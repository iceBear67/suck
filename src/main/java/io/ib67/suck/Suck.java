package io.ib67.suck;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.locks.LockSupport;

import static java.lang.Math.*;
import static java.lang.StringTemplate.STR;
import static java.net.http.HttpResponse.BodyHandlers.*;
import static java.util.Objects.requireNonNull;

public class Suck {
    protected final HttpClient client;
    protected final int preferredWorkers;

    protected Suck(HttpClient client, int preferredWorkers) {
        this.client = requireNonNull(client);
        this.preferredWorkers = preferredWorkers < 1 ? Runtime.getRuntime().availableProcessors() : preferredWorkers;
    }

    public static Suck ofDefault() {
        return new Suck(HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor()) // is explicitly http/1.1 more faster?
                .build(), Runtime.getRuntime().availableProcessors());
    }

    /**
     * Build a Suck Instance.
     * @param client Make sure your client is using {@link Executors#newVirtualThreadPerTaskExecutor()} as the executor.
     * @param preferredWorkers how many workers should be used for a task
     * @return suck
     */
    public static Suck of(HttpClient client, int preferredWorkers) {
        return new Suck(client, preferredWorkers);
    }

    public void downloadParallelized(HttpRequest request, Path file, DownloadListener downloadListener) throws IOException, InterruptedException {
        if (!Thread.currentThread().isVirtual()) {
            throw new IllegalStateException("Only use it in a virtual thread.");
        }
        if (Files.exists(file)) {
            throw new UnsupportedEncodingException(file + " exists.");
        }
        var _request = clone(request).HEAD();
        var response = client.send(_request.build(), discarding());
        var _contentLength = response.headers().firstValueAsLong("Content-Length");
        if (_contentLength.isPresent()) {
            downloadParallelized0(_contentLength.getAsLong(), request, file, downloadListener);
        } else {
            download(request, file, downloadListener);
        }
    }

    private void download(HttpRequest request, Path file, DownloadListener downloadListener) {
        var listener = downloadListener.onSegmentBegin(0, -1);
        int retries = 0;
        while (true) {
            try {
                client.send(request, ofFile(file));
                var len = file.toFile().length();
                listener.onSegmentProgress(null, len);
                return;
            } catch (IOException | InterruptedException e) {
                if (listener.onSegmentFailure(retries++, e)) {
                    LockSupport.parkNanos(Math.pow(2, retries), 1_000_000_000L);
                } else {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private void downloadParallelized0(long contentLength, HttpRequest request, Path file, DownloadListener downloadListener) throws IOException {
        if (contentLength / min(64, preferredWorkers) <= 1) {
            download(request, file, downloadListener);
            return;
        }
        var delta = contentLength % preferredWorkers;
        var segLen = min((contentLength - delta) / preferredWorkers, 1024 * 1024 * 1024);
        System.out.println(contentLength);
        try (StructuredTaskScope.ShutdownOnFailure scope = new StructuredTaskScope.ShutdownOnFailure();
             var openedFile = new RandomAccessFile(file.toFile(), "rw")) {
            var segments = new ArrayList<Segment>();
            var fc = openedFile.getChannel();
            var counts = (int) Math.floor((double) contentLength / segLen);
            for (int cnt = 0; cnt < counts; cnt++) {
                var _segLen = segLen;
                var curr = cnt * segLen;
                if (cnt == counts - 1) {
                    _segLen = _segLen + delta;
                }
                var mmapBuffer = fc.map(FileChannel.MapMode.READ_WRITE, curr, _segLen);
                segments.add(new Segment(cnt, mmapBuffer, request, curr));
            }
            downloadListener.onDownloadStart(segments.toArray(new Segment[0]));
            segments.forEach(seg -> scope.fork(() -> downloadSegment(seg, downloadListener.onSegmentBegin(seg.id, seg.getSize()))));
            scope.join();
            scope.throwIfFailed();
            downloadListener.onDownloadComplete();
        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private Void downloadSegment(Segment seg, SegmentListener listener) {
        seg.download(new SegmentListener() {
            @Override
            public void onSegmentProgress(Segment seg, long downloaded) {
                listener.onSegmentProgress(seg, downloaded);
            }

            @Override
            public boolean onSegmentFailure(int retries, Exception exception) { // 指数退避
                if (listener.onSegmentFailure(retries, exception)) {
                    exception.printStackTrace();
                    LockSupport.parkNanos((long) (pow(2, retries) * 1_000_000_000L));
                    return true;
                }
                return false;
            }
        });
        return null;
    }

    private static HttpRequest.Builder clone(HttpRequest original) {
        var _request = HttpRequest.newBuilder();
        _request.uri(original.uri());
        original.headers().map().forEach((k, v) -> _request.header(k, v.getLast()));
        original.timeout().ifPresent(_request::timeout);
        original.version().ifPresent(_request::version);
        return _request;
    }

    public class Segment {
        private final int id;
        private final ByteBuffer buf;
        private final HttpRequest req;
        private final long begin;

        private Segment(int id, ByteBuffer buf, HttpRequest req, long begin) {
            this.id = id;
            this.buf = requireNonNull(buf);
            this.req = requireNonNull(req);
            this.begin = begin;
        }

        public long getSize() {
            return buf.capacity();
        }

        public long getDownloaded() {
            return buf.position();
        }

        public void download(SegmentListener listener) {
            var _req = Suck.clone(req).header("Range", "bytes=" + begin + "-" + (begin + getSize() - 1)).GET().build();
            var retries = 0;
            while (true) {
                try {
                    client.send(_req, ofByteArrayConsumer(it -> it.ifPresent(b -> updateProgress(listener, b))));
                    retries = 0;
                    if (!buf.hasRemaining()) {
                        return; // success
                    }
                } catch (IOException | InterruptedException e) {
                    if (!listener.onSegmentFailure(retries++, e)) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }

        private void updateProgress(SegmentListener listener, byte[] b) {
            buf.put(b);
            listener.onSegmentProgress(this, b.length);
        }
    }


}

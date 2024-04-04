import io.ib67.suck.DownloadListener;
import io.ib67.suck.SegmentListener;
import io.ib67.suck.Suck;
import picocli.CommandLine;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.DoubleConsumer;

import static java.lang.StringTemplate.STR;

public class SuckCli {
    @CommandLine.Option(names = {"-U", "--url"}, description = "URL to file", required = true)
    URI uri;

    @CommandLine.Option(names = {"-O", "--out"}, description = "file output")
    Path output;

    @CommandLine.Option(names = {"-H", "--header"}, description = "header")
    String[] headers = new String[0];

    @CommandLine.Option(names = {"-R", "--retries"}, description = "max retries")
    int retries = 4;

    public static void main(String[] args) throws IOException, InterruptedException {
        System.out.println("Progress bar is broken lol");
        var startTime = Instant.now();
        var cli = new SuckCli();
        new CommandLine(cli).parseArgs(args);
        cli.run();
        var dur = Duration.between(Instant.now(),startTime);
        System.out.println("Used "+dur.getSeconds()+"s");
    }

    private void run() throws InterruptedException {
        var request = HttpRequest.newBuilder(uri);
        for (String header : headers) {
            var h = header.split(": ");
            request.header(h[0], h[1]);
        }
        if (output == null) {
            var a = uri.getPath().split("/");
            var fileName = a[a.length - 1];
            output = Path.of(fileName.isEmpty() ? "out" : fileName);
        }
        Thread.ofVirtual().start(() -> {
            try {
                Suck.ofDefault().downloadParallelized(
                                request.build(),
                                output,
                                new ProgressAwareDownloadListener(retries));
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }).join();
    }

    class ProgressAwareDownloadListener implements DownloadListener {
        private final int maxRetries;
        private volatile long bytesAccumulated;
        private volatile long bytesInSecond;
        private volatile long lastUpdated;
        private long total;

        private ReentrantLock lock = new ReentrantLock();

        ProgressAwareDownloadListener(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        @Override
        public void onDownloadStart(Suck.Segment[] segments) {
            total = Arrays.stream(segments).mapToLong(Suck.Segment::getSize).sum();
            System.out.println(STR."Total: \{total}");
        }

        @Override
        public void onDownloadComplete() {
            System.out.println("\nDownload success!");
        }

        @Override
        public SegmentListener onSegmentBegin(int id, long length) {
            return new SimpleSegmentListener();
        }

        private void updateProgressBar() {

        }

        private class SimpleSegmentListener implements SegmentListener {
            @Override
            public void onSegmentProgress(Suck.Segment segment, long newDownloaded) {
                if (System.currentTimeMillis() > lastUpdated + 500L) {
                    updateProgressBar();
                    bytesInSecond = 0;
                    lastUpdated = System.currentTimeMillis();
                }
                bytesAccumulated += newDownloaded;
                bytesInSecond += newDownloaded;
            }

            @Override
            public boolean onSegmentFailure(int retries, Exception exception) {
                return retries <= maxRetries;
            }
        }
    }
}

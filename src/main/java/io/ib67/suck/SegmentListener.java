package io.ib67.suck;

public interface SegmentListener {
    void onSegmentProgress(Suck.Segment segment, long newDownloaded);

    boolean onSegmentFailure(int retries, Exception exception);
}

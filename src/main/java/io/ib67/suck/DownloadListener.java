package io.ib67.suck;

public interface DownloadListener {
    void onDownloadStart(Suck.Segment[] segments);

    void onDownloadComplete();

    SegmentListener onSegmentBegin(int id, long length);
}

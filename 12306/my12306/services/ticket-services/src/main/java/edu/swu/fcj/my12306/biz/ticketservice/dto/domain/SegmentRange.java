package edu.swu.fcj.my12306.biz.ticketservice.dto.domain;

public record SegmentRange(int start, int end) {
    public SegmentRange {
        if (start < 0 || end <= start) throw new IllegalArgumentException("Invalid segment range");
    }
    public int length() { return end - start; }
    public boolean overlaps(SegmentRange other) { return start < other.end && other.start < end; }
}

package com.teahack.oxide.aim;
public record FrameMetrics(long totalFrames,long averageFrameNanos,long maximumFrameNanos,
        long averageProcessingLatencyNanos,long maximumProcessingLatencyNanos,
        long inputErrors,long configurationUpdates) {}

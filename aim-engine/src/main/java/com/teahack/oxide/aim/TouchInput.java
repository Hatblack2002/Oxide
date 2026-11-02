package com.teahack.oxide.aim;
/** Real user touch sample; never synthesizes Android touch events. */
public record TouchInput(float xPixels,float yPixels,long timestampNanos,Phase phase) {
    public enum Phase { PRESS, MOVE, RELEASE }
    public TouchInput {
        if(!Float.isFinite(xPixels)||!Float.isFinite(yPixels)) throw new IllegalArgumentException("Touch coordinates must be finite");
        if(timestampNanos<0) throw new IllegalArgumentException("Timestamp must be non-negative");
        if(phase==null) throw new IllegalArgumentException("Touch phase is required");
    }
}

package com.teahack.oxide.aim;
/** Viewport normalization and explicit user-selected reference-point calibration. */
public final class InputCalibration {
    public record Viewport(int widthPixels,int heightPixels) {
        public Viewport { if(widthPixels<=0||heightPixels<=0) throw new IllegalArgumentException("Viewport dimensions must be positive"); }
    }
    public record NormalizedTouch(float x,float y,long timestampNanos,TouchInput.Phase phase) {}
    private volatile Viewport viewport;
    public InputCalibration(int width,int height) { recalibrateViewport(width,height); }
    public void recalibrateViewport(int width,int height) { viewport=new Viewport(width,height); }
    public Viewport viewport() { return viewport; }
    public NormalizedTouch normalize(TouchInput input) {
        if(input==null) throw new IllegalArgumentException("Touch input required");
        Viewport v=viewport;
        return new NormalizedTouch(clamp(input.xPixels()/v.widthPixels(),0,1),
                clamp(input.yPixels()/v.heightPixels(),0,1),input.timestampNanos(),input.phase());
    }
    /** Applies an offset to a manually supplied calibration reference, not detected anatomy. */
    public float applyReferenceOffset(float y,float referenceHeight,float offset) {
        finite(y); finite(referenceHeight); finite(offset);
        if(y<0||y>1||referenceHeight<0||referenceHeight>1||offset< -1||offset>1)
            throw new IllegalArgumentException("Reference calibration outside range");
        return clamp(y+referenceHeight*offset,0,1);
    }
    private static float clamp(float v,float min,float max) { return Math.max(min,Math.min(max,v)); }
    private static void finite(float v) { if(!Float.isFinite(v)) throw new IllegalArgumentException("Reference must be finite"); }
}

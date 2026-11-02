package com.teahack.oxide.aim;
/** Exponential recovery model for explicit calibration measurements only. */
public final class RecoilModel {
    private float x,y; private long lastTimestamp;
    public synchronized void record(RecoilMeasurement m) {
        if(m==null) throw new IllegalArgumentException("Measurement required");
        if(m.timestampNanos()<=lastTimestamp) throw new IllegalArgumentException("Timestamps must increase");
        x=m.horizontal(); y=m.vertical(); lastTimestamp=m.timestampNanos();
    }
    public synchronized RecoveryCurve update(float rate,float dt) {
        if(!Float.isFinite(rate)||rate<0||!Float.isFinite(dt)||dt<=0||dt>1)
            throw new IllegalArgumentException("Invalid recovery parameters");
        float decay=(float)Math.exp(-rate*dt); x*=decay; y*=decay; return new RecoveryCurve(x,y);
    }
    public synchronized RecoveryCurve snapshot() { return new RecoveryCurve(x,y); }
    public synchronized void reset() { x=0; y=0; lastTimestamp=0; }
}

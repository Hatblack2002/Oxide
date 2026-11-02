package com.teahack.oxide.aim;
/** Explicitly supplied recoil calibration measurement. */
public record RecoilMeasurement(float horizontal,float vertical,long timestampNanos) {
    public RecoilMeasurement {
        if(!Float.isFinite(horizontal)||!Float.isFinite(vertical)||timestampNanos<=0)
            throw new IllegalArgumentException("Invalid recoil measurement");
    }
}

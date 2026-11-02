package com.teahack.oxide.aim;
/** Remaining measured recoil signal after exponential recovery. */
public record RecoveryCurve(float horizontal,float vertical) {
    public RecoveryCurve {
        if(!Float.isFinite(horizontal)||!Float.isFinite(vertical)) throw new IllegalArgumentException("Invalid recovery values");
    }
}

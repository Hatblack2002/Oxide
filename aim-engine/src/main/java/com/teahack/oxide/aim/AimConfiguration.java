package com.teahack.oxide.aim;
/** Immutable runtime configuration. */
public record AimConfiguration(float sensitivity, float neckCalibrationOffset,
        float smoothingTimeConstantSeconds, float deadZoneThreshold,
        float maximumNormalizedInput, float recoilRecoveryRate, boolean enabled) {
    public AimConfiguration {
        finite(sensitivity,"sensitivity"); finite(neckCalibrationOffset,"neckCalibrationOffset");
        finite(smoothingTimeConstantSeconds,"smoothingTimeConstantSeconds");
        finite(deadZoneThreshold,"deadZoneThreshold"); finite(maximumNormalizedInput,"maximumNormalizedInput");
        finite(recoilRecoveryRate,"recoilRecoveryRate");
        range(sensitivity,.05f,2f,"sensitivity"); range(neckCalibrationOffset,-1f,1f,"neckCalibrationOffset");
        range(smoothingTimeConstantSeconds,.001f,2f,"smoothingTimeConstantSeconds");
        range(deadZoneThreshold,0f,.95f,"deadZoneThreshold");
        range(maximumNormalizedInput,0f,1f,"maximumNormalizedInput");
        range(recoilRecoveryRate,0f,30f,"recoilRecoveryRate");
    }
    public static AimConfiguration defaults() { return new AimConfiguration(.65f,0f,.12f,.08f,1f,4f,true); }
    private static void finite(float v,String n) { if(!Float.isFinite(v)) throw new IllegalArgumentException(n+" must be finite"); }
    private static void range(float v,float min,float max,String n) { if(v<min||v>max) throw new IllegalArgumentException(n+" outside ["+min+", "+max+"]"); }
}

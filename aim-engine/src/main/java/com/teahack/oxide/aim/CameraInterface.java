package com.teahack.oxide.aim;
/** Calibration publication boundary; not a claim of native game-camera control. */
public interface CameraInterface {
    record CameraDelta(float horizontal,float vertical,float recoilCalibrationHorizontal,
            float recoilCalibrationVertical,long timestampNanos) {
        public CameraDelta {
            if(!Float.isFinite(horizontal)||!Float.isFinite(vertical)||
               !Float.isFinite(recoilCalibrationHorizontal)||!Float.isFinite(recoilCalibrationVertical)||timestampNanos<0)
                throw new IllegalArgumentException("Invalid camera calibration output");
        }
        public static CameraDelta neutral(long t) { return new CameraDelta(0,0,0,0,t); }
    }
    void submitCalibration(CameraDelta delta);
    CameraDelta getCurrentCalibration();
    void resetCalibration();
}

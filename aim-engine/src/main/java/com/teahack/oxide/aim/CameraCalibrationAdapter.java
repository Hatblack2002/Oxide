package com.teahack.oxide.aim;
import java.util.concurrent.atomic.AtomicReference;
/** Thread-safe in-memory adapter for tests and calibration consumers. */
public final class CameraCalibrationAdapter implements CameraInterface {
    private final AtomicReference<CameraDelta> state=new AtomicReference<>(CameraDelta.neutral(0));
    public void submitCalibration(CameraDelta d) { if(d==null) throw new IllegalArgumentException("Delta required"); state.set(d); }
    public CameraDelta getCurrentCalibration() { return state.get(); }
    public void resetCalibration() { state.set(CameraDelta.neutral(System.nanoTime())); }
}

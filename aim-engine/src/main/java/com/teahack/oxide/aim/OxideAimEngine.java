package com.teahack.oxide.aim;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
/** Manual-input calibration pipeline. No entity detection, selection, tracking or automatic targeting. */
public final class OxideAimEngine implements AutoCloseable {
    public record ProcessResult(boolean accepted,CameraInterface.CameraDelta output) {}
    private final AtomicReference<AimConfiguration> config=new AtomicReference<>(AimConfiguration.defaults());
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicLong lastTouch=new AtomicLong(-1),lastJoystick=new AtomicLong(-1);
    private final InputCalibration input;
    private final DeadZoneFilter deadZone=new DeadZoneFilter();
    private final RecoilModel recoil=new RecoilModel();
    private final AimDiagnostics diagnostics=new AimDiagnostics();
    private final CalibrationLogger logger;
    private final CameraInterface camera;
    private float smoothX,smoothY;
    public OxideAimEngine(int width,int height,CameraInterface camera,CalibrationLogger logger) {
        if(camera==null||logger==null) throw new IllegalArgumentException("Camera and logger required");
        input=new InputCalibration(width,height); this.camera=camera; this.logger=logger;
    }
    public synchronized void configure(AimConfiguration next) {
        ensureOpen(); if(next==null) throw new IllegalArgumentException("Configuration required");
        config.set(next); diagnostics.recordConfigurationUpdate(); logger.log("configuration.updated");
    }
    public AimConfiguration configuration() { return config.get(); }
    public InputCalibration inputCalibration() { return input; }
    public RecoilModel recoilModel() { return recoil; }
    public AimDiagnostics diagnostics() { return diagnostics; }
    public InputCalibration.NormalizedTouch processTouch(TouchInput sample) {
        ensureOpen(); long started=System.nanoTime();
        try {
            if(sample==null||sample.timestampNanos()<=lastTouch.get()) throw new IllegalArgumentException("Touch timestamps must increase");
            InputCalibration.NormalizedTouch result=input.normalize(sample); lastTouch.set(sample.timestampNanos()); return result;
        } catch(IllegalArgumentException e) { diagnostics.recordInputError(); throw e; }
        finally { diagnostics.recordLatency(System.nanoTime()-started); }
    }
    public synchronized ProcessResult processJoystick(JoystickState sample,float dt) {
        if(closed.get()) return new ProcessResult(false,CameraInterface.CameraDelta.neutral(0));
        long started=System.nanoTime();
        try {
            if(sample==null||!Float.isFinite(dt)||dt<=0||dt>1||sample.timestampNanos()<=lastJoystick.get())
                throw new IllegalArgumentException("Invalid joystick sample or frame time");
            AimConfiguration c=config.get();
            if(!c.enabled()) {
                lastJoystick.set(sample.timestampNanos()); smoothX=0; smoothY=0;
                CameraInterface.CameraDelta neutral=CameraInterface.CameraDelta.neutral(sample.timestampNanos());
                camera.submitCalibration(neutral); return new ProcessResult(true,neutral);
            }
            JoystickState filtered=deadZone.apply(sample,c.deadZoneThreshold());
            float targetX=filtered.horizontal()*c.sensitivity()*c.maximumNormalizedInput();
            float targetY=filtered.vertical()*c.sensitivity()*c.maximumNormalizedInput();
            if(dt>0.25f) { smoothX=targetX; smoothY=targetY; }
            else {
                float alpha=(float)(1-Math.exp(-dt/c.smoothingTimeConstantSeconds()));
                smoothX+=(targetX-smoothX)*alpha; smoothY+=(targetY-smoothY)*alpha;
            }
            RecoveryCurve measuredRecovery=recoil.update(c.recoilRecoveryRate(),dt);
            CameraInterface.CameraDelta output=new CameraInterface.CameraDelta(
                    smoothX,smoothY,measuredRecovery.horizontal(),measuredRecovery.vertical(),sample.timestampNanos());
            lastJoystick.set(sample.timestampNanos()); camera.submitCalibration(output);
            diagnostics.recordFrame((long)(dt*1_000_000_000L)); logger.log("input.joystick.processed");
            return new ProcessResult(true,output);
        } catch(IllegalArgumentException e) { diagnostics.recordInputError(); throw e; }
        finally { diagnostics.recordLatency(System.nanoTime()-started); }
    }
    public synchronized void reset() {
        ensureOpen(); smoothX=0; smoothY=0; lastTouch.set(-1); lastJoystick.set(-1);
        recoil.reset(); camera.resetCalibration(); diagnostics.reset(); logger.log("engine.reset");
    }
    public FrameMetrics metrics() { return diagnostics.snapshot(); }
    private void ensureOpen() { if(closed.get()) throw new IllegalStateException("Engine is shut down"); }
    public synchronized void close() {
        if(closed.compareAndSet(false,true)) { smoothX=0; smoothY=0; recoil.reset(); camera.resetCalibration(); logger.log("engine.shutdown"); logger.close(); }
    }
}

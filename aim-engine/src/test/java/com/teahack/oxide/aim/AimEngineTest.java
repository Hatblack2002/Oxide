package com.teahack.oxide.aim;
import org.junit.Test;
import static org.junit.Assert.*;
public class AimEngineTest {
 private OxideAimEngine make(CameraCalibrationAdapter c) { return new OxideAimEngine(2400,1080,c,new CalibrationLogger(16,s->{})); }
 @Test public void disabledStateIsNeutral() { var c=new CameraCalibrationAdapter(); try(var e=make(c)) { e.configure(new AimConfiguration(.65f,0,.1f,.05f,1,4,false)); var r=e.processJoystick(new JoystickState(.8f,.4f,true,10),1f/60); assertTrue(r.accepted()); assertEquals(0,r.output().horizontal(),0); } }
 @Test public void resetClearsState() { var c=new CameraCalibrationAdapter(); try(var e=make(c)) { e.processJoystick(new JoystickState(.8f,.2f,true,10),1f/60); e.reset(); assertEquals(0,c.getCurrentCalibration().horizontal(),0); } }
 @Test public void shutdownRejectsNewWork() { var e=make(new CameraCalibrationAdapter()); e.close(); assertFalse(e.processJoystick(new JoystickState(0,0,false,10),1f/60).accepted()); try { e.processTouch(new TouchInput(1,1,1,TouchInput.Phase.PRESS)); fail(); } catch(IllegalStateException expected) { assertNotNull(expected.getMessage()); } }
 @Test public void diagnosticsCountFramesAndUpdates() { try(var e=make(new CameraCalibrationAdapter())) { e.configure(AimConfiguration.defaults()); e.processJoystick(new JoystickState(.2f,.2f,true,1),1f/60); assertEquals(1,e.metrics().totalFrames()); assertEquals(1,e.metrics().configurationUpdates()); } }
 @Test public void smoothingIsComparableAt30To120Fps() { float a=run(30),b=run(60),c=run(120); assertEquals(a,b,.015f); assertEquals(b,c,.015f); }
 private float run(int fps) { var c=new CameraCalibrationAdapter(); try(var e=make(c)) { for(int i=1;i<=fps;i++) e.processJoystick(new JoystickState(.5f,0,true,i),1f/fps); return c.getCurrentCalibration().horizontal(); } }
}

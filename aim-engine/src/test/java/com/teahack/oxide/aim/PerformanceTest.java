package com.teahack.oxide.aim;
import org.junit.Test;
import static org.junit.Assert.*;
public class PerformanceTest {
 @Test public void sustainedFramesRemainFinite() { var c=new CameraCalibrationAdapter(); try(var e=new OxideAimEngine(2400,1080,c,new CalibrationLogger(8,s->{}))) { for(int i=1;i<=100000;i++) e.processJoystick(new JoystickState(.25f,-.5f,true,i),1f/120); assertTrue(Float.isFinite(c.getCurrentCalibration().horizontal())); assertEquals(100000,e.metrics().totalFrames()); } }
}

package com.teahack.oxide.aim;
import org.junit.Test;
import static org.junit.Assert.*;
public class InputCalibrationTest {
 @Test public void normalizesAndPreservesPhases() { InputCalibration c=new InputCalibration(2000,1000); var p=c.normalize(new TouchInput(1000,500,1,TouchInput.Phase.PRESS)); var r=c.normalize(new TouchInput(2000,1000,2,TouchInput.Phase.RELEASE)); assertEquals(.5f,p.x(),.0001f); assertEquals(TouchInput.Phase.PRESS,p.phase()); assertEquals(1f,r.x(),.0001f); assertEquals(TouchInput.Phase.RELEASE,r.phase()); }
 @Test(expected=IllegalArgumentException.class) public void rejectsBadViewport() { new InputCalibration(0,100); }
 @Test public void recalibratesViewport() { InputCalibration c=new InputCalibration(100,100); c.recalibrateViewport(200,100); assertEquals(.5f,c.normalize(new TouchInput(100,50,1,TouchInput.Phase.MOVE)).x(),.0001f); }
 @Test public void referenceOffsetClamps() { assertEquals(1f,new InputCalibration(10,10).applyReferenceOffset(.9f,.5f,1f),.0001f); }
}

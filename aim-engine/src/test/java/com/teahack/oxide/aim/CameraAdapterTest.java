package com.teahack.oxide.aim;
import org.junit.Test;
import static org.junit.Assert.*;
public class CameraAdapterTest {
 @Test public void atomicallyPublishesImmutableValueAndResets() { var a=new CameraCalibrationAdapter(); var d=new CameraInterface.CameraDelta(.2f,-.3f,1,-1,9); a.submitCalibration(d); assertSame(d,a.getCurrentCalibration()); a.resetCalibration(); assertEquals(0,a.getCurrentCalibration().horizontal(),0); }
}

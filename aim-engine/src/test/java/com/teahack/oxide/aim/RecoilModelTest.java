package com.teahack.oxide.aim;
import org.junit.Test;
import static org.junit.Assert.*;
public class RecoilModelTest {
 @Test public void exponentialRecoveryReducesBothAxes() { RecoilModel m=new RecoilModel(); m.record(new RecoilMeasurement(10,-8,100)); var r=m.update(4,.1f); assertTrue(Math.abs(r.horizontal())<10); assertTrue(Math.abs(r.vertical())<8); }
 @Test(expected=IllegalArgumentException.class) public void rejectsNonMonotonicTimestamp() { RecoilModel m=new RecoilModel(); m.record(new RecoilMeasurement(1,1,10)); m.record(new RecoilMeasurement(2,2,10)); }
 @Test public void resetClearsMeasurements() { RecoilModel m=new RecoilModel(); m.record(new RecoilMeasurement(2,3,10)); m.reset(); assertEquals(new RecoveryCurve(0,0),m.snapshot()); }
}

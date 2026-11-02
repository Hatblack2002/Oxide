package com.teahack.oxide.aim;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
public class ConcurrencyTest {
 @Test public void concurrentConfigurationAndInputStayValid() throws Exception {
  var camera=new CameraCalibrationAdapter(); var logger=new CalibrationLogger(64,s->{});
  try(var e=new OxideAimEngine(1920,1080,camera,logger)) {
   var start=new CountDownLatch(1); var failure=new AtomicReference<Throwable>();
   Thread config=new Thread(()->{ try { start.await(); for(int i=0;i<500;i++) e.configure(new AimConfiguration(.5f+(i%10)*.01f,0,.08f,.05f,1,3,true)); } catch(Throwable t) { failure.set(t); } });
   Thread input=new Thread(()->{ try { start.await(); for(int i=1;i<=500;i++) e.processJoystick(new JoystickState(.3f,-.2f,true,i),1f/60); } catch(Throwable t) { failure.set(t); } });
   config.start(); input.start(); start.countDown(); config.join(); input.join(); assertNull(failure.get()); assertTrue(Float.isFinite(camera.getCurrentCalibration().horizontal()));
  }
 }
}

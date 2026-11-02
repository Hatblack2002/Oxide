package com.teahack.oxide.aim;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
public class CalibrationLoggerTest {
 @Test public void queueIsBoundedAndOverflowDoesNotBlock() throws Exception {
  var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
  try(var logger=new CalibrationLogger(1,s->{ entered.countDown(); try { release.await(2,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } })) {
   assertTrue(logger.log("first")); assertTrue(entered.await(1,TimeUnit.SECONDS));
   assertTrue(logger.log("second")); assertFalse(logger.log("overflow")); assertEquals(1,logger.droppedCount()); assertEquals(1,logger.queuedCount());
   release.countDown();
  }
 }
}

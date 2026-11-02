package com.teahack.oxide.aim;
import org.junit.Test;
import static org.junit.Assert.*;
public class DeadZoneFilterTest {
 @Test public void filtersInsideZoneAndPreservesPressed() { var r=new DeadZoneFilter().apply(new JoystickState(.02f,.02f,true,1),.1f); assertEquals(0f,r.horizontal(),0); assertTrue(r.pressed()); }
 @Test public void preservesDirectionAndUnitMagnitude() { var r=new DeadZoneFilter().apply(new JoystickState(.6f,.8f,true,1),.2f); assertEquals(.75f,r.horizontal()/r.vertical(),.0001f); assertEquals(1f,(float)Math.hypot(r.horizontal(),r.vertical()),.0001f); }
 @Test public void deterministic() { var f=new DeadZoneFilter(); var i=new JoystickState(-.4f,.3f,false,8); assertEquals(f.apply(i,.12f),f.apply(i,.12f)); }
}

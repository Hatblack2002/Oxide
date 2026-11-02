package com.teahack.oxide.aim;
import org.junit.Test;
import static org.junit.Assert.*;
public class AimConfigurationTest {
 @Test public void defaultsAndBounds() { assertTrue(AimConfiguration.defaults().enabled()); new AimConfiguration(.05f,-1,.001f,0,0,0,true); new AimConfiguration(2,1,2,.95f,1,30,false); }
 @Test public void rejectsNanAndInfinity() { for(float v:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}) try { new AimConfiguration(v,0,.1f,.1f,1,1,true); fail(); } catch(IllegalArgumentException expected) { assertNotNull(expected.getMessage()); } }
}

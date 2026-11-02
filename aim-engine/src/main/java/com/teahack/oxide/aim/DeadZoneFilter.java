package com.teahack.oxide.aim;
/** Radial dead-zone filter preserving input direction. */
public final class DeadZoneFilter {
    public JoystickState apply(JoystickState in,float threshold) {
        if(in==null) throw new IllegalArgumentException("Joystick input required");
        if(!Float.isFinite(threshold)||threshold<0||threshold>=1) throw new IllegalArgumentException("Threshold must be in [0,1)");
        float x=in.horizontal(),y=in.vertical(),m=(float)Math.hypot(x,y);
        if(m<=threshold||m==0) return new JoystickState(0,0,in.pressed(),in.timestampNanos());
        float output=(Math.min(1,m)-threshold)/(1-threshold),scale=output/m;
        return new JoystickState(x*scale,y*scale,in.pressed(),in.timestampNanos());
    }
}

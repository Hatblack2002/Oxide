package com.teahack.oxide.aim;
/** Normalized joystick sample. */
public record JoystickState(float horizontal,float vertical,boolean pressed,long timestampNanos) {
    public JoystickState {
        if(!Float.isFinite(horizontal)||!Float.isFinite(vertical)) throw new IllegalArgumentException("Axes must be finite");
        if(Math.abs(horizontal)>1||Math.abs(vertical)>1) throw new IllegalArgumentException("Axes must be in [-1,1]");
        if(timestampNanos<0) throw new IllegalArgumentException("Timestamp must be non-negative");
    }
}

package com.arcar.android;

/**
 * Digital simulation actions (Rocket League style).
 * Steer / pitch / yaw come from analogue sticks — not key binds.
 */
public enum Action {
    JUMP("Saltar"),
    BOOST("Boost"),
    ACCELERATE("Ir adelante"),
    DECELERATE("Ir atrás"),
    POWERSLIDE("Derrapar"),
    TOGGLE_BALL_CAM("Toggle enfocar en balón/auto"),
    AIR_ROLL_RIGHT("Air Roll Right"),
    AIR_ROLL_LEFT("Air Roll Left");

    public final String label;
    Action(String label) { this.label = label; }
}

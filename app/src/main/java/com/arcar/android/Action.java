package com.arcar.android;

/** Logical actions the player can bind to controller buttons / axes. */
public enum Action {
    ACCELERATE,
    REVERSE,
    STEER,          // axis
    PITCH,          // axis (left stick Y)
    YAW,            // axis (right stick X)
    AIR_ROLL_LEFT,
    AIR_ROLL_RIGHT,
    JUMP,
    BOOST,
    POWERSLIDE,
    BALL_CAM,
    RESET
}

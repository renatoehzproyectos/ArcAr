package com.arcar.android;

/**
 * Simulation-only actions (drive the car).
 * Camera / Settings / Console / Reset are UI-only and not bindable here.
 */
public enum Action {
    ACCELERATE,
    DECELERATE,     // reverse / brake
    STEER_LEFT,
    STEER_RIGHT,
    PITCH_UP,
    PITCH_DOWN,
    YAW_LEFT,
    YAW_RIGHT,
    AIR_ROLL_LEFT,
    AIR_ROLL_RIGHT,
    JUMP,
    BOOST,
    POWERSLIDE
}

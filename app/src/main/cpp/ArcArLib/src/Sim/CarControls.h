#pragma once
#include "../BaseInc.h"

AA_NS_START

// Stores all control inputs to a car
struct CarControls {
	// Driving control
	float throttle, steer;

	// Air orientation control
	float pitch, yaw, roll;

	// Boolean action inputs
	bool jump, boost, handbrake;

	// Maybe someday...
	// bool useItem;

	CarControls() {
		// Initialize everything as zero
		memset(this, 0, sizeof(CarControls));
	}

	// Makes all values range-valid (clamps from -1 to 1)
	void ClampFix() {
		throttle	= AA_CLAMP(throttle,	-1, 1);
		steer		= AA_CLAMP(steer,		-1, 1);
		pitch		= AA_CLAMP(pitch,		-1, 1);
		yaw			= AA_CLAMP(yaw,		-1, 1);
		roll		= AA_CLAMP(roll,		-1, 1);
	}
};

#define CAR_CONTROLS_SERIALIZATION_FIELDS(name) \
name.throttle, name.steer, \
name.pitch, name.yaw, name.roll, \
name.boost, name.jump, name.handbrake

AA_NS_END
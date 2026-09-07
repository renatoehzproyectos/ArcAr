#include "CarConfig.h"

AA_NS_START

// Default car-type config definitions

// For those confused about the hitbox numbers,
// as any Google search for car hitbox sizes will conflict with this:
// - They are all wrong. The values the reference game gives you
//   when you call the function GetLocalCollisionExtent() are
//   slightly larger than the actual values used in simulation.
//
// Why?
// - Idk lol.
//
// How do I know I'm right?
// -  The hitbox values here produce a matching inertia matrix with the reference game,
//    the ones everyone else uses/shares do not.

const static Vec HITBOX_SIZES[7] = { 
	{ 120.507f,	86.6994f,	38.6591f	}, // BODY_A
	{ 130.427f,	85.7799f,	33.8f		}, // BODY_B
	{ 131.32f,	87.1704f,	31.8944f	}, // BODY_C
	{ 133.992f,	83.021f,	32.8f		}, // BODY_D
	{ 129.519f,	84.6879f,	36.6591f	}, // BODY_E
	{ 123.22f,	79.2103f,	44.1591f	}, // BODY_F

	{ 120.507f + 0.134f, 86.6994f + 0.134f,	38.6591f + 0.134f}   // BODY_G
};

const static Vec HITBOX_OFFSETS[7] = { 
	{ 13.8757f,	0, 20.755f	}, 
	{ 9.f,		0, 15.75f	},	
	{ 9.00857f, 0, 12.0942f	},
	{ 12.5f,	0, 11.75f	},
	{ 13.8757f,	0, 20.755f	},
	{ 11.3757f,	0, 21.505f	},

	{ 13.8757f,	0, 15.0f	}
};

constexpr float FRONT_WHEEL_RADS[7] = {
	12.50f, 12.00f, 12.50f, 13.50f, 12.50f, 15.00f, 
	12.50f
};

constexpr float BACK_WHEEL_RADS[7] = {
	15.00f, 13.50f, 17.00f, 15.00f, 15.00f, 15.00f, 
	15.00f
};

constexpr float FRONT_WHEEL_SUS_REST[7] = {
	38.755f, 33.95f, 31.9242f, 29.7f, 38.755f, 39.505f, 
	33.0f
};

constexpr float BACK_WHEEL_SUS_REST[7] = {
	37.055f, 33.85f, 27.9242f, 29.666f, 37.055f, 39.105f, 
	31.3f
};

const static Vec FRONT_WHEELS_OFFSET[7] = { 
	{ 51.25f, 25.90f, 20.755f	}, 
	{ 50.30f, 31.10f, 15.75f	},
	{ 49.97f, 27.80f, 12.0942f	},
	{ 51.50f, 26.67f, 11.75f	},
	{ 51.25f, 25.90f, 20.755f	},
	{ 51.25f, 25.90f, 21.505f	},

	{ 51.25f, 5.000f, 15.000f	},
};

const static Vec BACK_WHEELS_OFFSET[7] = { 
	{ -33.75f, 29.50f, 20.755f	}, 
	{ -34.75f, 33.00f, 15.75f	},
	{ -35.43f, 20.28f, 12.0942f	},
	{ -35.75f, 35.00f, 11.75f	},
	{ -34.00f, 29.50f, 20.755f	},
	{ -33.75f, 29.50f, 21.505f	},

	{ -33.75f, 29.50f, 15.000f	},
};

// Using a macro here for convenience
#define MAKE_CAR_CONFIG(name, index, threeWheels) \
	const CarConfig CAR_CONFIG_##name = { \
		HITBOX_SIZES[index],   \
		HITBOX_OFFSETS[index], \
		{ FRONT_WHEEL_RADS[index], FRONT_WHEEL_SUS_REST[index], FRONT_WHEELS_OFFSET[index] }, \
		{ BACK_WHEEL_RADS[index], BACK_WHEEL_SUS_REST[index], BACK_WHEELS_OFFSET[index] },    \
		threeWheels \
	}

MAKE_CAR_CONFIG(BODY_A, 0, false);
MAKE_CAR_CONFIG(BODY_B, 1, false);
MAKE_CAR_CONFIG(BODY_C, 2, false);
MAKE_CAR_CONFIG(BODY_D, 3, false);
MAKE_CAR_CONFIG(BODY_E, 4, false);
MAKE_CAR_CONFIG(BODY_F, 5, false);
MAKE_CAR_CONFIG(BODY_G, 6, true);

AA_NS_END
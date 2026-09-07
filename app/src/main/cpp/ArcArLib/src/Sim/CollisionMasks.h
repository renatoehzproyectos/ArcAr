#pragma once
#include "../Framework.h"

AA_NS_START

// Collision masks for different types of objects
// Used so that the net in basketball doesn't collide with the cars
enum CollisionMasks : uint32_t {
	BASKETBALL_NET = (1 << 8),
	SHATTER_TILE = (1 << 9),
	SHATTER_FLOOR = (1 << 10),
};

AA_NS_END
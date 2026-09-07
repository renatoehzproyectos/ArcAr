#pragma once
#include "../Framework.h"

AA_NS_START

enum class GameMode : byte {
	STANDARD,
	BASKETBALL,
	HOMING,
	HOCKEY,
	SHATTER,

	// I will not add rumble unless I am given a large amount of money, or, alternatively, a large amount of candy corn (I love candy corn)

	// Standard mode but without goals, boost pads, or the arena hull. The cars and ball will fall infinitely.
	THE_VOID,
};

constexpr const char* GAMEMODE_STRS[] = {
	"standard",
	"basketball",
	"homing",
	"hockey",
	"shatter",
	"void"
};

AA_NS_END
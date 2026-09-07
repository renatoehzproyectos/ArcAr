#pragma once
#include "../../../GameConst.h"
#include "../../../../libsrc/bullet3-3.24/BulletDynamics/Dynamics/btRigidBody.h"

AA_NS_START

struct ShatterTileState {
	enum {
		STATE_FULL = 0,
		STATE_DAMAGED,
		STATE_BROKEN
	};
	uint8_t damageState = STATE_FULL;
};

struct ShatterTilesState {
	enum {
		STATE_FULL = 0,
		STATE_DAMAGED,
		STATE_BROKEN
	};

	ShatterTileState states[GameConst::Shatter::TEAM_AMOUNT][GameConst::Shatter::NUM_TILES_PER_TEAM];

	ShatterTilesState() {
		for (int i = 0; i < GameConst::Shatter::TEAM_AMOUNT; i++)
			for (int j = 0; j < GameConst::Shatter::NUM_TILES_PER_TEAM; j++)
				states[i][j] = ShatterTileState();
	}

	// TODO: Add serialization
};

namespace ShatterTiles {
	void Init();

	Vec GetTilePos(int team, int index);
	std::vector<btCollisionShape*> MakeTileShapes();
	std::vector<int> GetNeighborIndices(int startIdx, int radius);
};

AA_NS_END
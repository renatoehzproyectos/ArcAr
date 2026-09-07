#include "ShatterTiles.h"

#include "../../../../libsrc/bullet3-3.24/BulletCollision/CollisionShapes/btConvexHullShape.h"

AA_NS_START

static Vec g_TilePositions[GameConst::Shatter::NUM_TILES_PER_TEAM] = {};

// NOTE: Neighbors include the starting tile
static std::vector<int> g_TileNeighbors1[GameConst::Shatter::NUM_TILES_PER_TEAM] = {};
static std::vector<int> g_TileNeighbors2[GameConst::Shatter::NUM_TILES_PER_TEAM] = {};

Vec ShatterTiles::GetTilePos(int team, int index) {
	using namespace GameConst;

	assert(team >= 0 && team <= 1);
	assert(index >= 0 && team < Shatter::NUM_TILES_PER_TEAM);

	return g_TilePositions[index] * ((team == 0) ? -1 : 1);
}

std::vector<btCollisionShape*> ShatterTiles::MakeTileShapes() {
	using namespace GameConst;

	auto results = std::vector<btCollisionShape*>();

	for (int team = 0; team <= 1; team++) {
		for (int i = 0; i < Shatter::NUM_TILES_PER_TEAM; i++) {
			Vec pos = GetTilePos(team, i);

			btConvexHullShape* hullShape = new btConvexHullShape();

			for (int j = 0; j < 6; j++) {

				Vec vert = pos * UU_TO_BT + Shatter::TILE_HEXAGON_VERTS_BT[j];

				constexpr float CLAMP_Y = Shatter::TILE_OFFSET_Y * UU_TO_BT;

				// Clamp vert from crossing middle part at x=0
				if (team == 0) {
					// Clamp max to CLAMP_Y
					vert.y = AA_MIN(vert.y, -CLAMP_Y);
				} else {
					// Clamp min to CLAMP_Y
					vert.y = AA_MAX(vert.y, CLAMP_Y);
				}

				hullShape->addPoint(vert);
			}

			hullShape->recalcLocalAabb();
			btVector3 localInertia;
			hullShape->calculateLocalInertia(0, localInertia);

			results.push_back(hullShape);
		}
	}

	return results;
}

void ShatterTiles::Init() {
	using namespace GameConst;

	{ // Generate g_TilePositions by making rows along X

		int curIdx = 0;
		float y = Shatter::TILE_OFFSET_Y;
		for (
			int i = 0, numTiles = Shatter::TILES_IN_FIRST_ROW;
			i < Shatter::NUM_TILE_ROWS;
			i++, numTiles--) {

			// Generate column (along x)
			float rowSizeX = Shatter::TILE_WIDTH_X * numTiles;
			float rowStartX = -(rowSizeX / 2.f) + (Shatter::TILE_WIDTH_X/2);

			for (int j = 0; j < numTiles; j++, curIdx++) {
				if (curIdx > Shatter::NUM_TILES_PER_TEAM)
					AA_ERR_CLOSE("ShatterTiles::Init(): Exceeded maximum tile count, make sure tile info is correct");

				float x = rowStartX + Shatter::TILE_WIDTH_X * j;
				g_TilePositions[curIdx] = Vec(x, y, 0);
			}

			y += Shatter::ROW_OFFSET_Y;
		}

		if (curIdx < Shatter::NUM_TILES_PER_TEAM)
			AA_ERR_CLOSE("ShatterTiles::Init(): Failed to reach tile amount, make sure tile info is correct");
	}

	{ // Generate g_TileNeighbors

		constexpr float NEIGHBOR_MAX_RADIUS = Shatter::TILE_WIDTH_X * 1.2f;
		int maxNeighbors1 = 0, maxNeighbors2 = 0;
		for (int i = 0; i < Shatter::NUM_TILES_PER_TEAM; i++) {
			Vec pos = GetTilePos(0, i);
			auto& neighborMap1 = g_TileNeighbors1[i];
			auto& neighborMap2 = g_TileNeighbors2[i];
			for (int j = 0; j < Shatter::NUM_TILES_PER_TEAM; j++) {
				Vec otherPos = GetTilePos(0, j);
				if (pos.Dist(otherPos) < NEIGHBOR_MAX_RADIUS)
					neighborMap1.push_back(j);
				if (pos.Dist(otherPos) < NEIGHBOR_MAX_RADIUS * 2)
					neighborMap2.push_back(j);

				maxNeighbors1 = AA_MAX(maxNeighbors1, neighborMap1.size());
				maxNeighbors2 = AA_MAX(maxNeighbors2, neighborMap2.size());
			}
		}

		if (maxNeighbors1 > 7 || maxNeighbors2 > 20)
			AA_ERR_CLOSE("ShatterTiles::Init(): Too high neighbor count, tile placement may be incorrect");
	}
}

// TODO: Return reference instead of copy
std::vector<int> ShatterTiles::GetNeighborIndices(int startIdx, int radius) {
	if (radius < 1 || radius > 3)
		AA_ERR_CLOSE("ShatterTiles::GetNeighborIndices(): Radius must be from 1-3");

	if (radius == 1) {
		return { startIdx };
	} else if (radius == 2) {
		return g_TileNeighbors1[startIdx];
	} else {
		return g_TileNeighbors2[startIdx];
	}
}

AA_NS_END
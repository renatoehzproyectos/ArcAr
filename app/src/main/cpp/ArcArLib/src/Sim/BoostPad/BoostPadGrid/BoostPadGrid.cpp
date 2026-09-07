#include "BoostPadGrid.h"

AA_NS_START

void BoostPadGrid::CheckCollision(Car* car) {
	if (car->_internalState.isDemoed || car->_internalState.boost >= GameConst::BOOST_MAX)
		return;

	Vec carPos = car->_rigidBody.getWorldTransform().m_origin * BT_TO_UU;

	if (carPos.z > EXTENT_Z)
		return;

	int indexX = carPos.x / CELL_SIZE_X + (CELLS_X / 2);
	int indexY = carPos.y / CELL_SIZE_Y + (CELLS_Y / 2);

	for (int i = AA_MAX(indexX - 1, 0); i <= AA_MIN(indexX + 1, CELLS_X - 1); i++) {
		for (int j = AA_MAX(indexY - 1, 0); j <= AA_MIN(indexY + 1, CELLS_Y - 1); j++) {
			BoostPad* pad = pads[i][j];
			if (pad) {
				pad->_CheckCollide(car);
			}
		}
	}
}

void BoostPadGrid::Add(BoostPad* pad) {
	int indexX = pad->config.pos.x / CELL_SIZE_X + (CELLS_X / 2);
	int indexY = pad->config.pos.y / CELL_SIZE_Y + (CELLS_Y / 2);

	BoostPad*& ptrInArray = pads[indexX][indexY];
	if (ptrInArray != NULL) {
		AA_ERR_CLOSE(
			"BoostPadGrid::Add(): Failed to add a boost pad where there already was one " <<
			"(old: " << ptrInArray->config.pos << ", new: " << pad->config.pos << ") -> " <<
			"[" << indexX << ", " << indexY << "]"
		);
	} else {
		ptrInArray = pad;
	}
}

AA_NS_END
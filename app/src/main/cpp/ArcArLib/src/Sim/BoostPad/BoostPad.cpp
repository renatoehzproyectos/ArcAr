#include "BoostPad.h"

#include "../../GameConst.h"

#include "../../../libsrc/bullet3-3.24/BulletDynamics/Dynamics/btRigidBody.h"

AA_NS_START

void BoostPadConfig::Serialize(DataStreamOut& out) const {
	out.WriteMultiple(
		BOOSTPADCONFIG_SERIALIZATION_FIELDS
	);
}

void BoostPadConfig::Deserialize(DataStreamIn& in) {
	in.ReadMultiple(
		BOOSTPADCONFIG_SERIALIZATION_FIELDS
	);
}

void BoostPadState::Serialize(DataStreamOut& out) const {
	out.WriteMultiple(
		BOOSTPAD_SERIALIZATION_FIELDS
	);
}

void BoostPadState::Deserialize(DataStreamIn& in) {
	in.ReadMultiple(
		BOOSTPAD_SERIALIZATION_FIELDS
	);
}

BoostPad* BoostPad::_AllocBoostPad() {
	return new BoostPad();
}

void BoostPad::_Setup(const BoostPadConfig& config) {
	this->config = config;

	this->_posBT = config.pos * UU_TO_BT;

	{
		using namespace GameConst::BoostPads;

		float boxRad = (config.isBig ? BOX_RAD_BIG : BOX_RAD_SMALL) * UU_TO_BT;
		this->_boxMinBT = this->_posBT - Vec(boxRad, boxRad, 0);
		this->_boxMaxBT = this->_posBT + Vec(boxRad, boxRad, BOX_HEIGHT * UU_TO_BT);
	}
}

void BoostPad::_PreTickUpdate(float tickTime) {

	if (_internalState.cooldown > 0) {
		_internalState.cooldown = AA_MAX(_internalState.cooldown - tickTime, 0);
	}

	_internalState.isActive = (_internalState.cooldown == 0);

	_internalState.curLockedCar = NULL;
}

void BoostPad::_CheckCollide(Car* car) {
	using namespace GameConst::BoostPads;

	Vec carPosBT = car->_rigidBody.getWorldTransform().m_origin;

	bool colliding = false;
	if (_internalState.prevLockedCarID == car->id) {
		// Check with AABB-hitbox collision

		btVector3 carMinBT, carMaxBT;
		car->_rigidBody.getAabb(carMinBT, carMaxBT);

		// TODO: Account for orientation
		colliding = (_boxMaxBT > carMinBT) && (_boxMinBT < carMaxBT);
	} else {
		// Check with cylinder-origin collision

		float rad = (config.isBig ? CYL_RAD_BIG : CYL_RAD_SMALL) * UU_TO_BT;
		if (carPosBT.DistSq2D(this->_posBT) < (rad * rad))
			colliding = abs(carPosBT.z - this->_posBT.z) < (CYL_HEIGHT * UU_TO_BT);
	}

	if (colliding)
		_internalState.curLockedCar = car;
}

void BoostPad::_PostTickUpdate(float tickTime, const MutatorConfig& mutatorConfig) {
	using namespace GameConst::BoostPads;

	uint32_t lockedCarID = 0;
	if (_internalState.curLockedCar) {
		lockedCarID = _internalState.curLockedCar->id;

		if (_internalState.isActive) {
			float boostToAdd = config.isBig ? BOOST_AMOUNT_BIG : BOOST_AMOUNT_SMALL;
			_internalState.curLockedCar->_internalState.boost = AA_MIN(_internalState.curLockedCar->_internalState.boost + boostToAdd, GameConst::BOOST_MAX);

			_internalState.isActive = false;
			_internalState.cooldown = config.isBig ? mutatorConfig.boostPadCooldown_Big : mutatorConfig.boostPadCooldown_Small;
		}
	}

	_internalState.prevLockedCarID = lockedCarID;
}

AA_NS_END

#include "Ball.h"

#include "../../GameConst.h"
#include "../Car/Car.h"

#include "../../../libsrc/bullet3-3.24/BulletDynamics/Dynamics/btDynamicsWorld.h"
#include "../../../libsrc/bullet3-3.24/BulletCollision/CollisionShapes/btConvexHullShape.h"
#include "../CollisionMasks.h"

AA_NS_START

bool BallState::Matches(const BallState& other, float marginPos, float marginVel, float marginAngVel) const {
	return
		pos.DistSq(other.pos) < (marginPos * marginPos) &&
		vel.DistSq(other.vel) < (marginVel * marginVel) &&
		angVel.DistSq(other.angVel) < (marginAngVel * marginAngVel);
}

void BallState::Serialize(DataStreamOut& out) {
	out.WriteMultiple(BALLSTATE_SERIALIZATION_FIELDS);
}

void BallState::Deserialize(DataStreamIn& in) {
	in.ReadMultiple(BALLSTATE_SERIALIZATION_FIELDS);
}

BallState Ball::GetState() {
	_internalState.pos = _rigidBody.getWorldTransform().getOrigin() * BT_TO_UU;
	_internalState.rotMat = _rigidBody.getWorldTransform().getBasis();
	_internalState.vel = _rigidBody.getLinearVelocity() * BT_TO_UU;
	_internalState.angVel = _rigidBody.getAngularVelocity();
	return _internalState;
}

void Ball::SetState(const BallState& state) {

	_internalState = state;

	btTransform newTransform;
	newTransform.setOrigin(state.pos * UU_TO_BT);
	newTransform.setBasis(state.rotMat);
	_rigidBody.setWorldTransform(newTransform);
	_rigidBody.setLinearVelocity(state.vel * UU_TO_BT);
	_rigidBody.setAngularVelocity(state.angVel);
	_rigidBody.updateInertiaTensor();
	if (!state.vel.IsZero() || !state.angVel.IsZero())
		_rigidBody.setActivationState(ACTIVE_TAG);

	_velocityImpulseCache = { 0,0,0 };
	_internalState.tickCountSinceUpdate = 0;
}

btCollisionShape* MakeBallCollisionShape(GameMode gameMode, const MutatorConfig& mutatorConfig, btVector3& localIntertia) {
	
	if (gameMode == GameMode::HOCKEY) {
		using namespace GameConst;

		auto shape = new btConvexHullShape();
		
		float angStep = (M_PI * 2) / Hockey::PUCK_CIRCLE_POINT_AMOUNT;
		float curAng = 0;
		for (int i = 0; i < Hockey::PUCK_CIRCLE_POINT_AMOUNT; i++) {
			Vec point = Vec(
				cosf(curAng) * mutatorConfig.ballRadius * UU_TO_BT,
				sinf(curAng) * mutatorConfig.ballRadius * UU_TO_BT,
				Hockey::PUCK_HEIGHT / 2 * UU_TO_BT
			);

			shape->addPoint(point, false);
			point.z *= -1;
			shape->addPoint(point, true);

			curAng += angStep;
		}
		shape->recalcLocalAabb();
		shape->calculateLocalInertia(mutatorConfig.ballMass, localIntertia);
		return shape;
	} else {
		auto shape = new btSphereShape(mutatorConfig.ballRadius * UU_TO_BT);
		shape->calculateLocalInertia(mutatorConfig.ballMass, localIntertia);
		return shape;
	}
}

void Ball::_BulletSetup(GameMode gameMode, btDynamicsWorld* bulletWorld, const MutatorConfig& mutatorConfig, bool noRot) {
	btVector3 localIneria;
	_collisionShape = MakeBallCollisionShape(gameMode, mutatorConfig, localIneria);

	btRigidBody::btRigidBodyConstructionInfo constructionInfo =
		btRigidBody::btRigidBodyConstructionInfo(mutatorConfig.ballMass, NULL, _collisionShape);

	constructionInfo.m_startWorldTransform.setIdentity();
	constructionInfo.m_startWorldTransform.setOrigin(btVector3(0, 0, mutatorConfig.ballRadius * UU_TO_BT));

	constructionInfo.m_localInertia = localIneria;
	constructionInfo.m_linearDamping = mutatorConfig.ballDrag;
	constructionInfo.m_friction = mutatorConfig.ballWorldFriction;
	constructionInfo.m_restitution = mutatorConfig.ballWorldRestitution;

	_rigidBody = btRigidBody(constructionInfo);
	_rigidBody.setUserIndex(BT_USERINFO_TYPE_BALL);
	_rigidBody.setUserPointer(this);

	// Trigger the Arena::_BulletContactAddedCallback() when anything touches the ball
	_rigidBody.m_collisionFlags |= btCollisionObject::CF_CUSTOM_MATERIAL_CALLBACK;

	_rigidBody.m_rigidbodyFlags = 0;

	_rigidBody.m_noRot = noRot && (_collisionShape->getShapeType() == SPHERE_SHAPE_PROXYTYPE);

	bulletWorld->addRigidBody(
		&_rigidBody,
		btBroadphaseProxy::DefaultFilter | CollisionMasks::BASKETBALL_NET | CollisionMasks::SHATTER_TILE, btBroadphaseProxy::AllFilter
	);
}

void Ball::_FinishPhysicsTick(const MutatorConfig& mutatorConfig) {
	using namespace GameConst;

	// Add velocity cache
	if (!_velocityImpulseCache.IsZero()) {
		_rigidBody.m_linearVelocity += _velocityImpulseCache;
		_velocityImpulseCache = { 0,0,0 };
	}

	{ // Limit velocities
		btVector3
			vel = _rigidBody.m_linearVelocity,
			angVel = _rigidBody.m_angularVelocity;

		float ballMaxSpeedBT = mutatorConfig.ballMaxSpeed * UU_TO_BT;
		if (vel.length2() > ballMaxSpeedBT * ballMaxSpeedBT)
			vel = vel.normalized() * ballMaxSpeedBT;

		if (angVel.length2() > (BALL_MAX_ANG_SPEED * BALL_MAX_ANG_SPEED))
			angVel = angVel.normalized() * BALL_MAX_ANG_SPEED;

		_rigidBody.m_linearVelocity = vel;
		_rigidBody.m_angularVelocity = angVel;
	}

	_internalState.tickCountSinceUpdate++;
}

bool Ball::IsSphere() const {
	return dynamic_cast<btSphereShape*>(_collisionShape);
}

float Ball::GetRadiusBullet() const {
	if (IsSphere()) {
		return ((btSphereShape*)_collisionShape)->getRadius();
	} else {
		return 0;
	}
}

float Ball::GetMass() const {
	return _rigidBody.getMass();
}

void Ball::_PreTickUpdate(GameMode gameMode, float tickTime) {
	if (gameMode == GameMode::HOMING) {
		using namespace GameConst;

		auto state = GetState();

		float yTargetDir = _internalState.hsInfo.yTargetDir;
		if (yTargetDir != 0) {
			Angle velAngle = Angle::FromVec(state.vel);

			// Determine angle to goal
			Vec goalTargetPos = Vec(0, Homing::TARGET_Y * yTargetDir, Homing::TARGET_Z);
			Angle angleToGoal = Angle::FromVec(goalTargetPos - state.pos);

			// Find difference between target angle and current angle
			Angle deltaAngle = angleToGoal - velAngle;
			
			// Determine speed ratio
			float curSpeed = state.vel.Length();
			float speedRatio = curSpeed / Homing::MAX_SPEED;

			// Interpolate delta
			Angle newAngle = velAngle;
			float baseInterpFactor = speedRatio * tickTime;
			newAngle.yaw += deltaAngle.yaw * baseInterpFactor * Homing::HORIZONTAL_BLEND;
			newAngle.pitch += deltaAngle.pitch * baseInterpFactor * Homing::VERTICAL_BLEND;
			newAngle.NormalizeFix();

			// Limit pitch
			newAngle.pitch = AA_CLAMP(newAngle.pitch, -Homing::MAX_TURN_PITCH, Homing::MAX_TURN_PITCH);

			// Apply aggressive UE3 rotator rounding
			// (This is suprisingly important for accuracy)
			newAngle = Math::RoundAngleUE3(newAngle);
			
			// Determine new interpolated speed
			float newSpeed = curSpeed + ((state.hsInfo.curTargetSpeed - curSpeed) * Homing::SPEED_BLEND);

			// Update velocity
			Vec newDir = newAngle.GetForwardVec();

			Vec newVel = newDir * newSpeed;
			_rigidBody.m_linearVelocity = newVel * UU_TO_BT;

			_internalState.hsInfo.timeSinceHit += tickTime;
		}
	} else if (gameMode == GameMode::HOCKEY) {
		_groundStickApplied = false;
	} else if (gameMode == GameMode::SHATTER || gameMode == GameMode::BASKETBALL) {
		// Launch ball after a short delay on kickoff

		bool isShatter = (gameMode == GameMode::SHATTER);

		float launchDelay = isShatter ? GameConst::Shatter::BALL_LAUNCH_DELAY : GameConst::BALL_BASKETBALL_LAUNCH_DELAY;

		float curKickoffTime = _internalState.tickCountSinceUpdate * tickTime;
		float prevKickoffTime = curKickoffTime - tickTime;

		if (prevKickoffTime < launchDelay && curKickoffTime >= launchDelay) {

			// Launch triggered
			
			// Make sure the ball is frozen at the kickoff X and Y
			BallState state = GetState();
			if (state.vel.IsZero() && state.angVel.IsZero() && state.pos.To2D().IsZero()) {

				// Apply the force
				float launchVelZ = isShatter ? GameConst::Shatter::BALL_LAUNCH_Z_VEL : GameConst::BALL_BASKETBALL_LAUNCH_Z_VEL;
				_rigidBody.applyCentralImpulse(Vec(0, 0, launchVelZ) * GetMass() * UU_TO_BT);
				_rigidBody.setActivationState(ACTIVE_TAG);
			}
		}

	}
}

void Ball::_OnHit(
	Car* car, Vec relPos,
	float& outFriction, float& outRestitution,
	GameMode gameMode, const MutatorConfig& mutatorConfig, uint64_t tickCount
) {
	using namespace GameConst;

	auto carState = car->GetState();
	auto ballState = GetState();

	// Override friction/restitution
	outFriction = CARBALL_COLLISION_FRICTION;
	outRestitution = CARBALL_COLLISION_RESTITUTION;

	auto& ballHitInfo = car->_internalState.ballHitInfo;

	ballHitInfo.isValid = true;

	ballHitInfo.relativePosOnBall = relPos;
	ballHitInfo.tickCountWhenHit = tickCount;

	ballHitInfo.ballPos = ballState.pos;
	ballHitInfo.extraHitVel = Vec();

	// Once we do an extra car-ball impulse, we need to wait at least 1 tick to do it again
	if ((tickCount > ballHitInfo.tickCountWhenExtraImpulseApplied + 1) || (ballHitInfo.tickCountWhenExtraImpulseApplied > tickCount)) {
		// Apply extra hit impulse
		ballHitInfo.tickCountWhenExtraImpulseApplied = tickCount;
		Vec carForward = car->GetForwardDir();
		Vec relPos = ballState.pos - carState.pos;
		Vec relVel = ballState.vel - carState.vel;

		float relSpeed = AA_MIN(relVel.Length(), BALL_CAR_EXTRA_IMPULSE_MAXDELTAVEL_UU);

		if (relSpeed > 0) {
			bool extraZScale =
				gameMode == GameMode::BASKETBALL &&
				carState.isOnGround &&
				carState.rotMat.up.z > BALL_CAR_EXTRA_IMPULSE_Z_SCALE_BASKETBALL_NORMAL_Z_THRESH;
			float zScale = extraZScale ? BALL_CAR_EXTRA_IMPULSE_Z_SCALE_BASKETBALL_GROUND : BALL_CAR_EXTRA_IMPULSE_Z_SCALE;
			Vec hitDir = (relPos * Vec(1, 1, zScale)).Normalized();
			Vec forwardDirAdjustment = carForward * hitDir.Dot(carForward) * (1 - BALL_CAR_EXTRA_IMPULSE_FORWARD_SCALE);
			hitDir = (hitDir - forwardDirAdjustment).Normalized();
			Vec addedVel = (hitDir * relSpeed) * BALL_CAR_EXTRA_IMPULSE_FACTOR_CURVE.GetOutput(relSpeed) * mutatorConfig.ballHitExtraForceScale;
			ballHitInfo.extraHitVel = addedVel;

			// Velocity won't be actually added until the end of this tick
			_velocityImpulseCache += addedVel * UU_TO_BT;
		}
	} else {
		// Don't do multiple extra impulses in a row
		return;
	}

	if (gameMode == GameMode::HOMING) {
		bool canIncrease = (_internalState.hsInfo.timeSinceHit > Homing::MIN_SPEEDUP_INTERVAL) || (_internalState.hsInfo.yTargetDir == 0);
		float newTargetDir = (car->team == Team::BLUE) ? 1 : -1;
		if (canIncrease && (newTargetDir != _internalState.hsInfo.yTargetDir)) {
			_internalState.hsInfo.timeSinceHit = 0;
			_internalState.hsInfo.curTargetSpeed = AA_MIN(_internalState.hsInfo.curTargetSpeed + Homing::TARGET_SPEED_INCREMENT, Homing::MAX_SPEED);
		}
		_internalState.hsInfo.yTargetDir = newTargetDir;
	} else if (gameMode == GameMode::SHATTER) {
		auto& accumulatedHitForce = _internalState.dsInfo.accumulatedHitForce;
		auto& chargeLevel = _internalState.dsInfo.chargeLevel;

		Vec dirFromCar = (ballState.pos - carState.pos).Normalized();
		Vec relVelFromCar = carState.vel - ballState.vel;
		float velIntoBall = dirFromCar.Dot(relVelFromCar);
		if (velIntoBall >= Shatter::MIN_CHARGE_HIT_SPEED) {
			
			accumulatedHitForce += velIntoBall;

			// Normal charge
			if (accumulatedHitForce >= Shatter::MIN_ABSORBED_FORCE_FOR_CHARGE)
				chargeLevel = 2;
			
			// Supercharge
			if (accumulatedHitForce >= Shatter::MIN_ABSORBED_FORCE_FOR_SUPERCHARGE)
				chargeLevel = 3;
		}
		
		if (chargeLevel > 1) {
			float newTargetDir = (car->team == Team::BLUE) ? 1 : -1;
			_internalState.dsInfo.yTargetDir = newTargetDir;
		}
	}
}

void Ball::_OnWorldCollision(GameMode gameMode, Vec normal, float tickTime) {
	using namespace GameConst;

	if (gameMode == GameMode::HOMING) {
		if (_internalState.hsInfo.yTargetDir != 0 ) {
			Vec pos = _rigidBody.getWorldTransform().getOrigin() * BT_TO_UU;
			float relNormalY = normal.y * _internalState.hsInfo.yTargetDir;
			float relY = pos.y * _internalState.hsInfo.yTargetDir;
			if (relNormalY <= -Homing::WALL_BOUNCE_CHANGE_Y_NORMAL && 
				relY >= ARENA_EXTENT_Y - Homing::WALL_BOUNCE_CHANGE_Y_THRESH) {

				// We hit far enough to change direction
				_internalState.hsInfo.yTargetDir *= -1;

				Vec pos = _rigidBody.getWorldTransform().getOrigin() * BT_TO_UU;
				Vec vel = _rigidBody.m_linearVelocity * BT_TO_UU;

				// TODO: Make this a member function
				Vec goalTargetPos = Vec(0, Homing::TARGET_Y * _internalState.hsInfo.yTargetDir, Homing::TARGET_Z);

				// Add wall bounce impulse
				Vec dirToGoal = (goalTargetPos - pos).Normalized();

				Vec bounceDir =
					dirToGoal * (1 - Homing::WALL_BOUNCE_UP_FRAC) +
					Vec(0, 0, 1) * Homing::WALL_BOUNCE_UP_FRAC;
				Vec bounceImpulse = bounceDir * vel.Length() * Homing::WALL_BOUNCE_FORCE_SCALE;
				_velocityImpulseCache += bounceImpulse * UU_TO_BT;
			}
		}
	} else if (gameMode == GameMode::HOCKEY) {
		if (!_groundStickApplied) {
			_rigidBody.applyCentralForce(-normal * Hockey::PUCK_GROUND_STICK_FORCE);
			_groundStickApplied = true;
		}
	}
}

bool Ball::_OnShatterTileCollision(
	ShatterTilesState& tilesState, int tileTotalIndex, const btCollisionObject* tileObj,
	uint64_t tickCount, float tickTime
) {
	int teamIdx = tileTotalIndex / GameConst::Shatter::NUM_TILES_PER_TEAM;
	int tileIdx = tileTotalIndex % GameConst::Shatter::NUM_TILES_PER_TEAM;
	auto& tileState = tilesState.states[teamIdx][tileIdx];
	Vec tilePos = ShatterTiles::GetTilePos(teamIdx, tileIdx);
	auto& dsInfo = _internalState.dsInfo;

	// This should be possible in rare circumstances where two tiles are hit simultaneously
	if (tileState.damageState == ShatterTileState::STATE_BROKEN)
		return false;

	if (dsInfo.hasDamaged) {
		float timeSinceDamage = (tickCount - dsInfo.lastDamageTick) * tickTime;
		if (timeSinceDamage <= GameConst::Shatter::MIN_DAMAGE_INTERVAL)
			return false; // Hasn't been long enough since we last damaged
	}

	Vec vel = _rigidBody.getLinearVelocity() * BT_TO_UU;
	if (vel.z > -GameConst::Shatter::MIN_DOWNWARD_SPEED_TO_DAMAGE)
		return false;

	if (dsInfo.chargeLevel > 1 && dsInfo.yTargetDir != 0)
		if (AA_SGN(tilePos.y) != dsInfo.yTargetDir)
			return false; // Wrong side of the arena

	// All checks passed

	// Break the tile(s)
	{
		std::vector<int> indicesToBreak = ShatterTiles::GetNeighborIndices(tileIdx, dsInfo.chargeLevel);
		for (int i : indicesToBreak) {
			ShatterTileState& state = tilesState.states[teamIdx][i];
			if (state.damageState != ShatterTileState::STATE_BROKEN)
				state.damageState++;
		}
	}
	dsInfo.hasDamaged = true;
	dsInfo.lastDamageTick = tickCount;
	dsInfo.accumulatedHitForce = 0;
	dsInfo.chargeLevel = 1;
	dsInfo.yTargetDir = 0;
	return true;
}

AA_NS_END
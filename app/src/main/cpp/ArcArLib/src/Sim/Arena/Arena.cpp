#include "Arena.h"
#include "../../ArcArSim.h"

#include "../../../libsrc/bullet3-3.24/BulletCollision/BroadphaseCollision/btAxisSweep3.h"
#include "../../../libsrc/bullet3-3.24/BulletCollision/BroadphaseCollision/btDbvtBroadphase.h"
#include "../../../libsrc/bullet3-3.24/BulletCollision/BroadphaseCollision/btSimpleBroadphase.h"
#include "../../../libsrc/bullet3-3.24/BulletCollision/BroadphaseCollision/btArcArBroadphase.h"
#include "../../../libsrc/bullet3-3.24/BulletCollision/BroadphaseCollision/btOverlappingPairCache.h"
#include "../../../libsrc/bullet3-3.24/BulletCollision/CollisionDispatch/btDefaultCollisionConfiguration.h"
#include "../../../libsrc/bullet3-3.24/BulletCollision/CollisionDispatch/btInternalEdgeUtility.h"
#include "../../../libsrc/bullet3-3.24/BulletCollision/CollisionShapes/btBoxShape.h"
#include "../../../libsrc/bullet3-3.24/BulletCollision/CollisionShapes/btSphereShape.h"
#include "ShatterTiles/ShatterTiles.h"

AA_NS_START

void Arena::SetMutatorConfig(const MutatorConfig& mutatorConfig) {

	bool
		ballChanged = mutatorConfig.ballRadius != this->_mutatorConfig.ballRadius || mutatorConfig.ballMass != this->_mutatorConfig.ballMass,
		carMassChanged = mutatorConfig.carMass != this->_mutatorConfig.carMass,
		gravityChanged = mutatorConfig.gravity != this->_mutatorConfig.gravity;

	this->_mutatorConfig = mutatorConfig;

	_bulletWorld.setGravity(mutatorConfig.gravity * UU_TO_BT);
	
	if (ballChanged) {
		// We'll need to remake the ball
		_bulletWorld.removeCollisionObject(&ball->_rigidBody);
		delete ball->_collisionShape;
		ball->_BulletSetup(gameMode, &_bulletWorld, mutatorConfig, _config.noBallRot);
	}

	if (carMassChanged) {
		for (Car* car : _cars) {
			btVector3 newCarInertia;
			car->_childHitboxShape.calculateLocalInertia(mutatorConfig.carMass, newCarInertia);
			car->_rigidBody.setMassProps(mutatorConfig.ballMass, newCarInertia);
		}
	}

	// Update ball rigidbody physics values for world contact
	// NOTE: Cars don't use their rigidbody physics values for world contact
	ball->_rigidBody.setFriction(mutatorConfig.ballWorldFriction);
	ball->_rigidBody.setRestitution(mutatorConfig.ballWorldRestitution);
	ball->_rigidBody.setDamping(mutatorConfig.ballDrag, 0);
}

Car* Arena::AddCar(Team team, const CarConfig& config) {
	Car* car = Car::_AllocateCar();
	
	car->config = config;
	car->team = team;
	
	_AddCarFromPtr(car);

	car->_BulletSetup(gameMode, &_bulletWorld, _mutatorConfig);
	car->Respawn(gameMode, -1, _mutatorConfig.carSpawnBoostAmount);

	return car;
}

bool Arena::_AddCarFromPtr(Car* car) {

	car->id = ++_lastCarID;

	if (_carIDMap.find(car->id) == _carIDMap.end()) {
		assert(!_cars.count(car));
		
		_carIDMap[car->id] = car;
		_cars.insert(car);
		return true;

	} else {
		return false;
	}
}

bool Arena::RemoveCar(uint32_t id) {
	auto itr = _carIDMap.find(id);

	if (itr != _carIDMap.end()) {
		Car* car = itr->second;
		_carIDMap.erase(itr);
		_cars.erase(car);
		_bulletWorld.removeCollisionObject(&car->_rigidBody);
		if (ownsCars)
			delete car;
		return true;
	} else {
		return false;
	}
}

Car* Arena::GetCar(uint32_t id) {
	return _carIDMap[id];
}

void Arena::SetGoalScoreCallback(GoalScoreEventFn callbackFunc, void* userInfo) {
	if (gameMode == GameMode::THE_VOID)
		AA_ERR_CLOSE("Cannot set a goal score callback when on THE_VOID gamemode");

	_goalScoreCallback.func = callbackFunc;
	_goalScoreCallback.userInfo = userInfo;
}

void Arena::SetCarBumpCallback(CarBumpEventFn callbackFunc, void* userInfo) {
	_carBumpCallback.func = callbackFunc;
	_carBumpCallback.userInfo = userInfo;
}

void Arena::ResetToRandomKickoff(int seed) {
	using namespace GameConst;
	// TODO: Make shuffling of kickoff setup more efficient (?)

	static thread_local std::array<int, CAR_SPAWN_LOCATION_AMOUNT> KICKOFF_ORDER_TEMPLATE = { -1 };
	if (KICKOFF_ORDER_TEMPLATE[0] == -1) {
		// Initialize
		for (int i = 0; i < CAR_SPAWN_LOCATION_AMOUNT; i++)
			KICKOFF_ORDER_TEMPLATE[i] = i;
	}

	auto kickoffOrder = KICKOFF_ORDER_TEMPLATE;

	std::default_random_engine* randEngine;
	if (seed == -1) {
		randEngine = &Math::GetRandEngine();
	} else {
		randEngine = new std::default_random_engine(seed);
	}

	int locationAmount = (gameMode == GameMode::HOMING) ? CAR_SPAWN_LOCATION_AMOUNT_HOMING : CAR_SPAWN_LOCATION_AMOUNT;

	std::shuffle(kickoffOrder.begin(), kickoffOrder.begin() + locationAmount, *randEngine);

	const CarSpawnPos* CAR_SPAWN_LOCATIONS = CAR_SPAWN_LOCATIONS_STANDARD;
	const CarSpawnPos* CAR_RESPAWN_LOCATIONS = CAR_RESPAWN_LOCATIONS_STANDARD;
	if (gameMode == GameMode::BASKETBALL) {
		CAR_SPAWN_LOCATIONS = CAR_SPAWN_LOCATIONS_BASKETBALL;
		CAR_RESPAWN_LOCATIONS = CAR_RESPAWN_LOCATIONS_BASKETBALL;
	} else if (gameMode == GameMode::HOMING) {
		CAR_SPAWN_LOCATIONS = CAR_SPAWN_LOCATIONS_HOMING;
		CAR_RESPAWN_LOCATIONS = CAR_RESPAWN_LOCATIONS_STANDARD;
	} else if (gameMode == GameMode::SHATTER) {
		CAR_SPAWN_LOCATIONS = CAR_SPAWN_LOCATIONS_SHATTER;
		CAR_RESPAWN_LOCATIONS = CAR_RESPAWN_LOCATIONS_SHATTER;
	}

	std::vector<Car*> blueCars, orangeCars;
	for (Car* car : _cars)
		((car->team == Team::BLUE) ? blueCars : orangeCars).push_back(car);

	int numCarsAtRespawnPos[CAR_RESPAWN_LOCATION_AMOUNT] = {};

	int kickoffPositionAmount = AA_MAX(blueCars.size(), orangeCars.size());
	for (int i = 0; i < kickoffPositionAmount; i++) {

		CarSpawnPos spawnPos;
	
		if (i < locationAmount) {
			spawnPos = CAR_SPAWN_LOCATIONS[AA_MIN(kickoffOrder[i], locationAmount - 1)];
		} else {
			int respawnPosIdx = (i - (locationAmount)) % locationAmount;
			spawnPos = CAR_RESPAWN_LOCATIONS[respawnPosIdx];

			// Extra offset to add to multiple cars spawning at the same respawn point,
			//	helps prevent insane numbers of cars from spawning in eachother.
			// Eventually, they will spawn so far away that they clip out of the arena,
			//	but that's not my problem.
			constexpr float CAR_SPAWN_EXTRA_OFFSET_Y = 250;
			spawnPos.y += CAR_SPAWN_EXTRA_OFFSET_Y * numCarsAtRespawnPos[respawnPosIdx];
			numCarsAtRespawnPos[respawnPosIdx]++;
		}

		for (int teamIndex = 0; teamIndex < 2; teamIndex++) {
			bool isBlue = (teamIndex == 0);
			std::vector<Car*> teamCars = isBlue ? blueCars : orangeCars;

			if (i < teamCars.size()) {
				CarState spawnState;
				spawnState.boost = _mutatorConfig.carSpawnBoostAmount;
				spawnState.pos = { spawnPos.x, spawnPos.y, CAR_SPAWN_REST_Z };
				Angle angle = Angle(spawnPos.yawAng, 0, 0);
				spawnState.isOnGround = true;

				if (!isBlue) {
					spawnState.pos *= { -1, -1, 1 };
					angle.yaw += M_PI;
				}

				spawnState.rotMat = angle.ToRotMat();

				teamCars[i]->SetState(spawnState);
			}
		}
	}

	BallState ballState = BallState();
	if (gameMode == GameMode::HOMING) {
		int nextRand = (*randEngine)();
		Vec scale = Vec(1, (nextRand % 2) ? 1 : -1, 1);
		ballState.pos = Homing::BALL_START_POS * scale;
		ballState.vel = Homing::BALL_START_VEL * scale;
	} else if (gameMode == GameMode::HOCKEY) {
		// Don't freeze
		ballState.vel.z = FLT_EPSILON;
	}
	ball->SetState(ballState);

	// Reset boost pads
	for (BoostPad* boostPad : _boostPads)
		boostPad->SetState(BoostPadState());

	// Reset tile states
	if (gameMode == GameMode::SHATTER)
		SetShatterTilesState({});

	if (seed != -1) {
		// Custom random engine was created for this seed, so we need to free it
		delete randEngine;
	}
}

void Arena::SetShatterTilesState(const ShatterTilesState& state) {
	for (int teamIdx = 0; teamIdx <= 1; teamIdx++) {
		for (int tileIdx = 0; tileIdx < GameConst::Shatter::NUM_TILES_PER_TEAM; tileIdx++) {
			auto& newState = state.states[teamIdx][tileIdx];

			int rbIndex = tileIdx + (GameConst::Shatter::NUM_TILES_PER_TEAM * teamIdx);
			assert(rbIndex < _worldShatterTileRBs.size());
			auto shatterTileRB = _worldShatterTileRBs[rbIndex];
			if (newState.damageState == ShatterTileState::STATE_BROKEN) {
				shatterTileRB->m_collisionFlags |= btCollisionObject::CF_NO_CONTACT_RESPONSE;
			} else {
				shatterTileRB->m_collisionFlags &= ~btCollisionObject::CF_NO_CONTACT_RESPONSE;
			}
		}
	}

	_shatterTilesState = state;
}

bool Arena::_BulletContactAddedCallback(
	btManifoldPoint& contactPoint,
	const btCollisionObjectWrapper* objA, int partID_A, int indexA,
	const btCollisionObjectWrapper* objB, int partID_B, int indexB) {

	auto
		bodyA = objA->m_collisionObject,
		bodyB = objB->m_collisionObject;

	if (!objA->m_collisionObject->hasContactResponse() || !objB->m_collisionObject->hasContactResponse())
		return true;

	bool shouldSwap = false;
	if ((bodyA->getUserIndex() != -1) && (bodyB->getUserIndex() != -1)) {
		// If both bodies have a user index, the lower user index should be A
		shouldSwap = bodyA->getUserIndex() > bodyB->getUserIndex();
	} else {
		// If only one body has a user index, make sure that body is A
		shouldSwap = (bodyB->getUserIndex() != -1);
	}

	if (shouldSwap)
		std::swap(bodyA, bodyB);

	int
		userIndexA = bodyA->getUserIndex(),
		userIndexB = bodyB->getUserIndex();

	bool carInvolved = (userIndexA == BT_USERINFO_TYPE_CAR);
	if (carInvolved) {

		Car* car = (Car*)bodyA->getUserPointer();
		Arena* arenaInst = (Arena*)car->_bulletVehicle.m_dynamicsWorld->getWorldUserInfo();

		if (userIndexB == BT_USERINFO_TYPE_BALL) {
			// Car + Ball
			arenaInst->
				_BtCallback_OnCarBallCollision(car, (Ball*)bodyB->getUserPointer(), contactPoint, shouldSwap);
		} else if (userIndexB == BT_USERINFO_TYPE_CAR) {
			// Car + Car
			arenaInst->
				_BtCallback_OnCarCarCollision(car, (Car*)bodyB->getUserPointer(), contactPoint);
		} else {
			// Car + World
			arenaInst->
				_BtCallback_OnCarWorldCollision(car, (btCollisionObject*)bodyB->getUserPointer(), contactPoint);
		}
	} else if (userIndexA == BT_USERINFO_TYPE_BALL && userIndexB == BT_USERINFO_TYPE_SHATTER_TILE) {

		Arena* arenaInst = (Arena*)bodyB->getUserPointer();
		arenaInst->ball->_OnShatterTileCollision(
			arenaInst->_shatterTilesState, bodyB->getUserIndex2(), bodyB, arenaInst->tickCount, arenaInst->tickTime
		);

	} else if (userIndexA == BT_USERINFO_TYPE_BALL && userIndexB == -1) {
		// Ball + World
		Arena* arenaInst = (Arena*)bodyB->getUserPointer();
		arenaInst->ball->_OnWorldCollision(arenaInst->gameMode, contactPoint.m_normalWorldOnB, arenaInst->tickTime);
		
		// Set as special (unless in hockey)
		if (arenaInst->gameMode != GameMode::HOCKEY)
			contactPoint.m_isSpecial = true;
	}
	
	btAdjustInternalEdgeContacts(
		contactPoint, 
		(shouldSwap ? objA : objB), (shouldSwap ? objB : objA),
		(shouldSwap ? partID_A : partID_B), (shouldSwap ? indexA : indexB)
	);
	return true;
}

void Arena::_BtCallback_OnCarBallCollision(Car* car, Ball* ball, btManifoldPoint& manifoldPoint, bool ballIsBodyA) {
	using namespace GameConst;

	Vec relBallPos = (ballIsBodyA ? manifoldPoint.m_localPointA : manifoldPoint.m_localPointB) * BT_TO_UU;
	ball->_OnHit(car, relBallPos, manifoldPoint.m_combinedFriction, manifoldPoint.m_combinedRestitution, gameMode, _mutatorConfig, tickCount);
}

void Arena::_BtCallback_OnCarCarCollision(Car* car1, Car* car2, btManifoldPoint& manifoldPoint) {
	using namespace GameConst;

	// Manually override manifold friction/restitution
	manifoldPoint.m_combinedFriction = GameConst::CARCAR_COLLISION_FRICTION;
	manifoldPoint.m_combinedRestitution = GameConst::CARCAR_COLLISION_RESTITUTION;

	// Test collision both ways
	for (int i = 0; i < 2; i++) {

		bool isSwapped = (i == 1);
		if (isSwapped)
			std::swap(car1, car2);

		CarState
			state = car1->GetState(),
			otherState = car2->GetState();

		if (state.isDemoed || otherState.isDemoed)
			return;

		if ((state.carContact.otherCarID == car2->id) && (state.carContact.cooldownTimer > 0))
			continue; // In cooldown

		Vec deltaPos = (otherState.pos - state.pos);
		if (state.vel.Dot(deltaPos) > 0) { // Going towards other car

			Vec velDir = state.vel.Normalized();
			Vec dirToOtherCar = deltaPos.Normalized();

			float speedTowardsOtherCar = state.vel.Dot(dirToOtherCar);
			float otherCarAwaySpeed = otherState.vel.Dot(velDir);

			if (speedTowardsOtherCar > otherCarAwaySpeed) { // Going towards other car faster than they are going away

				Vec localPoint = isSwapped ? manifoldPoint.m_localPointB : manifoldPoint.m_localPointA;
				bool hitWithBumper = (localPoint.x * BT_TO_UU) > BUMP_MIN_FORWARD_DIST;
				if (hitWithBumper) {

					bool isDemo;
					switch (_mutatorConfig.demoMode) {
					case DemoMode::ON_CONTACT:
						isDemo = true; // BOOM
						break;
					case DemoMode::DISABLED:
						isDemo = false;
						break;
					default:
						isDemo = state.isSupersonic;
					}

					if (isDemo && !_mutatorConfig.enableTeamDemos)
						isDemo = car1->team != car2->team;

					if (isDemo) {
						car2->Demolish(_mutatorConfig.respawnDelay);
					} else {
						bool groundHit = car2->_internalState.isOnGround;

						float baseScale =
							(groundHit ? BUMP_VEL_AMOUNT_GROUND_CURVE : BUMP_VEL_AMOUNT_AIR_CURVE).GetOutput(speedTowardsOtherCar);

						Vec hitUpDir =
							(otherState.isOnGround ? (Vec)car2->GetUpDir() : Vec(0, 0, 1));

						Vec bumpImpulse =
							velDir * baseScale +
							hitUpDir * BUMP_UPWARD_VEL_AMOUNT_CURVE.GetOutput(speedTowardsOtherCar)
							* _mutatorConfig.bumpForceScale;

						car2->_velocityImpulseCache += bumpImpulse * UU_TO_BT;
					}

					car1->_internalState.carContact.otherCarID = car2->id;
					car1->_internalState.carContact.cooldownTimer = _mutatorConfig.bumpCooldownTime;

					if (_carBumpCallback.func)
						_carBumpCallback.func(this, car1, car2, isDemo, _carBumpCallback.userInfo);
				}
			}
		}
	}
}

void Arena::_BtCallback_OnCarWorldCollision(Car* car, btCollisionObject* world, btManifoldPoint& manifoldPoint) {
	car->_internalState.worldContact.hasContact = true;
	car->_internalState.worldContact.contactNormal = manifoldPoint.m_normalWorldOnB;

	// Manually override manifold friction/restitution
	manifoldPoint.m_combinedFriction = _mutatorConfig.carWorldFriction;
	manifoldPoint.m_combinedRestitution = _mutatorConfig.carWorldRestitution;
}

Arena::Arena(GameMode gameMode, const ArenaConfig& config, float tickRate) : _mutatorConfig(gameMode), _config(config) {

	// Tickrate must be from 15 to 120tps
	assert(tickRate >= 15 && tickRate <= 120);

	ArcAr::AssertInitialized("Cannot create Arena, ");

	this->gameMode = gameMode;
	this->tickTime = 1 / tickRate;

	{ // Initialize world

		btDefaultCollisionConstructionInfo collisionConfigConstructionInfo = {};

		// These take up a ton of memory normally
		if (_config.memWeightMode == ArenaMemWeightMode::LIGHT) {
			collisionConfigConstructionInfo.m_defaultMaxPersistentManifoldPoolSize /= 32;
			collisionConfigConstructionInfo.m_defaultMaxCollisionAlgorithmPoolSize /= 64;
		} else {
			collisionConfigConstructionInfo.m_defaultMaxPersistentManifoldPoolSize /= 16;
			collisionConfigConstructionInfo.m_defaultMaxCollisionAlgorithmPoolSize /= 32;
		}

		_bulletWorldParams.collisionConfig.setup(collisionConfigConstructionInfo);

		_bulletWorldParams.collisionDispatcher.setup(&_bulletWorldParams.collisionConfig);
		_bulletWorldParams.constraintSolver = btSequentialImpulseConstraintSolver();

		_bulletWorldParams.overlappingPairCache = new btHashedOverlappingPairCache();
		
		if (_config.useCustomBroadphase) {
			float cellSizeMultiplier = 1;
			if (_config.memWeightMode == ArenaMemWeightMode::LIGHT) {
				// Increase cell size
				cellSizeMultiplier = 2.0f;
			}

			_bulletWorldParams.broadphase = new btArcArBroadphase(
				_config.minPos * UU_TO_BT,
				_config.maxPos * UU_TO_BT,
				_config.maxAABBLen * UU_TO_BT * cellSizeMultiplier,
				_bulletWorldParams.overlappingPairCache,
				_config.maxObjects);
		} else {
			_bulletWorldParams.broadphase = new btDbvtBroadphase(_bulletWorldParams.overlappingPairCache);
		}

		_bulletWorld.setup(
			&_bulletWorldParams.collisionDispatcher,
			_bulletWorldParams.broadphase,
			&_bulletWorldParams.constraintSolver,
			&_bulletWorldParams.collisionConfig
		);

		_bulletWorld.setGravity(_mutatorConfig.gravity * UU_TO_BT);

		// Adjust solver configuration to be closer to an older Bullet version (the reference implementation dates from roughly 2013-2015)
		auto& solverInfo = _bulletWorld.getSolverInfo();
		solverInfo.m_splitImpulsePenetrationThreshold = 1.0e30f;
		solverInfo.m_erp2 = 0.8f;
	}

	bool loadArenaStuff = gameMode != GameMode::THE_VOID;

	if (loadArenaStuff) {
		_SetupArenaCollisionShapes();

		// Give arena collision shapes the proper restitution/friction values
		for (auto* rb : _worldCollisionRBs) {
			rb->setRestitution(GameConst::ARENA_COLLISION_BASE_RESTITUTION);
			rb->setFriction(GameConst::ARENA_COLLISION_BASE_FRICTION);
			rb->setRollingFriction(0.f);
		}
	}

	{ // Initialize ball
		ball = Ball::_AllocBall();

		ball->_BulletSetup(gameMode, &_bulletWorld, _mutatorConfig, _config.noBallRot);
		ball->SetState(BallState());
	}

	if (loadArenaStuff && gameMode != GameMode::SHATTER) { // Initialize boost pads
		using namespace GameConst::BoostPads;

		if (_config.useCustomBoostPads) {
			for (auto& padConfig : _config.customBoostPads) {
				BoostPad* pad = BoostPad::_AllocBoostPad();
				pad->_Setup(padConfig);

				_boostPads.push_back(pad);
			}
		} else {
			bool isBasketball = gameMode == GameMode::BASKETBALL;

			int amountSmall = isBasketball ? LOCS_AMOUNT_SMALL_BASKETBALL : LOCS_AMOUNT_SMALL_STANDARD;
			_boostPads.reserve(LOCS_AMOUNT_BIG + amountSmall);

			for (int i = 0; i < (LOCS_AMOUNT_BIG + amountSmall); i++) {

				BoostPadConfig padConfig;

				padConfig.isBig = i < LOCS_AMOUNT_BIG;

				btVector3 pos;
				if (isBasketball) {
					padConfig.pos = padConfig.isBig ? LOCS_BIG_BASKETBALL[i] : LOCS_SMALL_BASKETBALL[i - LOCS_AMOUNT_BIG];
				} else {
					padConfig.pos = padConfig.isBig ? LOCS_BIG_STANDARD[i] : LOCS_SMALL_STANDARD[i - LOCS_AMOUNT_BIG];
				}

				BoostPad* pad = BoostPad::_AllocBoostPad();
				pad->_Setup(padConfig);

				_boostPads.push_back(pad);
				_boostPadGrid.Add(pad);
			}
		}
	}

	// Set internal tick callback
	_bulletWorld.setWorldUserInfo(this);

	gContactAddedCallback = &Arena::_BulletContactAddedCallback;
}

Arena* Arena::Create(GameMode gameMode, const ArenaConfig& arenaConfig, float tickRate) {
	return new Arena(gameMode, arenaConfig, tickRate);
}

void Arena::Serialize(DataStreamOut& out) const {
	out.WriteMultiple(gameMode, tickTime, tickCount, _lastCarID);

	_config.Serialize(out);

	{ // Serialize cars
		out.Write<uint32_t>(_cars.size());
		for (auto car : _cars) {
			out.Write(car->team);
			out.Write(car->id);
			car->Serialize(out);
		}
	}

	if (_boostPads.size() > 0) { // Serialize boost pads
		out.Write<uint32_t>(_boostPads.size());
		for (auto pad : _boostPads)
			pad->GetState().Serialize(out);
	}

	{ // Serialize ball
		ball->GetState().Serialize(out);
	}

	{ // Serialize mutators
		_mutatorConfig.Serialize(out);
	}
}

Arena* Arena::DeserializeNew(DataStreamIn& in) {
	constexpr char ERROR_PREFIX[] = "Arena::Deserialize(): ";

	GameMode gameMode;
	float tickTime;
	uint64_t tickCount;
	uint32_t lastCarID;

	in.ReadMultiple(gameMode, tickTime, tickCount, lastCarID);

	ArenaConfig newConfig = {};
	newConfig.Deserialize(in);

	Arena* newArena = new Arena(gameMode, newConfig, 1.f / tickTime);
	newArena->tickCount = tickCount;
	
	{ // Deserialize cars
		uint32_t carAmount = in.Read<uint32_t>();
		for (uint32_t i = 0; i < carAmount; i++) {
			Team team;
			uint32_t id;
			in.Read(team);
			in.Read(id);

#ifndef AA_MAX_SPEED
			if (newArena->_carIDMap.count(id))
				AA_ERR_CLOSE(ERROR_PREFIX << "Failed to load, got repeated car ID of " << id);
#endif

			Car* newCar = newArena->DeserializeNewCar(in, team);

			// Force ID
			newArena->_carIDMap.erase(newCar->id);
			newArena->_carIDMap[id] = newCar;
			newCar->id = id;
		}

		newArena->_lastCarID = lastCarID;
	}

	// Deserialize boost pads
	if (newArena->_boostPads.size() > 0) {
		uint32_t boostPadAmount = in.Read<uint32_t>();

#ifndef AA_MAX_SPEED
		if (boostPadAmount != newArena->_boostPads.size())
			AA_ERR_CLOSE(ERROR_PREFIX << "Failed to load, " <<
				"different boost pad amount written in file (" << boostPadAmount << "/" << newArena->_boostPads.size() << ")");
#endif

		for (auto pad : newArena->_boostPads) {
			BoostPadState padState = BoostPadState();
			padState.Deserialize(in);
			pad->SetState(padState);
		}
	}

	{ // Deserialize ball
		BallState ballState = BallState();
		ballState.Deserialize(in);
		newArena->ball->SetState(ballState);
	}

	{ // Serialize mutators
		newArena->_mutatorConfig.Deserialize(in);
		newArena->SetMutatorConfig(newArena->_mutatorConfig);
	}

	return newArena;
}

Arena* Arena::Clone(bool copyCallbacks) {
	Arena* newArena = new Arena(this->gameMode, this->_config, this->GetTickRate());
	
	if (copyCallbacks) {
		newArena->_goalScoreCallback = this->_goalScoreCallback;
		newArena->_carBumpCallback = this->_carBumpCallback;
	}

	newArena->ball->SetState(this->ball->GetState());
	newArena->ball->_velocityImpulseCache = this->ball->_velocityImpulseCache;

	for (Car* car : this->_cars) {
		Car* newCar = newArena->AddCar(car->team, car->config);
		
		newCar->SetState(car->GetState());
		newCar->id = car->id;
		newCar->controls = car->controls;
		newCar->_velocityImpulseCache = car->_velocityImpulseCache;
	}

	assert(this->_boostPads.size() == newArena->_boostPads.size());
	for (int i = 0; i < this->_boostPads.size(); i++)
		newArena->_boostPads[i]->SetState(this->_boostPads[i]->GetState());

	newArena->tickCount = this->tickCount;
	newArena->_lastCarID = this->_lastCarID;

	return newArena;
}

Car* Arena::DeserializeNewCar(DataStreamIn& in, Team team) {
	Car* car = Car::_AllocateCar();
	car->_Deserialize(in);
	car->team = team;

	_AddCarFromPtr(car);

	car->_BulletSetup(gameMode, &_bulletWorld, _mutatorConfig);
	car->SetState(car->_internalState);

	return car;
}

void Arena::Step(int ticksToSimulate) {
	for (int i = 0; i < ticksToSimulate; i++) {

		_bulletWorld.setWorldUserInfo(this);

		{ // Ball zero-vel sleeping
			if (ball->_rigidBody.m_linearVelocity.length2() == 0 && ball->_rigidBody.m_angularVelocity.length2() == 0) {
				ball->_rigidBody.setActivationState(ISLAND_SLEEPING);
			} else {
				ball->_rigidBody.setActivationState(ACTIVE_TAG);
			}
		}

		bool ballOnly = _cars.empty();

		bool hasArenaStuff = (gameMode != GameMode::THE_VOID);
		
		for (Car* car : _cars)
			car->_PreTickUpdate(gameMode, tickTime, _mutatorConfig);

		if (hasArenaStuff && !ballOnly) {
			for (BoostPad* pad : _boostPads)
				pad->_PreTickUpdate(tickTime);
		}

		// Update ball
		ball->_PreTickUpdate(gameMode, tickTime);

		// Update world
		_bulletWorld.stepSimulation(tickTime, 0, tickTime);

		for (Car* car : _cars) {
			car->_PostTickUpdate(gameMode, tickTime, _mutatorConfig);
			car->_FinishPhysicsTick(_mutatorConfig);
			if (hasArenaStuff) {
				if (_config.useCustomBoostPads) {
					// TODO: This is quite slow, we should use a sorting method of some sort
					for (auto& boostPad : _boostPads) {
						boostPad->_CheckCollide(car);
					}
				} else {
					_boostPadGrid.CheckCollision(car);
				}
			}
		}

		if (hasArenaStuff && !ballOnly)
			for (BoostPad* pad : _boostPads)
				pad->_PostTickUpdate(tickTime, _mutatorConfig);

		ball->_FinishPhysicsTick(_mutatorConfig);

		// Sync tiles state after the tick ends.
		// We don't want to sync the state on tile damage, 
		//	because that would cause the ball to immediately fall through the newly-broken tile.
		if (gameMode == GameMode::SHATTER)
			if (ball->_internalState.dsInfo.lastDamageTick && ball->_internalState.dsInfo.lastDamageTick == tickCount)
				SetShatterTilesState(_shatterTilesState);

		if (_goalScoreCallback.func != NULL) { // Potentially fire goal score callback
			if (IsBallScored()) {
				_goalScoreCallback.func(this, AA_TEAM_FROM_Y(-ball->_rigidBody.getWorldTransform().m_origin.y()), _goalScoreCallback.userInfo);
			}
		}

		tickCount++;
	}
}

// Returns negative: within
// Note that the returned margin is squared
float BallWithinBasketballGoalXYMarginSq(float x, float y) {
	constexpr float
		SCALE_Y = 0.9f,
		OFFSET_Y = 2770.f,
		RADIUS_SQ = 716 * 716;

	float dy = abs(y) * SCALE_Y - OFFSET_Y;
	float distSq = x * x + dy * dy;
	return distSq - RADIUS_SQ;
}

bool Arena::IsBallProbablyGoingIn(float maxTime, float extraMargin, Team* goalTeamOut) const {
	Vec ballPos = ball->_rigidBody.getWorldTransform().m_origin * BT_TO_UU;
	Vec ballVel = ball->_rigidBody.m_linearVelocity * BT_TO_UU;

	if (gameMode == GameMode::STANDARD || gameMode == GameMode::HOCKEY) {
		if (abs(ballVel.y) < FLT_EPSILON)
			return false;

		float scoreDirSgn = AA_SGN(ballVel.y);
		float goalY = _mutatorConfig.goalBaseThresholdY * scoreDirSgn;
		float distToGoal = abs(ballPos.y - goalY);

		float timeToGoal = distToGoal / abs(ballVel.y);
		
		if (timeToGoal > maxTime)
			return false;

		Vec extrapPosWhenScore = ballPos + (ballVel * timeToGoal) + (_mutatorConfig.gravity * timeToGoal * timeToGoal) / 2;

		// Derived from community-sourced reverse-engineered game values
		constexpr float
			APPROX_GOAL_HALF_WIDTH = 892.755f,
			APPROX_GOAL_HEIGHT = 642.775;

		float scoreMargin = _mutatorConfig.ballRadius * 0.1f + extraMargin;

		if (extrapPosWhenScore.z > APPROX_GOAL_HEIGHT + scoreMargin)
			return false; // Too high

		if (abs(extrapPosWhenScore.x) > APPROX_GOAL_HALF_WIDTH + scoreMargin)
			return false; // Too far to the side

		if (goalTeamOut)
			*goalTeamOut = AA_TEAM_FROM_Y(scoreDirSgn);

		// Ok it's probably gonna score, or at least be very close
		return true;
	} else if (gameMode == GameMode::BASKETBALL) {

		constexpr float
			APPROX_RIM_HEIGHT = 365;
		
		float minHeight = APPROX_RIM_HEIGHT + _mutatorConfig.ballRadius * 1.2f;

		if (ballVel.z < -FLT_EPSILON && ballPos.z < minHeight) {
			if (BallWithinBasketballGoalXYMarginSq(ballPos.x, ballPos.y) < 0) {
				if (goalTeamOut)
					*goalTeamOut = AA_TEAM_FROM_Y(ballPos.y);
				return true; // Already in the net
			}
		}

		float margin = _mutatorConfig.ballRadius * 1.0f;
		float marginSq = margin * margin;

		float upQuadIntercept;
		float downQuadIntercept;

		// Calculate time to score using quadratic intercept
		{
			float g = _mutatorConfig.gravity.z;
			if (g > -FLT_EPSILON)
				return false; 

			float v = ballVel.z;
			float h = ballPos.z - minHeight;

			float sqrtInput = v * v - 2 * g * h;
			if (sqrtInput > 0) {
				float sqrtOutput = sqrtf(sqrtInput);
				upQuadIntercept = (-v + sqrtOutput) / g;
				downQuadIntercept = (-v - sqrtOutput) / g;
			} else {
				// Never reaches the rim height
				if (BallWithinBasketballGoalXYMarginSq(ballPos.x, ballPos.y) < -marginSq) {
					// If started within the hoop, it will stay within the hoop and is therefore scoring
					return true;
				} else {
					// Otherwise, it can never get into the hoop and is therefore never scoring
					return false;
				}
			}
		}
		
		if (upQuadIntercept >= 0) {
			// Ball has to go up before it can fall into the hoop
			// Make sure it cant hit the rim on the way up

			Vec extrapPosUp = ballPos + (ballVel * upQuadIntercept);
			float upMarginSq = BallWithinBasketballGoalXYMarginSq(extrapPosUp.x, extrapPosUp.y);

			float minClearanceMargin = 60 + _mutatorConfig.ballRadius;

			if (upMarginSq > -marginSq && upMarginSq < (minClearanceMargin * minClearanceMargin))
				return false; // Will probably hit rim
		}

		Vec extrapPosDown = ballPos + (ballVel * downQuadIntercept);
		extrapPosDown.y = abs(extrapPosDown.y);

		{ // Very approximate prediction of backboard bounce
			float wallBounceY = GameConst::ARENA_EXTENT_Y_BASKETBALL - _mutatorConfig.ballRadius;
			if (extrapPosDown.y > wallBounceY) {
				float margin = extrapPosDown.y - wallBounceY;
				extrapPosDown.y -= margin * (1 + _mutatorConfig.ballWorldRestitution);
			}
		}
		
		if (BallWithinBasketballGoalXYMarginSq(extrapPosDown.x, extrapPosDown.y) < -marginSq) {
			if (goalTeamOut)
				*goalTeamOut = AA_TEAM_FROM_Y(extrapPosDown.y);
			return true;
		} else {
			return false;
		}

	} else {
		AA_ERR_CLOSE("Arena::IsBallProbablyGoingIn() is not supported for gamemode " << GAMEMODE_STRS[(int)gameMode]);
		return false;
	}
}

bool Arena::IsBallScored() const {
	switch (gameMode) {
	case GameMode::STANDARD:
	case GameMode::HOMING:
	case GameMode::HOCKEY:
	{
		float ballPosY = ball->_rigidBody.getWorldTransform().m_origin.y() * BT_TO_UU;
		return abs(ballPosY) > (_mutatorConfig.goalBaseThresholdY + _mutatorConfig.ballRadius);
	}
	case GameMode::BASKETBALL:
	{
		if (ball->_rigidBody.getWorldTransform().m_origin.z() < GameConst::BASKETBALL_GOAL_SCORE_THRESHOLD_Z * UU_TO_BT) {
			constexpr float
				SCALE_Y = 0.9f,
				OFFSET_Y = 2770.f,
				RADIUS_SQ = 716 * 716;

			Vec ballPos = ball->_rigidBody.getWorldTransform().m_origin * BT_TO_UU;
			return BallWithinBasketballGoalXYMarginSq(ballPos.x, ballPos.y) < 0;
		} else {
			return false;
		}
	}
	case GameMode::SHATTER:
	{
		if ((ball->_rigidBody.getWorldTransform().m_origin.z() * BT_TO_UU) < -(_mutatorConfig.ballRadius * 1.75f)) {
			return true;
		} else {
			return false;
		}
	}
	default:
		return false;
	}
}

Arena::~Arena() {

	// Remove all from bullet world constraints
	while (_bulletWorld.getNumConstraints() > 0)
		_bulletWorld.removeConstraint(0);

	// Manually remove all collision objects
	// Otherwise we run into issues regarding deconstruction order
	while (_bulletWorld.getNumCollisionObjects() > 0)
		_bulletWorld.removeCollisionObject(_bulletWorld.getCollisionObjectArray()[0]);

	// Remove all cars
	if (ownsCars) {
		for (Car* car : _cars)
			delete car;
	}

	// Remove the ball
	if (ownsBall) {
		Ball::_DestroyBall(ball);
	}

	if (_boostPads.size() > 0) {
		if (ownsBoostPads) {
			// Remove all boost pads
			for (BoostPad* boostPad : _boostPads)
				delete boostPad;
		}
	}

	// Remove all rigidbodies and collision shapes that we own
	for (auto rb : _worldCollisionRBs) {
		auto shape = rb->getCollisionShape();
		
		bool isBvh = dynamic_cast<btBvhTriangleMeshShape*>(shape);
		if (isBvh) {
			// Don't free BVH shapes because we don't own them
		} else {
			delete shape;
		}

		delete rb;
	}

	delete _bulletWorldParams.overlappingPairCache;
	delete _bulletWorldParams.broadphase;
}

btRigidBody* Arena::_AddStaticCollisionShape(btCollisionShape* shape, btVector3 posBT, int group, int mask) {
	btRigidBody* shapeRB = new btRigidBody(0, NULL, shape);
	shapeRB->setWorldTransform(btTransform(btMatrix3x3::getIdentity(), posBT));
	shapeRB->setUserPointer(this);
	if (group || mask) {
		_bulletWorld.addRigidBody(shapeRB, group, mask);
	} else {
		_bulletWorld.addRigidBody(shapeRB);
	}
	_worldCollisionRBs.push_back(shapeRB);
	return shapeRB;
}

void Arena::_SetupArenaCollisionShapes() {
	assert(gameMode != GameMode::THE_VOID);
	bool isBasketball = gameMode == GameMode::BASKETBALL;
	bool isShatter = gameMode == GameMode::SHATTER;

	auto collisionMeshes = ArcAr::GetArenaCollisionShapes(gameMode);

	// No mesh files: continue with floor/wall planes only (playable without assets)
	if (collisionMeshes.empty()) {
		AA_WARN(
			"No arena meshes for " << GAMEMODE_STRS[(int)gameMode]
			<< " — using collision planes only (folder=" << ArcAr::_collisionMeshesFolder << ")"
		);
	}

	for (size_t i = 0; i < collisionMeshes.size(); i++) {
		auto mesh = collisionMeshes[i];

		bool isBasketballNet = false;

		if (isBasketball) { // Detect net mesh and disable car collision
			const unsigned char* vertexBase;
			int numVerts, stride;
			const unsigned char* indexBase;
			int indexStride, numFaces;
			mesh->getMeshInterface()->getLockedReadOnlyVertexIndexBase(&vertexBase, numVerts, stride, &indexBase, indexStride, numFaces);
			
			constexpr int BASKETBALL_NET_NUM_VERTS = 505;
			if (numVerts == BASKETBALL_NET_NUM_VERTS) {
				isBasketballNet = true;
			}
		}

		int mask = isBasketballNet ? CollisionMasks::BASKETBALL_NET : 0;
		_worldCollisionBvhShapes.push_back(mesh);
		_AddStaticCollisionShape(mesh, btVector3(0, 0, 0), mask, mask);

		// Don't free the BVH when we deconstruct this arena
		mesh->m_ownsBvh = false;
	}

	{ // Add arena collision planes (floor/walls/ceiling)
		using namespace GameConst;

		float 
			extentX = isBasketball ? ARENA_EXTENT_X_BASKETBALL : ARENA_EXTENT_X,
			extentY = isBasketball ? ARENA_EXTENT_Y_BASKETBALL : ARENA_EXTENT_Y,
			height  = isShatter ? ARENA_HEIGHT_SHATTER : (isBasketball ? ARENA_HEIGHT_BASKETBALL : ARENA_HEIGHT);

		auto fnAddPlane = [&](Vec posUU, Vec normal, int mask = 0) {
			assert(normal.Length() == 1);
			auto planeShape = new btStaticPlaneShape(normal, 0);

			_worldCollisionPlaneShapes.push_back(planeShape);
			_AddStaticCollisionShape(
				planeShape,
				posUU * UU_TO_BT,
				mask, mask
			);
		};

		// Floor
		fnAddPlane(Vec(0, 0, isShatter ? GameConst::FLOOR_HEIGHT_SHATTER : 0), Vec(0, 0, 1), isShatter ? CollisionMasks::SHATTER_FLOOR : 0);

		// Ceiling
		fnAddPlane(Vec(0, 0, height), Vec(0, 0, -1), 0);

		if (!isShatter) {
			// Side walls
			fnAddPlane(Vec(-extentX, 0, height / 2), btVector3( 1, 0, 0));
			fnAddPlane(Vec(extentX, 0, height / 2),  btVector3(-1, 0, 0));
		}
		

		if (isBasketball) {
			// Y walls
			fnAddPlane(Vec(0, -extentY, height / 2), btVector3(0,  1, 0));
			fnAddPlane(Vec(0, extentY, height / 2),  btVector3(0, -1, 0));
		}
	}

	if (isShatter) {
		// Add tiles
		auto tileShapes = ShatterTiles::MakeTileShapes();
		for (int i = 0; i < tileShapes.size(); i++) {
			int teamIdx = i / GameConst::Shatter::NUM_TILES_PER_TEAM;
			int tileIdx = i % GameConst::Shatter::NUM_TILES_PER_TEAM;

			// Shift down so the collision doesn't peek through the floor
			Vec pos = Vec(0, 0, -tileShapes[i]->getMargin());

			auto tileRB = _AddStaticCollisionShape(tileShapes[i], pos, SHATTER_TILE, SHATTER_TILE);
			tileRB->setUserIndex(BT_USERINFO_TYPE_SHATTER_TILE);
			tileRB->setUserIndex2(i);
			_worldShatterTileRBs.push_back(tileRB);
		}
	}
}

AA_NS_END
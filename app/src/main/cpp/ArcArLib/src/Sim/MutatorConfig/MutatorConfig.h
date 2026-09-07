#pragma once
#include "../../GameConst.h"
#include "../GameMode.h"

#include "../../DataStream/DataStreamIn.h"
#include "../../DataStream/DataStreamOut.h"

AA_NS_START

enum class DemoMode : byte {
	NORMAL,
	ON_CONTACT,
	DISABLED
};

struct MutatorConfig {

	Vec gravity = Vec(0, 0, GameConst::GRAVITY_Z);

	float
		carMass = GameConst::CAR_MASS_BT,

		// Friction between car and world (arena)
		carWorldFriction = GameConst::CARWORLD_COLLISION_FRICTION,


		carWorldRestitution = GameConst::CARWORLD_COLLISION_RESTITUTION,

		ballMass,
		ballMaxSpeed = GameConst::BALL_MAX_SPEED,
		ballDrag = GameConst::BALL_DRAG,

		// Friction between car and world (arena)
		ballWorldFriction,

		// Restitution between ball and world (arena)
		ballWorldRestitution,

		jumpAccel = GameConst::JUMP_ACCEL,
		jumpImmediateForce = GameConst::JUMP_IMMEDIATE_FORCE,

		boostAccelGround = GameConst::BOOST_ACCEL_GROUND,
		boostAccelAir = GameConst::BOOST_ACCEL_AIR,
		boostUsedPerSecond = GameConst::BOOST_USED_PER_SECOND,

		respawnDelay = GameConst::DEMO_RESPAWN_TIME,
		bumpCooldownTime = GameConst::BUMP_COOLDOWN_TIME,

		boostPadCooldown_Big = GameConst::BoostPads::COOLDOWN_BIG,
		boostPadCooldown_Small = GameConst::BoostPads::COOLDOWN_SMALL,

		carSpawnBoostAmount = GameConst::BOOST_SPAWN_AMOUNT;

	float
		ballHitExtraForceScale = 1,
		bumpForceScale = 1;

	float
		ballRadius;

	bool
		unlimitedFlips = false,
		unlimitedDoubleJumps = false;

	bool rechargeBoostEnabled = false;
	float rechargeBoostPerSecond = GameConst::RECHARGE_BOOST_PER_SECOND;
	float rechargeBoostDelay = GameConst::RECHARGE_BOOST_DELAY;

	DemoMode demoMode = DemoMode::NORMAL;
	bool enableTeamDemos = false;

	// Only used if the game mode has standard goals (i.e. standard, homing, hockey)
	float goalBaseThresholdY = GameConst::STANDARD_GOAL_SCORE_BASE_THRESHOLD_Y;

	MutatorConfig(GameMode gameMode);

	void Serialize(DataStreamOut& out) const;
	void Deserialize(DataStreamIn& in);
};

#define MUTATOR_CONFIG_SERIALIZATION_FIELDS \
gravity, carMass, carWorldFriction, carWorldRestitution, ballMass, \
ballMaxSpeed, ballDrag, ballWorldFriction, ballWorldRestitution, jumpAccel, \
jumpImmediateForce, boostAccelGround, boostAccelAir, boostUsedPerSecond, respawnDelay, \
carSpawnBoostAmount, bumpCooldownTime, boostPadCooldown_Big, boostPadCooldown_Small, \
ballHitExtraForceScale, bumpForceScale, ballRadius, unlimitedFlips, unlimitedDoubleJumps, \
rechargeBoostEnabled, rechargeBoostPerSecond, rechargeBoostDelay, \
demoMode, enableTeamDemos, goalBaseThresholdY

AA_NS_END
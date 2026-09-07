#include "MutatorConfig.h"

AA_NS_START

MutatorConfig::MutatorConfig(GameMode gameMode) {
	using namespace GameConst;

	switch (gameMode) {
	case GameMode::BASKETBALL:
		ballRadius = BALL_COLLISION_RADIUS_BASKETBALL;
		break;
	case GameMode::HOCKEY:
		ballRadius = Hockey::PUCK_RADIUS;
		break;
	case GameMode::SHATTER:
		ballRadius = BALL_COLLISION_RADIUS_SHATTER;
		break;
	default:
		ballRadius = BALL_COLLISION_RADIUS_STANDARD;
	}

	if (gameMode == GameMode::HOCKEY) {
		ballWorldFriction = Hockey::PUCK_FRICTION;
		ballWorldRestitution = Hockey::PUCK_RESTITUTION;
		ballMass = Hockey::PUCK_MASS_BT;
	} else {
		ballWorldFriction = BALL_FRICTION;
		ballWorldRestitution = BALL_RESTITUTION;
		ballMass = BALL_MASS_BT;
	}

	if (gameMode == GameMode::HOMING) {
		// Infinite boost
		carSpawnBoostAmount = 100;
		boostUsedPerSecond = 0;
	} else if (gameMode == GameMode::SHATTER) {
		// Spawn with 100, and recharge
		carSpawnBoostAmount = 100;
		rechargeBoostEnabled = true;
	}
}

void MutatorConfig::Serialize(DataStreamOut& out) const {
	out.Write<uint16_t>(AA_GET_ARGUMENT_COUNT(MUTATOR_CONFIG_SERIALIZATION_FIELDS));
	out.WriteMultiple(MUTATOR_CONFIG_SERIALIZATION_FIELDS);
}

void MutatorConfig::Deserialize(DataStreamIn& in) {
	uint16_t argCount = in.Read<uint16_t>();

	if (argCount != AA_GET_ARGUMENT_COUNT(MUTATOR_CONFIG_SERIALIZATION_FIELDS)) {
		AA_ERR_CLOSE(" MutatorConfig::Deserialize(): Mutator config is from a different version of ArcAr, fields don't match");
	}

	in.ReadMultiple(MUTATOR_CONFIG_SERIALIZATION_FIELDS);
}

AA_NS_END
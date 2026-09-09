#include "game_engine.h"

#include <android/log.h>
#include <cmath>
#include <cstring>
#include <map>
#include <cstdlib>

#define LOG_TAG "ArcArNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace ArcAr {

GameEngine& GameEngine::Instance() {
	static GameEngine inst;
	return inst;
}

GameEngine::~GameEngine() {
	Shutdown();
}

bool GameEngine::Init(const std::string& meshesDir) {
	std::lock_guard<std::mutex> lock(mutex_);
	if (ready_) {
		LOGI("Init: already ready");
		return true;
	}

	LOGI("Init: begin meshesDir=%s", meshesDir.c_str());

	try {
		bool inited = false;
		if (!meshesDir.empty()) {
			try {
				LOGI("Init: ArcAr::Init(folder)...");
				ArcAr::Init(meshesDir, /*silent=*/false);
				inited = true;
				LOGI("Init: ArcAr::Init(folder) OK");
			} catch (const std::exception& e) {
				LOGE("Init: folder failed: %s", e.what());
			} catch (...) {
				LOGE("Init: folder failed (unknown)");
			}
		}
		if (!inited) {
			try {
				LOGI("Init: InitFromMem(empty)...");
				std::map<GameMode, std::vector<FileData>> empty;
				ArcAr::InitFromMem(empty, /*silent=*/false);
				LOGI("Init: InitFromMem OK");
			} catch (const std::exception& e) {
				LOGE("Init: InitFromMem failed: %s", e.what());
				return false;
			} catch (...) {
				LOGE("Init: InitFromMem failed (unknown)");
				return false;
			}
		}

		LOGI("Init: Arena::Create...");
		ArenaConfig cfg{};
		cfg.memWeightMode = ArenaMemWeightMode::LIGHT;
		// Prefer custom broadphase off on mobile if it causes issues
		cfg.useCustomBroadphase = false;
		arena_ = Arena::Create(GameMode::STANDARD, cfg, 120.f);
		if (!arena_) {
			LOGE("Init: Arena::Create returned null");
			return false;
		}
		LOGI("Init: Arena OK");

		LOGI("Init: AddCar...");
		player_ = arena_->AddCar(Team::BLUE, CAR_CONFIG_BODY_C);
		if (!player_) {
			LOGE("Init: AddCar failed");
			delete arena_;
			arena_ = nullptr;
			return false;
		}
		playerId_ = player_->id;
		LOGI("Init: car id=%u", playerId_);

		{
			CarState cs = player_->GetState();
			cs.pos = Vec(0.f, -2560.f, GameConst::CAR_SPAWN_REST_Z);
			cs.rotMat = RotMat::LookAt(Vec(0.f, 1.f, 0.f), Vec(0.f, 0.f, 1.f)); // face +Y (field forward)
			cs.vel = Vec(0, 0, 0);
			cs.angVel = Vec(0, 0, 0);
			cs.boost = 100.f;
			player_->SetState(cs);
		}
		{
			BallState bs = arena_->ball->GetState();
			bs.pos = Vec(0.f, 0.f, GameConst::BALL_REST_Z);
			bs.vel = Vec(0, 0, 0);
			bs.angVel = Vec(0, 0, 0);
			arena_->ball->SetState(bs);
		}

		ballCam_ = true;
		timeAccum_ = 0.f;
		ready_ = true;
		LOGI("Init: READY");
		return true;
	} catch (const std::exception& e) {
		LOGE("Init: exception: %s", e.what());
		if (arena_) { delete arena_; arena_ = nullptr; }
		player_ = nullptr;
		ready_ = false;
		return false;
	} catch (...) {
		LOGE("Init: unknown exception");
		if (arena_) { delete arena_; arena_ = nullptr; }
		player_ = nullptr;
		ready_ = false;
		return false;
	}
}

void GameEngine::Shutdown() {
	std::lock_guard<std::mutex> lock(mutex_);
	if (arena_) {
		delete arena_;
		arena_ = nullptr;
	}
	player_ = nullptr;
	playerId_ = 0;
	ready_ = false;
	LOGI("Shutdown");
}

void GameEngine::SetControls(const CarControls& controls) {
	std::lock_guard<std::mutex> lock(mutex_);
	pendingControls_ = controls;
	pendingControls_.ClampFix();
}

void GameEngine::SetBallCam(bool enabled) {
	std::lock_guard<std::mutex> lock(mutex_);
	ballCam_ = enabled;
}

void GameEngine::ToggleBallCam() {
	std::lock_guard<std::mutex> lock(mutex_);
	ballCam_ = !ballCam_;
}

void GameEngine::SetInfiniteBoost(bool on) {
	std::lock_guard<std::mutex> lock(mutex_);
	infiniteBoost_ = on;
}

void GameEngine::ResetToKickoff() {
	std::lock_guard<std::mutex> lock(mutex_);
	if (!ready_ || !arena_ || !player_) return;

	CarState cs = player_->GetState();
	cs.pos = Vec(0.f, -2560.f, GameConst::CAR_SPAWN_REST_Z);
	cs.rotMat = RotMat::LookAt(Vec(0.f, 1.f, 0.f), Vec(0.f, 0.f, 1.f)); // face +Y (field forward)
	cs.vel = Vec(0, 0, 0);
	cs.angVel = Vec(0, 0, 0);
	cs.boost = 100.f;
	cs.isDemoed = false;
	player_->SetState(cs);

	BallState bs = arena_->ball->GetState();
	bs.pos = Vec(0.f, 0.f, GameConst::BALL_REST_Z);
	bs.vel = Vec(0, 0, 0);
	bs.angVel = Vec(0, 0, 0);
	arena_->ball->SetState(bs);

	pendingControls_ = CarControls{};
}

void GameEngine::Update(float dtSeconds) {
	std::lock_guard<std::mutex> lock(mutex_);
	if (!ready_ || !arena_ || !player_) return;

	player_->controls = pendingControls_;
	// Infinite boost = unlimited fuel only; player still must hold boost to use it
	if (infiniteBoost_) {
		CarState cs = player_->GetState();
		if (cs.boost < 100.f) {
			cs.boost = 100.f;
			player_->SetState(cs);
		}
	}

	timeAccum_ += dtSeconds;
	if (timeAccum_ > 0.25f) timeAccum_ = 0.25f;

	int steps = 0;
	while (timeAccum_ >= kFixedDt && steps < 8) {
		arena_->Step(1);
		timeAccum_ -= kFixedDt;
		++steps;
	}
}

void GameEngine::RebuildCamera(RenderSnapshot& snap) {
	Vec carPos(snap.carPos[0], snap.carPos[1], snap.carPos[2]);
	Vec fwd(snap.carForward[0], snap.carForward[1], snap.carForward[2]);
	Vec vel(snap.carVel[0], snap.carVel[1], snap.carVel[2]);

	float flen = std::sqrt(fwd.x * fwd.x + fwd.y * fwd.y + fwd.z * fwd.z);
	if (flen > 1e-4f) { fwd.x /= flen; fwd.y /= flen; fwd.z /= flen; }
	else { fwd = Vec(0, 1, 0); }

	float speed = snap.speedUU;
	float speed01 = std::min(speed / 2300.f, 1.f);

	// Dynamic distance / height / look-ahead from speed + boost
	float camDist = 300.f + speed01 * 90.f + (snap.isBoosting ? 40.f : 0.f);
	float camHeight = 120.f + speed01 * 40.f + (snap.onGround ? 0.f : 30.f);
	float lookAhead = 80.f + speed01 * 120.f;

	float desiredPos[3], desiredTgt[3];

	if (ballCam_) {
		Vec ball(snap.ballPos[0], snap.ballPos[1], snap.ballPos[2]);
		Vec toBall = ball - carPos;
		float tlen = std::sqrt(toBall.x * toBall.x + toBall.y * toBall.y + toBall.z * toBall.z);
		Vec dir = (tlen > 50.f) ? Vec(toBall.x / tlen, toBall.y / tlen, toBall.z / tlen) : fwd;
		// Blend look direction with velocity for prediction
		float vlen = std::sqrt(vel.x*vel.x + vel.y*vel.y + vel.z*vel.z);
		if (vlen > 100.f) {
			Vec vn(vel.x/vlen, vel.y/vlen, vel.z/vlen);
			dir.x = dir.x * 0.7f + vn.x * 0.3f;
			dir.y = dir.y * 0.7f + vn.y * 0.3f;
			dir.z = dir.z * 0.7f + vn.z * 0.3f;
			float dl = std::sqrt(dir.x*dir.x+dir.y*dir.y+dir.z*dir.z);
			if (dl > 1e-4f) { dir.x/=dl; dir.y/=dl; dir.z/=dl; }
		}
		float dist = std::min(std::max(tlen * 0.35f, 220.f), 520.f);
		desiredPos[0] = carPos.x - dir.x * dist;
		desiredPos[1] = carPos.y - dir.y * dist;
		desiredPos[2] = carPos.z + camHeight + std::min(tlen * 0.05f, 80.f);
		// Look between car and ball, weighted toward ball
		desiredTgt[0] = carPos.x * 0.25f + ball.x * 0.75f;
		desiredTgt[1] = carPos.y * 0.25f + ball.y * 0.75f;
		desiredTgt[2] = carPos.z * 0.25f + ball.z * 0.75f + 30.f;
	} else {
		// Chase cam with velocity look-ahead
		Vec look = fwd;
		float vlen = std::sqrt(vel.x*vel.x + vel.y*vel.y + vel.z*vel.z);
		if (vlen > 50.f) {
			look.x = fwd.x * 0.55f + (vel.x/vlen) * 0.45f;
			look.y = fwd.y * 0.55f + (vel.y/vlen) * 0.45f;
			look.z = fwd.z * 0.55f + (vel.z/vlen) * 0.45f;
			float ll = std::sqrt(look.x*look.x+look.y*look.y+look.z*look.z);
			if (ll > 1e-4f) { look.x/=ll; look.y/=ll; look.z/=ll; }
		}
		desiredPos[0] = carPos.x - look.x * camDist;
		desiredPos[1] = carPos.y - look.y * camDist;
		desiredPos[2] = carPos.z + camHeight;
		desiredTgt[0] = carPos.x + look.x * lookAhead;
		desiredTgt[1] = carPos.y + look.y * lookAhead;
		desiredTgt[2] = carPos.z + look.z * 20.f + 25.f;
	}

	// Smooth follow (exponential)
	const float follow = 0.12f;
	for (int i = 0; i < 3; i++) {
		camPosSmooth_[i] += (desiredPos[i] - camPosSmooth_[i]) * follow;
		camTgtSmooth_[i] += (desiredTgt[i] - camTgtSmooth_[i]) * follow;
	}

	// FOV: base 68, up to ~85 when boosting / high speed
	float wantFov = 68.f + speed01 * 10.f + (snap.isBoosting ? 8.f : 0.f);
	camFov_ += (wantFov - camFov_) * 0.08f;

	// Shake decay + add from hard ball hits (handled in FillSnapshot)
	camShake_ *= 0.90f;
	if (camShake_ < 0.05f) camShake_ = 0.f;

	float sx = camShake_ * ((float)(rand() % 1000) / 1000.f - 0.5f) * 12.f;
	float sy = camShake_ * ((float)(rand() % 1000) / 1000.f - 0.5f) * 12.f;
	float sz = camShake_ * ((float)(rand() % 1000) / 1000.f - 0.5f) * 6.f;

	snap.camPos[0] = camPosSmooth_[0] + sx;
	snap.camPos[1] = camPosSmooth_[1] + sy;
	snap.camPos[2] = camPosSmooth_[2] + sz;
	snap.camTarget[0] = camTgtSmooth_[0];
	snap.camTarget[1] = camTgtSmooth_[1];
	snap.camTarget[2] = camTgtSmooth_[2];
	snap.ballCam = ballCam_;
	snap.camFov = camFov_;
	snap.camShake = camShake_;
}

void GameEngine::FillSnapshot(RenderSnapshot& snap) {
	std::memset(&snap, 0, sizeof(snap));
	snap.ready = ready_;
	if (!ready_ || !arena_ || !player_) return;

	CarState cs = player_->GetState();
	BallState bs = arena_->ball->GetState();

	snap.carPos[0] = cs.pos.x; snap.carPos[1] = cs.pos.y; snap.carPos[2] = cs.pos.z;
	const Vec& forward = cs.rotMat.forward;
	const Vec& right   = cs.rotMat.right;
	const Vec& up      = cs.rotMat.up;

	snap.carForward[0] = forward.x; snap.carForward[1] = forward.y; snap.carForward[2] = forward.z;
	snap.carRight[0]   = right.x;   snap.carRight[1]   = right.y;   snap.carRight[2]   = right.z;
	snap.carUp[0]      = up.x;      snap.carUp[1]      = up.y;      snap.carUp[2]      = up.z;

	snap.carVel[0] = cs.vel.x; snap.carVel[1] = cs.vel.y; snap.carVel[2] = cs.vel.z;
	snap.boost = cs.boost;
	snap.onGround = cs.isOnGround;
	snap.isBoosting = cs.isBoosting;
	snap.isSupersonic = cs.isSupersonic;

	snap.ballPos[0] = bs.pos.x; snap.ballPos[1] = bs.pos.y; snap.ballPos[2] = bs.pos.z;
	snap.ballVel[0] = bs.vel.x; snap.ballVel[1] = bs.vel.y; snap.ballVel[2] = bs.vel.z;
	snap.ballRadius = GameConst::BALL_COLLISION_RADIUS_STANDARD;

	float sp = std::sqrt(cs.vel.x * cs.vel.x + cs.vel.y * cs.vel.y + cs.vel.z * cs.vel.z);
	snap.speedUU = sp;
	snap.tick = arena_->tickCount;

	float bsp = std::sqrt(bs.vel.x*bs.vel.x + bs.vel.y*bs.vel.y + bs.vel.z*bs.vel.z);
	snap.ballSpeed = bsp;
	float dBall = bsp - prevBallSpeed_;
	prevBallSpeed_ = bsp;
	snap.impactImpulse = (dBall > 200.f) ? dBall : 0.f;
	if (dBall > 400.f) camShake_ = std::min(camShake_ + dBall / 800.f, 3.f);

	// Goal line ~ ±5120 Y.
	// Fix #24: this used to be a level check on the *current* ball position, which
	// stays true for every frame the ball spends beyond the line (multiple goal
	// events per real goal, plus false re-triggers while the ball lingers in the
	// net). Detect the actual crossing instead: compare previous vs current Y so
	// the event fires exactly once, then "disarm" until the ball is back in the
	// field of play so the next real goal can trigger again.
	bool wasBeyond = std::abs(prevBallPosY_) > 5124.f;
	bool isBeyond  = std::abs(bs.pos.y) > 5124.f && bs.pos.z < 650.f && std::abs(bs.pos.x) < 900.f;

	snap.goalScored = false;
	if (isBeyond && !wasBeyond && goalArmed_) {
		snap.goalScored = true;
		goalArmed_ = false; // consumed; won't fire again until re-armed below
	}
	// Re-arm once the ball has clearly returned to the field (past the reset/kickoff
	// area), e.g. after GameEngine resets the ball position following a goal.
	if (std::abs(bs.pos.y) < 5124.f - 50.f) {
		goalArmed_ = true;
	}
	prevBallPosY_ = bs.pos.y;

	RebuildCamera(snap);
}

RenderSnapshot GameEngine::GetSnapshot() {
	std::lock_guard<std::mutex> lock(mutex_);
	RenderSnapshot snap{};
	FillSnapshot(snap);
	return snap;
}

} // namespace ArcAr

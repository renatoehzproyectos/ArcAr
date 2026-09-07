#include "game_engine.h"

#include <android/log.h>
#include <cmath>
#include <cstring>

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
	if (ready_) return true;

	try {
		// Init collision meshes if folder exists; otherwise still create arena
		// (missing meshes → cars/ball still work on internal collision, goals may be limited)
		if (!meshesDir.empty()) {
			try {
				ArcAr::Init(meshesDir, /*silent=*/false);
			} catch (const std::exception& e) {
				LOGI("Mesh init note: %s (continuing)", e.what());
			} catch (...) {
				LOGI("Mesh init failed (continuing without external meshes)");
			}
		}

		ArenaConfig cfg{};
		arena_ = Arena::Create(GameMode::STANDARD, cfg, 120.f);
		if (!arena_) {
			LOGE("Arena::Create failed");
			return false;
		}

		player_ = arena_->AddCar(Team::BLUE, CAR_CONFIG_BODY_C);
		if (!player_) {
			LOGE("AddCar failed");
			delete arena_;
			arena_ = nullptr;
			return false;
		}
		playerId_ = player_->id;

		// Kickoff-ish spawn: car on blue side, ball center
		{
			CarState cs = player_->GetState();
			cs.pos = Vec(0.f, -4608.f, GameConst::CAR_SPAWN_REST_Z);
			cs.rotMat = RotMat::GetIdentity();
			// Face toward midfield (+Y)
			cs.vel = Vec(0, 0, 0);
			cs.angVel = Vec(0, 0, 0);
			cs.boost = 33.f;
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
		LOGI("GameEngine ready — car id=%u", playerId_);
		return true;
	} catch (const std::exception& e) {
		LOGE("GameEngine::Init exception: %s", e.what());
		Shutdown();
		return false;
	} catch (...) {
		LOGE("GameEngine::Init unknown exception");
		Shutdown();
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

void GameEngine::ResetToKickoff() {
	std::lock_guard<std::mutex> lock(mutex_);
	if (!ready_ || !arena_ || !player_) return;

	CarState cs = player_->GetState();
	cs.pos = Vec(0.f, -4608.f, GameConst::CAR_SPAWN_REST_Z);
	cs.rotMat = RotMat::GetIdentity();
	cs.vel = Vec(0, 0, 0);
	cs.angVel = Vec(0, 0, 0);
	cs.boost = 33.f;
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

	timeAccum_ += dtSeconds;
	// Cap spiral of death
	if (timeAccum_ > 0.25f) timeAccum_ = 0.25f;

	int steps = 0;
	while (timeAccum_ >= kFixedDt && steps < 8) {
		arena_->Step(1);
		timeAccum_ -= kFixedDt;
		++steps;
	}
}

void GameEngine::RebuildCamera(RenderSnapshot& snap) {
	const float camDist = 280.f;
	const float camHeight = 110.f;
	const float lookAhead = 60.f;

	Vec carPos(snap.carPos[0], snap.carPos[1], snap.carPos[2]);
	Vec fwd(snap.carForward[0], snap.carForward[1], snap.carForward[2]);
	Vec up(snap.carUp[0], snap.carUp[1], snap.carUp[2]);

	float flen = std::sqrt(fwd.x * fwd.x + fwd.y * fwd.y + fwd.z * fwd.z);
	if (flen > 1e-4f) {
		fwd.x /= flen; fwd.y /= flen; fwd.z /= flen;
	} else {
		fwd = Vec(0, 1, 0);
	}

	if (ballCam_) {
		Vec ball(snap.ballPos[0], snap.ballPos[1], snap.ballPos[2]);
		Vec toBall = ball - carPos;
		float tlen = std::sqrt(toBall.x * toBall.x + toBall.y * toBall.y + toBall.z * toBall.z);
		Vec dir = (tlen > 1.f) ? Vec(toBall.x / tlen, toBall.y / tlen, toBall.z / tlen) : fwd;
		// Camera behind car along direction opposite to ball, looking at ball
		Vec back = Vec(-dir.x, -dir.y, -dir.z);
		snap.camPos[0] = carPos.x + back.x * camDist;
		snap.camPos[1] = carPos.y + back.y * camDist;
		snap.camPos[2] = carPos.z + camHeight;
		snap.camTarget[0] = ball.x;
		snap.camTarget[1] = ball.y;
		snap.camTarget[2] = ball.z;
	} else {
		// Chase cam behind car
		snap.camPos[0] = carPos.x - fwd.x * camDist;
		snap.camPos[1] = carPos.y - fwd.y * camDist;
		snap.camPos[2] = carPos.z + camHeight;
		snap.camTarget[0] = carPos.x + fwd.x * lookAhead;
		snap.camTarget[1] = carPos.y + fwd.y * lookAhead;
		snap.camTarget[2] = carPos.z + 20.f;
	}
	snap.ballCam = ballCam_;
}

void GameEngine::FillSnapshot(RenderSnapshot& snap) {
	std::memset(&snap, 0, sizeof(snap));
	snap.ready = ready_;
	if (!ready_ || !arena_ || !player_) return;

	CarState cs = player_->GetState();
	BallState bs = arena_->ball->GetState();

	snap.carPos[0] = cs.pos.x; snap.carPos[1] = cs.pos.y; snap.carPos[2] = cs.pos.z;
	// RotMat is column-major; forward is typically +X in RL cars? In RocketSim, local +X is forward.
	// Columns: 0=right? Check MathTypes — usually forward is X for cars in this codebase.
	// From typical RocketSim: rotMat.forward is column 0 or a helper.
	// Use rotMat as 3x3 column-major: col0, col1, col2
	// In MathTypes RotMat, forward is often .forward member — check quickly via usage.
	// Safer: extract from matrix columns used by Bullet.
	// We'll use: forward = rotMat * (1,0,0), right = rotMat * (0,1,0), up = rotMat * (0,0,1)
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

	RebuildCamera(snap);
}

RenderSnapshot GameEngine::GetSnapshot() {
	std::lock_guard<std::mutex> lock(mutex_);
	RenderSnapshot snap{};
	FillSnapshot(snap);
	return snap;
}

} // namespace ArcAr

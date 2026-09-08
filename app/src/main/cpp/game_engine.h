#pragma once

#include "ArcArSim.h"
#include "Sim/Arena/Arena.h"
#include "Sim/Car/Car.h"
#include "Sim/CarControls.h"
#include "Sim/GameMode.h"

#include <mutex>
#include <atomic>
#include <string>

namespace ArcAr {

// Snapshot of everything the renderer needs for one frame (UU = unreal units)
struct RenderSnapshot {
	// Car
	float carPos[3];
	float carForward[3];
	float carUp[3];
	float carRight[3];
	float carVel[3];
	float boost;
	bool  onGround;
	bool  isBoosting;
	bool  isSupersonic;

	// Ball
	float ballPos[3];
	float ballVel[3];
	float ballRadius;

	// Camera
	float camPos[3];
	float camTarget[3];
	bool  ballCam;
	float camFov;
	float camShake;

	// Ball impact (for VFX)
	float ballSpeed;
	float impactImpulse; // rise when ball accelerates hard

	// HUD
	float speedUU;
	uint64_t tick;
	bool  ready;
	bool  goalScored; // ball crossed goal line this snapshot (sticky ~0.5s handled in render)
};

class GameEngine {
public:
	static GameEngine& Instance();

	// meshesDir: path where collision meshes live (may be empty → void-like / soft fail)
	bool Init(const std::string& meshesDir);
	void Shutdown();

	void SetControls(const CarControls& controls);
	void SetBallCam(bool enabled);
	void ToggleBallCam();
	bool IsBallCam() const { return ballCam_; }

	void ResetToKickoff();

	void SetInfiniteBoost(bool on);
	bool IsInfiniteBoost() const { return infiniteBoost_; }

	// Advance simulation by dt seconds (accumulates and steps at arena tick rate)
	void Update(float dtSeconds);

	RenderSnapshot GetSnapshot();

	bool IsReady() const { return ready_; }

private:
	GameEngine() = default;
	~GameEngine();

	void RebuildCamera(RenderSnapshot& snap);
	void FillSnapshot(RenderSnapshot& snap);

	std::mutex mutex_;
	Arena* arena_ = nullptr;
	Car* player_ = nullptr;
	uint32_t playerId_ = 0;

	CarControls pendingControls_{};
	bool ballCam_ = true;
	bool ready_ = false;
	bool infiniteBoost_ = false;

	float timeAccum_ = 0.f;
	static constexpr float kFixedDt = 1.f / 120.f;

	// Smoothed camera state
	float camPosSmooth_[3] = {0, -3000, 200};
	float camTgtSmooth_[3] = {0, 0, 100};
	float camFov_ = 70.f;
	float camShake_ = 0.f;
	float prevBallSpeed_ = 0.f;
};



} // namespace ArcAr

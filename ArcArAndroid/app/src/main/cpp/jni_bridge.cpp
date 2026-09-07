// jni_bridge.cpp
//
// The only Android/JNI-specific file in this project. It does not modify
// the ArcAr physics library (upstream RocketSim source) in any way - it
// just calls its public API and reports the result back to Java as a
// string (logged via Logcat by MainActivity).

#include <jni.h>

#include <chrono>
#include <sstream>
#include <android/log.h>

#include "RocketSim.h"

#define LOG_TAG "ArcArNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

using namespace RocketSim;

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arcar_android_MainActivity_runArcAr(JNIEnv* env, jclass /* clazz */,
                                              jstring meshesDirJava) {
	std::ostringstream out;

	const char* meshesDirChars = env->GetStringUTFChars(meshesDirJava, nullptr);
	std::string meshesDir(meshesDirChars);
	env->ReleaseStringUTFChars(meshesDirJava, meshesDirChars);

	try {
		// Loads real Rocket League arena collision meshes if present at
		// <app files dir>/collision_meshes/<Mode>/*.cmf. If the folder is
		// empty or missing, Init() still succeeds - it just means the arena
		// will have no field geometry, which Arena::Create() then reports
		// as an exception (caught below) rather than crashing.
		RocketSim::Init(meshesDir, /* silent */ false);

		out << "ArcAr (RocketSim core) v" << RS_VERSION << " initialized OK.\n";
		out << "Collision meshes dir: " << meshesDir << "\n";

		Arena* arena = Arena::Create(GameMode::SOCCAR);
		arena->AddCar(Team::BLUE);
		arena->AddCar(Team::ORANGE);

		constexpr uint64_t NUM_TICKS = 100'000;

		auto startTime = std::chrono::high_resolution_clock::now();
		for (uint64_t i = 0; i < NUM_TICKS; i++)
			arena->Step();
		auto endTime = std::chrono::high_resolution_clock::now();

		double seconds = std::chrono::duration<double>(endTime - startTime).count();
		double ticksPerSecond = NUM_TICKS / seconds;
		double simSecondsPerRealSecond = ticksPerSecond * arena->tickTime;

		out << "Simulated " << NUM_TICKS << " ticks in " << seconds << "s\n";
		out << "-> " << (uint64_t)ticksPerSecond << " ticks/sec"
		    << " (~" << simSecondsPerRealSecond << "x realtime)\n";
		out << "Final tickCount=" << arena->tickCount;

		delete arena;
	} catch (const std::exception& e) {
		out << "ArcAr run threw an exception: " << e.what();
	} catch (...) {
		out << "ArcAr run threw an unknown exception.";
	}

	std::string result = out.str();
	LOGI("%s", result.c_str());
	return env->NewStringUTF(result.c_str());
}

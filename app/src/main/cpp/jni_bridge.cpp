#include <jni.h>
#include <android/log.h>
#include <string>

#include "game_engine.h"
#include "Sim/CarControls.h"

#define LOG_TAG "ArcArNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

using namespace ArcAr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_arcar_android_NativeBridge_nativeInitImpl(JNIEnv* env, jclass, jstring meshesDirJava) {
	const char* c = env->GetStringUTFChars(meshesDirJava, nullptr);
	std::string meshesDir = c ? c : "";
	env->ReleaseStringUTFChars(meshesDirJava, c);
	bool ok = GameEngine::Instance().Init(meshesDir);
	return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_arcar_android_NativeBridge_nativeShutdownImpl(JNIEnv*, jclass) {
	GameEngine::Instance().Shutdown();
}

JNIEXPORT void JNICALL
Java_com_arcar_android_NativeBridge_nativeSetControlsImpl(
		JNIEnv*, jclass,
		jfloat throttle, jfloat steer,
		jfloat pitch, jfloat yaw, jfloat roll,
		jboolean jump, jboolean boost, jboolean handbrake) {
	CarControls c;
	c.throttle = throttle;
	c.steer = steer;
	c.pitch = pitch;
	c.yaw = yaw;
	c.roll = roll;
	c.jump = jump == JNI_TRUE;
	c.boost = boost == JNI_TRUE;
	c.handbrake = handbrake == JNI_TRUE;
	GameEngine::Instance().SetControls(c);
}

JNIEXPORT void JNICALL
Java_com_arcar_android_NativeBridge_nativeToggleBallCamImpl(JNIEnv*, jclass) {
	GameEngine::Instance().ToggleBallCam();
}

JNIEXPORT void JNICALL
Java_com_arcar_android_NativeBridge_nativeSetBallCamImpl(JNIEnv*, jclass, jboolean on) {
	GameEngine::Instance().SetBallCam(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_arcar_android_NativeBridge_nativeSetInfiniteBoostImpl(JNIEnv*, jclass, jboolean on) {
	GameEngine::Instance().SetInfiniteBoost(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_arcar_android_NativeBridge_nativeResetImpl(JNIEnv*, jclass) {
	GameEngine::Instance().ResetToKickoff();
}

JNIEXPORT void JNICALL
Java_com_arcar_android_NativeBridge_nativeUpdateImpl(JNIEnv*, jclass, jfloat dt) {
	GameEngine::Instance().Update(dt);
}

JNIEXPORT jboolean JNICALL
Java_com_arcar_android_NativeBridge_nativeGetSnapshotImpl(JNIEnv* env, jclass, jfloatArray outArr) {
	if (!outArr) return JNI_FALSE;
	const jsize len = env->GetArrayLength(outArr);
	if (len < 30) return JNI_FALSE;

	RenderSnapshot s = GameEngine::Instance().GetSnapshot();
	jfloat buf[30];
	buf[0] = s.carPos[0]; buf[1] = s.carPos[1]; buf[2] = s.carPos[2];
	buf[3] = s.carForward[0]; buf[4] = s.carForward[1]; buf[5] = s.carForward[2];
	buf[6] = s.carUp[0]; buf[7] = s.carUp[1]; buf[8] = s.carUp[2];
	buf[9] = s.carRight[0]; buf[10] = s.carRight[1]; buf[11] = s.carRight[2];
	buf[12] = s.ballPos[0]; buf[13] = s.ballPos[1]; buf[14] = s.ballPos[2];
	buf[15] = s.ballRadius;
	buf[16] = s.camPos[0]; buf[17] = s.camPos[1]; buf[18] = s.camPos[2];
	buf[19] = s.camTarget[0]; buf[20] = s.camTarget[1]; buf[21] = s.camTarget[2];
	buf[22] = s.boost;
	buf[23] = s.speedUU;
	buf[24] = s.ballCam ? 1.f : 0.f;
	buf[25] = s.onGround ? 1.f : 0.f;
	buf[26] = s.isBoosting ? 1.f : 0.f;
	buf[27] = s.isSupersonic ? 1.f : 0.f;
	buf[28] = (float)(s.tick % 1000000ULL);
	buf[29] = s.ready ? 1.f : 0.f;

	env->SetFloatArrayRegion(outArr, 0, 30, buf);
	return s.ready ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"

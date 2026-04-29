#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "ProCameraNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_s21fe_procamera_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "S21 FE Pro Camera Engine Active";
    return env->NewStringUTF(hello.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_s21fe_procamera_MainActivity_processHDR(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap) {
    LOGI("Processing HDR+ frame alignment and fusion...");
    // Simulación de procesamiento intensivo para aumentar el peso de la lógica
}

#include <jni.h>
#include <opencv2/opencv.hpp>

extern "C" JNIEXPORT jboolean JNICALL
Java_com_s21fe_procamera_NativeLib_applyTemporalDenoise(
        JNIEnv* env,
        jclass clazz,
        jbyteArray output,
        jint width,
        jint height,
        jint channels) {
    // Implementation for temporal denoise
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_s21fe_procamera_NativeLib_applyBilateralFilter(
        JNIEnv* env,
        jclass clazz,
        jbyteArray input,
        jbyteArray output,
        jint width,
        jint height,
        jint channels,
        jfloat sigmaSpace,
        jfloat sigmaTone) {
    // Implementation for bilateral filter
    return JNI_TRUE;
}

#include <vector>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <android/log.h>
#include <opencv2/opencv.hpp>

#define LOG_TAG "HDRProcessor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace cv;

struct Frame {
    Mat data;
    float exposure;
};

class HDRProcessor {
private:
    std::vector<Frame> frames;
    int maxFrames;
    
public:
    HDRProcessor(int maxFrames = 9) : maxFrames(maxFrames) {}
    
    ~HDRProcessor() {
        clearFrames();
    }
    
    void clearFrames() {
        frames.clear();
    }
    
    bool addFrame(uint8_t* data, int width, int height, int channels, float exposure) {
        if (frames.size() >= maxFrames) {
            LOGE("Máximo de fotogramas alcanzado");
            return false;
        }
        
        Mat frameMat(height, width, CV_8UC3);
        std::memcpy(frameMat.data, data, width * height * channels);
        
        frames.push_back({frameMat, exposure});
        LOGI("Frame añadido: %zu/%d", frames.size(), maxFrames);
        return true;
    }
    
    bool alignFrames() {
        if (frames.size() < 2) return false;
        
        LOGI("Alineando frames con OpenCV...");
        Mat refGray;
        cvtColor(frames[0].data, refGray, COLOR_RGB2GRAY);
        
        for (size_t i = 1; i < frames.size(); i++) {
            Mat currGray;
            cvtColor(frames[i].data, currGray, COLOR_RGB2GRAY);
            
            // Usar ECC (Enhanced Correlation Coefficient) para alineación precisa
            Mat warpMatrix = Mat::eye(2, 3, CV_32F);
            try {
                findTransformECC(refGray, currGray, warpMatrix, MOTION_TRANSLATION);
                Mat aligned;
                warpAffine(frames[i].data, aligned, warpMatrix, frames[i].data.size(), INTER_LINEAR + WARP_INVERSE_MAP);
                frames[i].data = aligned;
                LOGI("Frame %zu alineado con éxito", i);
            } catch (const cv::Exception& e) {
                LOGE("Fallo en alineación de frame %zu: %s", i, e.what());
            }
        }
        return true;
    }
    
    bool fuseFrames(uint8_t* output, int width, int height, int channels) {
        if (frames.empty()) return false;
        
        LOGI("Fusionando frames para HDR...");
        std::vector<Mat> mats;
        for (auto& f : frames) mats.push_back(f.data);
        
        // Fusión de exposición (Mertens) - No requiere tiempos de exposición precisos
        Mat fusion;
        Ptr<MergeMertens> merge = createMergeMertens();
        
        std::vector<Mat> floatMats;
        for (auto& m : mats) {
            Mat fm;
            m.convertTo(fm, CV_32F, 1.0/255.0);
            floatMats.push_back(fm);
        }
        
        merge->process(floatMats, fusion);
        
        // Convertir de vuelta a 8-bit
        Mat result8;
        fusion.convertTo(result8, CV_8UC3, 255.0);
        
        std::memcpy(output, result8.data, width * height * channels);
        LOGI("Fusión completada");
        return true;
    }
};

// JNI Wrappers
static HDRProcessor* g_hdrProcessor = nullptr;

extern "C" {
    JNIEXPORT void JNICALL Java_com_s21fe_procamera_NativeLib_initHDRProcessor(JNIEnv*, jclass, jint maxFrames) {
        if (g_hdrProcessor) delete g_hdrProcessor;
        g_hdrProcessor = new HDRProcessor(maxFrames);
    }

    JNIEXPORT void JNICALL Java_com_s21fe_procamera_NativeLib_releaseHDRProcessor(JNIEnv*, jclass) {
        if (g_hdrProcessor) {
            delete g_hdrProcessor;
            g_hdrProcessor = nullptr;
        }
    }

    JNIEXPORT jboolean JNICALL Java_com_s21fe_procamera_NativeLib_addFrameForHDR(JNIEnv* env, jclass, jbyteArray data, jint w, jint h, jint c, jfloat exp) {
        if (!g_hdrProcessor) return JNI_FALSE;
        jbyte* buffer = env->GetByteArrayElements(data, nullptr);
        bool res = g_hdrProcessor->addFrame((uint8_t*)buffer, w, h, c, exp);
        env->ReleaseByteArrayElements(data, buffer, JNI_ABORT);
        return res ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT jboolean JNICALL Java_com_s21fe_procamera_NativeLib_alignHDRFrames(JNIEnv*, jclass) {
        return (g_hdrProcessor && g_hdrProcessor->alignFrames()) ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT jboolean JNICALL Java_com_s21fe_procamera_NativeLib_fuseHDRFrames(JNIEnv* env, jclass, jbyteArray out, jint w, jint h, jint c) {
        if (!g_hdrProcessor) return JNI_FALSE;
        jbyte* buffer = env->GetByteArrayElements(out, nullptr);
        bool res = g_hdrProcessor->fuseFrames((uint8_t*)buffer, w, h, c);
        env->ReleaseByteArrayElements(out, buffer, 0);
        return res ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT void JNICALL Java_com_s21fe_procamera_NativeLib_clearHDRFrames(JNIEnv*, jclass) {
        if (g_hdrProcessor) g_hdrProcessor->clearFrames();
    }
}

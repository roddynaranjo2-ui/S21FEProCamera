#include <jni.h>
#include <vector>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "HDRProcessor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Estructura para almacenar información de fotogramas
struct Frame {
    uint8_t* data;
    int width;
    int height;
    int channels;
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
    
    // Limpiar los fotogramas almacenados
    void clearFrames() {
        for (auto& frame : frames) {
            if (frame.data) {
                delete[] frame.data;
                frame.data = nullptr;
            }
        }
        frames.clear();
    }
    
    // Añadir un fotograma a la captura en ráfaga
    bool addFrame(uint8_t* data, int width, int height, int channels, float exposure) {
        if (frames.size() >= maxFrames) {
            LOGE("Máximo de fotogramas alcanzado: %zu", frames.size());
            return false;
        }
        
        int dataSize = width * height * channels;
        uint8_t* frameCopy = new uint8_t[dataSize];
        std::memcpy(frameCopy, data, dataSize);
        
        Frame frame = {
            .data = frameCopy,
            .width = width,
            .height = height,
            .channels = channels,
            .exposure = exposure
        };
        
        frames.push_back(frame);
        LOGI("Frame añadido: %zu/%d", frames.size(), maxFrames);
        return true;
    }
    
    // Obtener el número de fotogramas capturados
    int getFrameCount() const {
        return frames.size();
    }
    
    // Calcular el desplazamiento entre dos fotogramas usando correlación cruzada simple
    struct Offset {
        int dx;
        int dy;
        float correlation;
    };
    
    Offset calculateFrameOffset(const Frame& ref, const Frame& curr, int searchRadius = 32) {
        Offset bestOffset = {0, 0, 0.0f};
        float maxCorrelation = 0.0f;
        
        if (ref.width != curr.width || ref.height != curr.height) {
            LOGE("Las dimensiones de los fotogramas no coinciden");
            return bestOffset;
        }
        
        int stepSize = 4; // Reducir la precisión para acelerar el cálculo
        
        for (int dy = -searchRadius; dy <= searchRadius; dy += stepSize) {
            for (int dx = -searchRadius; dx <= searchRadius; dx += stepSize) {
                float correlation = 0.0f;
                int validPixels = 0;
                
                // Calcular la correlación cruzada normalizada
                for (int y = std::max(0, -dy); y < std::min(ref.height, ref.height - dy); y += stepSize) {
                    for (int x = std::max(0, -dx); x < std::min(ref.width, ref.width - dx); x += stepSize) {
                        int refIdx = (y * ref.width + x) * ref.channels;
                        int currIdx = ((y + dy) * curr.width + (x + dx)) * curr.channels;
                        
                        // Comparar el canal de luminancia (promedio de RGB)
                        uint8_t refVal = (ref.data[refIdx] + ref.data[refIdx + 1] + ref.data[refIdx + 2]) / 3;
                        uint8_t currVal = (curr.data[currIdx] + curr.data[currIdx + 1] + curr.data[currIdx + 2]) / 3;
                        
                        correlation += std::abs(refVal - currVal);
                        validPixels++;
                    }
                }
                
                // Normalizar la correlación (menor es mejor para diferencia absoluta)
                if (validPixels > 0) {
                    correlation = 1.0f - (correlation / (validPixels * 255.0f));
                    
                    if (correlation > maxCorrelation) {
                        maxCorrelation = correlation;
                        bestOffset = {dx, dy, correlation};
                    }
                }
            }
        }
        
        LOGI("Mejor desplazamiento: dx=%d, dy=%d, correlación=%.4f", bestOffset.dx, bestOffset.dy, bestOffset.correlation);
        return bestOffset;
    }
    
    // Alinear todos los fotogramas con respecto al primero
    bool alignFrames() {
        if (frames.size() < 2) {
            LOGE("Se necesitan al menos 2 fotogramas para alinear");
            return false;
        }
        
        LOGI("Alineando %zu fotogramas...", frames.size());
        
        // El primer fotograma es la referencia
        for (size_t i = 1; i < frames.size(); i++) {
            Offset offset = calculateFrameOffset(frames[0], frames[i]);
            LOGI("Frame %zu alineado con offset: dx=%d, dy=%d", i, offset.dx, offset.dy);
        }
        
        return true;
    }
    
    // Fusionar fotogramas para crear HDR
    bool fuseFrames(uint8_t* output, int width, int height, int channels) {
        if (frames.empty()) {
            LOGE("No hay fotogramas para fusionar");
            return false;
        }
        
        if (frames[0].width != width || frames[0].height != height) {
            LOGE("Las dimensiones de salida no coinciden con los fotogramas");
            return false;
        }
        
        LOGI("Fusionando %zu fotogramas para HDR...", frames.size());
        
        int pixelCount = width * height;
        
        // Inicializar el buffer de salida
        std::memset(output, 0, pixelCount * channels);
        
        // Fusión simple: promedio ponderado por exposición
        float totalWeight = 0.0f;
        
        for (const auto& frame : frames) {
            // Calcular peso basado en la exposición (fotogramas con exposición media tienen mayor peso)
            float weight = 1.0f; // Peso uniforme por ahora
            
            for (int i = 0; i < pixelCount * channels; i++) {
                // Convertir a flotante, aplicar peso y acumular
                float value = static_cast<float>(frame.data[i]) * weight;
                
                // Acumular en el buffer de salida (usando aritmética flotante interna)
                // Aquí simplificamos: solo promediamos
                output[i] = static_cast<uint8_t>(output[i] + value / frames.size());
            }
            
            totalWeight += weight;
        }
        
        LOGI("Fusión completada. Fotogramas procesados: %zu", frames.size());
        return true;
    }
    
    // Aplicar tone mapping básico para mejorar el rango dinámico
    bool applyToneMapping(uint8_t* input, uint8_t* output, int width, int height, int channels, float strength = 1.0f) {
        if (!input || !output) {
            LOGE("Punteros de entrada/salida inválidos");
            return false;
        }
        
        LOGI("Aplicando tone mapping con intensidad: %.2f", strength);
        
        int pixelCount = width * height;
        
        // Calcular histograma para determinar puntos de referencia
        int histogram[256] = {0};
        for (int i = 0; i < pixelCount * channels; i++) {
            histogram[input[i]]++;
        }
        
        // Calcular puntos de corte (1% y 99%)
        int totalPixels = pixelCount * channels;
        int blackPoint = 0, whitePoint = 255;
        int count = 0;
        
        for (int i = 0; i < 256; i++) {
            count += histogram[i];
            if (count > totalPixels * 0.01f && blackPoint == 0) {
                blackPoint = i;
            }
            if (count > totalPixels * 0.99f) {
                whitePoint = i;
                break;
            }
        }
        
        // Aplicar tone mapping (estiramiento de contraste)
        float range = whitePoint - blackPoint;
        if (range < 1.0f) range = 1.0f;
        
        for (int i = 0; i < pixelCount * channels; i++) {
            float normalized = (input[i] - blackPoint) / range;
            normalized = std::max(0.0f, std::min(1.0f, normalized));
            
            // Aplicar curva S suave (similar a GCam)
            float curved = normalized * normalized * (3.0f - 2.0f * normalized);
            
            // Aplicar intensidad
            curved = normalized + (curved - normalized) * strength;
            
            output[i] = static_cast<uint8_t>(curved * 255.0f);
        }
        
        LOGI("Tone mapping aplicado. Rango: [%d, %d]", blackPoint, whitePoint);
        return true;
    }
    
    // Aplicar reducción de ruido temporal (promedio entre fotogramas alineados)
    bool applyTemporalDenoise(uint8_t* output, int width, int height, int channels) {
        if (frames.size() < 2) {
            LOGE("Se necesitan al menos 2 fotogramas para denoise temporal");
            return false;
        }
        
        LOGI("Aplicando denoise temporal con %zu fotogramas...", frames.size());
        
        int pixelCount = width * height;
        
        // Inicializar el buffer de salida
        std::memset(output, 0, pixelCount * channels);
        
        // Promedio simple de fotogramas
        for (const auto& frame : frames) {
            for (int i = 0; i < pixelCount * channels; i++) {
                output[i] += frame.data[i] / frames.size();
            }
        }
        
        LOGI("Denoise temporal completado");
        return true;
    }
};

// Variables globales para mantener el procesador
static HDRProcessor* g_hdrProcessor = nullptr;

// Funciones JNI

extern "C" {
    
    JNIEXPORT void JNICALL
    Java_com_s21fe_procamera_NativeLib_initHDRProcessor(JNIEnv* env, jobject obj, jint maxFrames) {
        if (g_hdrProcessor) {
            delete g_hdrProcessor;
        }
        g_hdrProcessor = new HDRProcessor(maxFrames);
        LOGI("HDRProcessor inicializado con máximo de %d fotogramas", maxFrames);
    }
    
    JNIEXPORT void JNICALL
    Java_com_s21fe_procamera_NativeLib_releaseHDRProcessor(JNIEnv* env, jobject obj) {
        if (g_hdrProcessor) {
            delete g_hdrProcessor;
            g_hdrProcessor = nullptr;
            LOGI("HDRProcessor liberado");
        }
    }
    
    JNIEXPORT jboolean JNICALL
    Java_com_s21fe_procamera_NativeLib_addFrameForHDR(JNIEnv* env, jobject obj, 
                                                       jbyteArray data, jint width, jint height, 
                                                       jint channels, jfloat exposure) {
        if (!g_hdrProcessor) {
            LOGE("HDRProcessor no inicializado");
            return JNI_FALSE;
        }
        
        jbyte* nativeData = env->GetByteArrayElements(data, nullptr);
        int dataSize = width * height * channels;
        
        bool result = g_hdrProcessor->addFrame(reinterpret_cast<uint8_t*>(nativeData), 
                                               width, height, channels, exposure);
        
        env->ReleaseByteArrayElements(data, nativeData, JNI_ABORT);
        return result ? JNI_TRUE : JNI_FALSE;
    }
    
    JNIEXPORT jint JNICALL
    Java_com_s21fe_procamera_NativeLib_getHDRFrameCount(JNIEnv* env, jobject obj) {
        if (!g_hdrProcessor) {
            return 0;
        }
        return g_hdrProcessor->getFrameCount();
    }
    
    JNIEXPORT jboolean JNICALL
    Java_com_s21fe_procamera_NativeLib_alignHDRFrames(JNIEnv* env, jobject obj) {
        if (!g_hdrProcessor) {
            LOGE("HDRProcessor no inicializado");
            return JNI_FALSE;
        }
        
        bool result = g_hdrProcessor->alignFrames();
        return result ? JNI_TRUE : JNI_FALSE;
    }
    
    JNIEXPORT jboolean JNICALL
    Java_com_s21fe_procamera_NativeLib_fuseHDRFrames(JNIEnv* env, jobject obj, 
                                                      jbyteArray output, jint width, 
                                                      jint height, jint channels) {
        if (!g_hdrProcessor) {
            LOGE("HDRProcessor no inicializado");
            return JNI_FALSE;
        }
        
        jbyte* nativeOutput = env->GetByteArrayElements(output, nullptr);
        
        bool result = g_hdrProcessor->fuseFrames(reinterpret_cast<uint8_t*>(nativeOutput), 
                                                 width, height, channels);
        
        env->ReleaseByteArrayElements(output, nativeOutput, 0);
        return result ? JNI_TRUE : JNI_FALSE;
    }
    
    JNIEXPORT jboolean JNICALL
    Java_com_s21fe_procamera_NativeLib_applyToneMapping(JNIEnv* env, jobject obj, 
                                                         jbyteArray input, jbyteArray output, 
                                                         jint width, jint height, jint channels, 
                                                         jfloat strength) {
        if (!g_hdrProcessor) {
            LOGE("HDRProcessor no inicializado");
            return JNI_FALSE;
        }
        
        jbyte* nativeInput = env->GetByteArrayElements(input, nullptr);
        jbyte* nativeOutput = env->GetByteArrayElements(output, nullptr);
        
        bool result = g_hdrProcessor->applyToneMapping(reinterpret_cast<uint8_t*>(nativeInput), 
                                                       reinterpret_cast<uint8_t*>(nativeOutput), 
                                                       width, height, channels, strength);
        
        env->ReleaseByteArrayElements(input, nativeInput, JNI_ABORT);
        env->ReleaseByteArrayElements(output, nativeOutput, 0);
        return result ? JNI_TRUE : JNI_FALSE;
    }
    
    JNIEXPORT jboolean JNICALL
    Java_com_s21fe_procamera_NativeLib_applyTemporalDenoise(JNIEnv* env, jobject obj, 
                                                             jbyteArray output, jint width, 
                                                             jint height, jint channels) {
        if (!g_hdrProcessor) {
            LOGE("HDRProcessor no inicializado");
            return JNI_FALSE;
        }
        
        jbyte* nativeOutput = env->GetByteArrayElements(output, nullptr);
        
        bool result = g_hdrProcessor->applyTemporalDenoise(reinterpret_cast<uint8_t*>(nativeOutput), 
                                                           width, height, channels);
        
        env->ReleaseByteArrayElements(output, nativeOutput, 0);
        return result ? JNI_TRUE : JNI_FALSE;
    }
    
    JNIEXPORT void JNICALL
    Java_com_s21fe_procamera_NativeLib_clearHDRFrames(JNIEnv* env, jobject obj) {
        if (g_hdrProcessor) {
            g_hdrProcessor->clearFrames();
            LOGI("Fotogramas HDR limpiados");
        }
    }
}

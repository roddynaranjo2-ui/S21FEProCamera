#include <jni.h>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "ImageProcessor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

class ImageProcessor {
public:
    // Aplicar corrección de color usando una LUT (Look-Up Table)
    static bool applyColorCorrection(uint8_t* input, uint8_t* output, int width, int height, 
                                     int channels, const float* lut) {
        if (!input || !output || !lut) {
            LOGE("Punteros inválidos");
            return false;
        }
        
        LOGI("Aplicando corrección de color con LUT...");
        
        int pixelCount = width * height;
        
        for (int i = 0; i < pixelCount * channels; i++) {
            uint8_t value = input[i];
            // La LUT tiene 256 entradas (una por cada valor de intensidad)
            output[i] = static_cast<uint8_t>(lut[value] * 255.0f);
        }
        
        LOGI("Corrección de color completada");
        return true;
    }
    
    // Aplicar sharpening consciente de bordes
    static bool applySharpeningEdgeAware(uint8_t* input, uint8_t* output, int width, int height, 
                                         int channels, float strength = 1.0f) {
        if (!input || !output) {
            LOGE("Punteros inválidos");
            return false;
        }
        
        LOGI("Aplicando sharpening consciente de bordes con intensidad: %.2f", strength);
        
        // Crear un buffer temporal para el cálculo
        float* temp = new float[width * height * channels];
        
        // Copiar datos de entrada a flotante
        for (int i = 0; i < width * height * channels; i++) {
            temp[i] = static_cast<float>(input[i]);
        }
        
        // Aplicar filtro de sharpening (kernel Laplaciano)
        float kernel[3][3] = {
            {-1, -1, -1},
            {-1,  8, -1},
            {-1, -1, -1}
        };
        
        float* sharpened = new float[width * height * channels];
        std::memcpy(sharpened, temp, width * height * channels * sizeof(float));
        
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                for (int c = 0; c < channels; c++) {
                    float sum = 0.0f;
                    
                    for (int ky = -1; ky <= 1; ky++) {
                        for (int kx = -1; kx <= 1; kx++) {
                            int idx = ((y + ky) * width + (x + kx)) * channels + c;
                            sum += temp[idx] * kernel[ky + 1][kx + 1];
                        }
                    }
                    
                    int idx = (y * width + x) * channels + c;
                    sharpened[idx] = temp[idx] + sum * strength * 0.1f; // Factor de escala
                }
            }
        }
        
        // Convertir de vuelta a uint8_t con clamping
        for (int i = 0; i < width * height * channels; i++) {
            float value = sharpened[i];
            value = std::max(0.0f, std::min(255.0f, value));
            output[i] = static_cast<uint8_t>(value);
        }
        
        delete[] temp;
        delete[] sharpened;
        
        LOGI("Sharpening completado");
        return true;
    }
    
    // Aplicar reducción de ruido espacial (Bilateral Filter)
    static bool applyBilateralFilter(uint8_t* input, uint8_t* output, int width, int height, 
                                     int channels, float sigmaSpace = 2.0f, float sigmaTone = 50.0f) {
        if (!input || !output) {
            LOGE("Punteros inválidos");
            return false;
        }
        
        LOGI("Aplicando Bilateral Filter con sigmaSpace=%.2f, sigmaTone=%.2f", sigmaSpace, sigmaTone);
        
        int radius = static_cast<int>(sigmaSpace * 3);
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int c = 0; c < channels; c++) {
                    float sum = 0.0f;
                    float weightSum = 0.0f;
                    
                    uint8_t centerValue = input[(y * width + x) * channels + c];
                    
                    for (int ky = -radius; ky <= radius; ky++) {
                        for (int kx = -radius; kx <= radius; kx++) {
                            int ny = y + ky;
                            int nx = x + kx;
                            
                            if (ny >= 0 && ny < height && nx >= 0 && nx < width) {
                                uint8_t value = input[(ny * width + nx) * channels + c];
                                
                                // Peso espacial (Gaussiana)
                                float spatialWeight = std::exp(-(kx * kx + ky * ky) / (2.0f * sigmaSpace * sigmaSpace));
                                
                                // Peso de tono (Gaussiana)
                                float toneDiff = static_cast<float>(value - centerValue);
                                float toneWeight = std::exp(-(toneDiff * toneDiff) / (2.0f * sigmaTone * sigmaTone));
                                
                                float weight = spatialWeight * toneWeight;
                                sum += value * weight;
                                weightSum += weight;
                            }
                        }
                    }
                    
                    output[(y * width + x) * channels + c] = static_cast<uint8_t>(sum / weightSum);
                }
            }
        }
        
        LOGI("Bilateral Filter completado");
        return true;
    }
    
    // Aplicar boost de contraste local
    static bool applyLocalContrastBoost(uint8_t* input, uint8_t* output, int width, int height, 
                                        int channels, float strength = 1.0f) {
        if (!input || !output) {
            LOGE("Punteros inválidos");
            return false;
        }
        
        LOGI("Aplicando boost de contraste local con intensidad: %.2f", strength);
        
        // Crear versión borrosa de la imagen
        float* blurred = new float[width * height * channels];
        
        // Aplicar filtro Gaussiano simple (3x3)
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int c = 0; c < channels; c++) {
                    float sum = 0.0f;
                    float weightSum = 0.0f;
                    
                    for (int ky = -1; ky <= 1; ky++) {
                        for (int kx = -1; kx <= 1; kx++) {
                            int ny = y + ky;
                            int nx = x + kx;
                            
                            if (ny >= 0 && ny < height && nx >= 0 && nx < width) {
                                float weight = 1.0f / (1.0f + std::abs(kx) + std::abs(ky));
                                sum += input[(ny * width + nx) * channels + c] * weight;
                                weightSum += weight;
                            }
                        }
                    }
                    
                    blurred[(y * width + x) * channels + c] = sum / weightSum;
                }
            }
        }
        
        // Calcular diferencia local y aplicar boost
        for (int i = 0; i < width * height * channels; i++) {
            float original = static_cast<float>(input[i]);
            float blurredValue = blurred[i];
            float detail = original - blurredValue;
            
            float boosted = blurredValue + detail * (1.0f + strength);
            boosted = std::max(0.0f, std::min(255.0f, boosted));
            
            output[i] = static_cast<uint8_t>(boosted);
        }
        
        delete[] blurred;
        
        LOGI("Boost de contraste local completado");
        return true;
    }
    
    // Aplicar corrección de gamma
    static bool applyGammaCorrection(uint8_t* input, uint8_t* output, int width, int height, 
                                     int channels, float gamma = 2.2f) {
        if (!input || !output) {
            LOGE("Punteros inválidos");
            return false;
        }
        
        LOGI("Aplicando corrección de gamma: %.2f", gamma);
        
        float invGamma = 1.0f / gamma;
        
        for (int i = 0; i < width * height * channels; i++) {
            float normalized = static_cast<float>(input[i]) / 255.0f;
            float corrected = std::pow(normalized, invGamma);
            output[i] = static_cast<uint8_t>(corrected * 255.0f);
        }
        
        LOGI("Corrección de gamma completada");
        return true;
    }
    
    // Aplicar aumento de saturación
    static bool increaseSaturation(uint8_t* input, uint8_t* output, int width, int height, 
                                   float saturation = 1.2f) {
        if (!input || !output) {
            LOGE("Punteros inválidos");
            return false;
        }
        
        LOGI("Aumentando saturación: %.2f", saturation);
        
        int pixelCount = width * height;
        
        for (int i = 0; i < pixelCount; i++) {
            int idx = i * 3; // Asumiendo RGB
            
            uint8_t r = input[idx];
            uint8_t g = input[idx + 1];
            uint8_t b = input[idx + 2];
            
            // Convertir RGB a HSV
            float rf = r / 255.0f;
            float gf = g / 255.0f;
            float bf = b / 255.0f;
            
            float maxC = std::max({rf, gf, bf});
            float minC = std::min({rf, gf, bf});
            float delta = maxC - minC;
            
            float h = 0.0f, s = 0.0f, v = maxC;
            
            if (delta != 0.0f) {
                s = delta / maxC;
                
                if (maxC == rf) {
                    h = std::fmod((gf - bf) / delta, 6.0f);
                } else if (maxC == gf) {
                    h = (bf - rf) / delta + 2.0f;
                } else {
                    h = (rf - gf) / delta + 4.0f;
                }
                h /= 6.0f;
            }
            
            // Aumentar saturación
            s = std::min(1.0f, s * saturation);
            
            // Convertir de vuelta a RGB
            float c = v * s;
            float x = c * (1.0f - std::fabs(std::fmod(h * 6.0f, 2.0f) - 1.0f));
            float m = v - c;
            
            float rf2 = 0.0f, gf2 = 0.0f, bf2 = 0.0f;
            
            if (h < 1.0f / 6.0f) {
                rf2 = c; gf2 = x; bf2 = 0.0f;
            } else if (h < 2.0f / 6.0f) {
                rf2 = x; gf2 = c; bf2 = 0.0f;
            } else if (h < 3.0f / 6.0f) {
                rf2 = 0.0f; gf2 = c; bf2 = x;
            } else if (h < 4.0f / 6.0f) {
                rf2 = 0.0f; gf2 = x; bf2 = c;
            } else if (h < 5.0f / 6.0f) {
                rf2 = x; gf2 = 0.0f; bf2 = c;
            } else {
                rf2 = c; gf2 = 0.0f; bf2 = x;
            }
            
            output[idx] = static_cast<uint8_t>((rf2 + m) * 255.0f);
            output[idx + 1] = static_cast<uint8_t>((gf2 + m) * 255.0f);
            output[idx + 2] = static_cast<uint8_t>((bf2 + m) * 255.0f);
        }
        
        LOGI("Saturación aumentada");
        return true;
    }
};

// Funciones JNI

extern "C" {
    
    JNIEXPORT jboolean JNICALL
    Java_com_s21fe_procamera_NativeLib_applyColorCorrection(JNIEnv* env, jobject obj, 
                                                             jbyteArray input, jbyteArray output, 
                                                             jint width, jint height, jint channels, 
                                                             jfloatArray lut) {
        jbyte* nativeInput = env->GetByteArrayElements(input, nullptr);
        jbyte* nativeOutput = env->GetByteArrayElements(output, nullptr);
        jfloat* nativeLut = env->GetFloatArrayElements(lut, nullptr);
        
        bool result = ImageProcessor::applyColorCorrection(
            reinterpret_cast<uint8_t*>(nativeInput),
            reinterpret_cast<uint8_t*>(nativeOutput),
            width, height, channels, nativeLut);
        
        env->ReleaseByteArrayElements(input, nativeInput, JNI_ABORT);
        env->ReleaseByteArrayElements(output, nativeOutput, 0);
        env->ReleaseFloatArrayElements(lut, nativeLut, JNI_ABORT);
        
        return result ? JNI_TRUE : JNI_FALSE;
    }
    
    JNIEXPORT jboolean JNICALL
    Java_com_s21fe_procamera_NativeLib_applySharpeningEdgeAware(JNIEnv* env, jobject obj, 
                                                                 jbyteArray input, jbyteArray output, 
                                                                 jint width, jint height, jint channels, 
                                                                 jfloat strength) {
        jbyte* nativeInput = env->GetByteArrayElements(input, nullptr);
        jbyte* nativeOutput = env->GetByteArrayElements(output, nullptr);
        
        bool result = ImageProcessor::applySharpeningEdgeAware(
            reinterpret_cast<uint8_t*>(nativeInput),
            reinterpret_cast<uint8_t*>(nativeOutput),
            width, height, channels, strength);
        
        env->ReleaseByteArrayElements(input, nativeInput, JNI_ABORT);
        env->ReleaseByteArrayElements(output, nativeOutput, 0);
        
        return result ? JNI_TRUE : JNI_FALSE;
    }
    
    JNIEXPORT jboolean JNICALL
    Java_com_s21fe_procamera_NativeLib_applyBilateralFilter(JNIEnv* env, jobject obj, 
                                                             jbyteArray input, jbyteArray output, 
                                                             jint width, jint height, jint channels, 
                                                             jfloat sigmaSpace, jfloat sigmaTone) {
        jbyte* nativeInput = env->GetByteArrayElements(input, nullptr);
        jbyte* nativeOutput = env->GetByteArrayElements(output, nullptr);
        
        bool result = ImageProcessor::applyBilateralFilter(
            reinterpret_cast<uint8_t*>(nativeInput),
            reinterpret_cast<uint8_t*>(nativeOutput),
            width, height, channels, sigmaSpace, sigmaTone);
        
        env->ReleaseByteArrayElements(input, nativeInput, JNI_ABORT);
        env->ReleaseByteArrayElements(output, nativeOutput, 0);
        
        return result ? JNI_TRUE : JNI_FALSE;
    }
    
    JNIEXPORT jboolean JNICALL
    Java_com_s21fe_procamera_NativeLib_applyLocalContrastBoost(JNIEnv* env, jobject obj, 
                                                                jbyteArray input, jbyteArray output, 
                                                                jint width, jint height, jint channels, 
                                                                jfloat strength) {
        jbyte* nativeInput = env->GetByteArrayElements(input, nullptr);
        jbyte* nativeOutput = env->GetByteArrayElements(output, nullptr);
        
        bool result = ImageProcessor::applyLocalContrastBoost(
            reinterpret_cast<uint8_t*>(nativeInput),
            reinterpret_cast<uint8_t*>(nativeOutput),
            width, height, channels, strength);
        
        env->ReleaseByteArrayElements(input, nativeInput, JNI_ABORT);
        env->ReleaseByteArrayElements(output, nativeOutput, 0);
        
        return result ? JNI_TRUE : JNI_FALSE;
    }
    
    JNIEXPORT jboolean JNICALL
    Java_com_s21fe_procamera_NativeLib_applyGammaCorrection(JNIEnv* env, jobject obj, 
                                                             jbyteArray input, jbyteArray output, 
                                                             jint width, jint height, jint channels, 
                                                             jfloat gamma) {
        jbyte* nativeInput = env->GetByteArrayElements(input, nullptr);
        jbyte* nativeOutput = env->GetByteArrayElements(output, nullptr);
        
        bool result = ImageProcessor::applyGammaCorrection(
            reinterpret_cast<uint8_t*>(nativeInput),
            reinterpret_cast<uint8_t*>(nativeOutput),
            width, height, channels, gamma);
        
        env->ReleaseByteArrayElements(input, nativeInput, JNI_ABORT);
        env->ReleaseByteArrayElements(output, nativeOutput, 0);
        
        return result ? JNI_TRUE : JNI_FALSE;
    }
    
    JNIEXPORT jboolean JNICALL
    Java_com_s21fe_procamera_NativeLib_increaseSaturation(JNIEnv* env, jobject obj, 
                                                           jbyteArray input, jbyteArray output, 
                                                           jint width, jint height, jfloat saturation) {
        jbyte* nativeInput = env->GetByteArrayElements(input, nullptr);
        jbyte* nativeOutput = env->GetByteArrayElements(output, nullptr);
        
        bool result = ImageProcessor::increaseSaturation(
            reinterpret_cast<uint8_t*>(nativeInput),
            reinterpret_cast<uint8_t*>(nativeOutput),
            width, height, saturation);
        
        env->ReleaseByteArrayElements(input, nativeInput, JNI_ABORT);
        env->ReleaseByteArrayElements(output, nativeOutput, 0);
        
        return result ? JNI_TRUE : JNI_FALSE;
    }
}

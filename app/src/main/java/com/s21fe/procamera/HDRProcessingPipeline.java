package com.s21fe.procamera;

import android.graphics.Bitmap;
import android.media.Image;
import android.util.Log;

import androidx.camera.core.ImageProxy;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pipeline de procesamiento HDR que orquesta la captura multi-frame,
 * alineación, fusión y tone mapping inspirado en GCam.
 */
public class HDRProcessingPipeline {
    
    private static final String TAG = "HDRProcessingPipeline";
    
    private BurstCaptureManager burstCaptureManager;
    private ExecutorService processingExecutor;
    private HDRProcessingCallback callback;
    
    public interface HDRProcessingCallback {
        void onProcessingStarted();
        void onFrameCaptured(int frameNumber, int totalFrames);
        void onProcessingStep(String step);
        void onProcessingComplete(Bitmap resultBitmap);
        void onError(String error);
    }
    
    public HDRProcessingPipeline(BurstCaptureManager burstCaptureManager) {
        this.burstCaptureManager = burstCaptureManager;
        this.processingExecutor = Executors.newSingleThreadExecutor();
    }
    
    /**
     * Iniciar el pipeline HDR completo
     * @param frameCount Número de fotogramas a capturar
     * @param callback Callback para notificaciones
     */
    public void startHDRProcessing(int frameCount, HDRProcessingCallback callback) {
        this.callback = callback;
        
        if (callback != null) {
            callback.onProcessingStarted();
        }
        
        Log.d(TAG, "Iniciando pipeline HDR con " + frameCount + " fotogramas");
        
        // Iniciar captura en ráfaga
        burstCaptureManager.startBurstCapture(frameCount, new BurstCaptureManager.BurstCaptureCallback() {
            @Override
            public void onFrameCaptured(int frameNumber, int totalFrames) {
                Log.d(TAG, "Fotograma capturado: " + frameNumber + "/" + totalFrames);
                if (callback != null) {
                    callback.onFrameCaptured(frameNumber, totalFrames);
                }
            }
            
            @Override
            public void onBurstComplete(List<ImageProxy> frames) {
                Log.d(TAG, "Captura en ráfaga completada. Procesando " + frames.size() + " fotogramas");
                processingExecutor.execute(() -> processFrames(frames));
            }
            
            @Override
            public void onError(String error) {
                Log.e(TAG, "Error en captura: " + error);
                if (callback != null) {
                    callback.onError("Error en captura: " + error);
                }
            }
        });
    }
    
    /**
     * Procesar los fotogramas capturados
     */
    private void processFrames(List<ImageProxy> frames) {
        try {
            if (frames.isEmpty()) {
                if (callback != null) {
                    callback.onError("No hay fotogramas para procesar");
                }
                return;
            }
            
            // Paso 1: Inicializar el procesador HDR nativo
            if (callback != null) {
                callback.onProcessingStep("Inicializando procesador HDR...");
            }
            NativeLib.initHDRProcessor(frames.size());
            
            // Paso 2: Añadir fotogramas al procesador nativo
            if (callback != null) {
                callback.onProcessingStep("Añadiendo fotogramas al procesador...");
            }
            
            for (int i = 0; i < frames.size(); i++) {
                ImageProxy frame = frames.get(i);
                byte[] frameData = BurstCaptureManager.imageToByteArray(frame);
                
                Image mediaImage = frame.getImage();
                if (mediaImage == null) {
                    Log.w(TAG, "Media Image es null en fotograma " + i);
                    continue;
                }
                
                float exposure = 1.0f; // Exposición normalizada (será mejorada después)
                NativeLib.addFrameForHDR(frameData, mediaImage.getWidth(), mediaImage.getHeight(), 3, exposure);
                
                Log.d(TAG, "Fotograma " + (i + 1) + " añadido al procesador");
            }
            
            // Paso 3: Alinear fotogramas
            if (callback != null) {
                callback.onProcessingStep("Alineando fotogramas...");
            }
            boolean alignmentSuccess = NativeLib.alignHDRFrames();
            if (!alignmentSuccess) {
                Log.w(TAG, "Alineación de fotogramas falló, continuando con fusión...");
            }
            
            // Paso 4: Fusionar fotogramas para HDR
            if (callback != null) {
                callback.onProcessingStep("Fusionando fotogramas para HDR...");
            }
            
            Image firstImage = frames.get(0).getImage();
            if (firstImage == null) {
                if (callback != null) {
                    callback.onError("Primera imagen es null");
                }
                return;
            }
            
            int width = firstImage.getWidth();
            int height = firstImage.getHeight();
            int channels = 3; // RGB
            byte[] fusedData = new byte[width * height * channels];
            
            boolean fusionSuccess = NativeLib.fuseHDRFrames(fusedData, width, height, channels);
            if (!fusionSuccess) {
                if (callback != null) {
                    callback.onError("Fusión de fotogramas falló");
                }
                return;
            }
            
            // Paso 5: Aplicar Tone Mapping
            if (callback != null) {
                callback.onProcessingStep("Aplicando tone mapping...");
            }
            
            byte[] toneMappedData = new byte[width * height * channels];
            boolean toneMappingSuccess = NativeLib.applyToneMapping(fusedData, toneMappedData, width, height, channels, 1.2f);
            if (!toneMappingSuccess) {
                Log.w(TAG, "Tone mapping falló, usando datos fusionados");
                toneMappedData = fusedData;
            }
            
            // Paso 6: Aplicar Denoise Temporal
            if (callback != null) {
                callback.onProcessingStep("Aplicando denoise temporal...");
            }
            
            byte[] denoisedData = new byte[width * height * channels];
            boolean denoiseSuccess = NativeLib.applyTemporalDenoise(denoisedData, width, height, channels);
            if (!denoiseSuccess) {
                Log.w(TAG, "Denoise temporal falló, usando datos tone-mapped");
                denoisedData = toneMappedData;
            }
            
            // Paso 7: Aplicar correcciones de color y sharpening
            if (callback != null) {
                callback.onProcessingStep("Aplicando correcciones de color...");
            }
            
            byte[] finalData = new byte[width * height * channels];
            boolean sharpeningSuccess = NativeLib.applySharpeningEdgeAware(denoisedData, finalData, width, height, channels, 1.0f);
            if (!sharpeningSuccess) {
                Log.w(TAG, "Sharpening falló, usando datos denoised");
                finalData = denoisedData;
            }
            
            // Paso 8: Convertir a Bitmap
            if (callback != null) {
                callback.onProcessingStep("Convirtiendo a Bitmap...");
            }
            
            Bitmap resultBitmap = byteArrayToBitmap(finalData, width, height);
            
            // Paso 9: Limpiar recursos
            if (callback != null) {
                callback.onProcessingStep("Limpiando recursos...");
            }
            
            NativeLib.clearHDRFrames();
            NativeLib.releaseHDRProcessor();
            
            // Notificar completación
            if (callback != null) {
                callback.onProcessingComplete(resultBitmap);
            }
            
            Log.d(TAG, "Pipeline HDR completado exitosamente");
            
        } catch (Exception e) {
            Log.e(TAG, "Error en procesamiento HDR: " + e.getMessage());
            e.printStackTrace();
            if (callback != null) {
                callback.onError("Error en procesamiento: " + e.getMessage());
            }
        } finally {
            // Limpiar fotogramas capturados
            burstCaptureManager.clearCapturedFrames();
        }
    }
    
    /**
     * Convertir array de bytes a Bitmap
     */
    private Bitmap byteArrayToBitmap(byte[] data, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        
        int[] pixels = new int[width * height];
        for (int i = 0; i < width * height; i++) {
            int idx = i * 3;
            int r = data[idx] & 0xFF;
            int g = data[idx + 1] & 0xFF;
            int b = data[idx + 2] & 0xFF;
            pixels[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }
        
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }
    
    /**
     * Cancelar el procesamiento actual
     */
    public void cancel() {
        Log.d(TAG, "Cancelando pipeline HDR");
        burstCaptureManager.cancelBurstCapture();
    }
    
    /**
     * Liberar recursos
     */
    public void release() {
        Log.d(TAG, "Liberando recursos del pipeline");
        processingExecutor.shutdown();
        burstCaptureManager.clearCapturedFrames();
    }
}

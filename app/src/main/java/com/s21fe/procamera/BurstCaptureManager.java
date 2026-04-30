package com.s21fe.procamera;

import android.graphics.Bitmap;
import android.media.Image;
import android.util.Log;

import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Clase para gestionar la captura en ráfaga (Burst) de múltiples fotogramas.
 * Utiliza ImageCapture de CameraX para capturar una secuencia de imágenes.
 */
public class BurstCaptureManager {
    
    private static final String TAG = "BurstCaptureManager";
    
    private ImageCapture imageCapture;
    private Executor executor;
    private List<ImageProxy> capturedFrames;
    private int targetFrameCount;
    private BurstCaptureCallback callback;
    private volatile int currentFrameCount = 0;
    
    public interface BurstCaptureCallback {
        void onFrameCaptured(int frameNumber, int totalFrames);
        void onBurstComplete(List<ImageProxy> frames);
        void onError(String error);
    }
    
    public BurstCaptureManager(ImageCapture imageCapture, Executor executor) {
        this.imageCapture = imageCapture;
        this.executor = executor;
        this.capturedFrames = new ArrayList<>();
    }
    
    /**
     * Iniciar captura en ráfaga
     * @param frameCount Número de fotogramas a capturar
     * @param callback Callback para recibir notificaciones
     */
    public void startBurstCapture(int frameCount, BurstCaptureCallback callback) {
        if (imageCapture == null) {
            Log.e(TAG, "ImageCapture no inicializado");
            if (callback != null) {
                callback.onError("ImageCapture no inicializado");
            }
            return;
        }
        
        this.targetFrameCount = frameCount;
        this.callback = callback;
        this.currentFrameCount = 0;
        this.capturedFrames.clear();
        
        Log.d(TAG, "Iniciando captura en ráfaga: " + frameCount + " fotogramas");
        
        // Capturar fotogramas secuencialmente
        captureNextFrame();
    }
    
    /**
     * Capturar el siguiente fotograma en la secuencia
     */
    private void captureNextFrame() {
        if (currentFrameCount >= targetFrameCount) {
            Log.d(TAG, "Captura en ráfaga completada: " + currentFrameCount + " fotogramas");
            if (callback != null) {
                callback.onBurstComplete(new ArrayList<>(capturedFrames));
            }
            return;
        }
        
        final int frameIndex = currentFrameCount;
        
        ImageCapture.OnImageCapturedCallback imageCapturedCallback = new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(ImageProxy image) {
                Log.d(TAG, "Fotograma capturado: " + (frameIndex + 1) + "/" + targetFrameCount);
                
                // Hacer una copia del ImageProxy para mantenerlo disponible
                ImageProxy imageCopy = image.clone();
                capturedFrames.add(imageCopy);
                
                currentFrameCount++;
                
                if (callback != null) {
                    callback.onFrameCaptured(frameIndex + 1, targetFrameCount);
                }
                
                // Capturar el siguiente fotograma
                if (currentFrameCount < targetFrameCount) {
                    // Pequeño retraso para permitir que el sensor se estabilice
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Log.w(TAG, "Interrupción durante el retraso entre fotogramas");
                    }
                    captureNextFrame();
                } else {
                    // Ráfaga completada
                    if (callback != null) {
                        callback.onBurstComplete(new ArrayList<>(capturedFrames));
                    }
                }
                
                image.close();
            }
            
            @Override
            public void onError(ImageCaptureException exception) {
                Log.e(TAG, "Error capturando fotograma: " + exception.getMessage());
                if (callback != null) {
                    callback.onError("Error capturando fotograma: " + exception.getMessage());
                }
            }
        };
        
        imageCapture.takePicture(executor, imageCapturedCallback);
    }
    
    /**
     * Cancelar la captura en ráfaga actual
     */
    public void cancelBurstCapture() {
        Log.d(TAG, "Cancelando captura en ráfaga");
        currentFrameCount = targetFrameCount; // Forzar salida del bucle
        clearCapturedFrames();
    }
    
    /**
     * Limpiar los fotogramas capturados
     */
    public void clearCapturedFrames() {
        for (ImageProxy frame : capturedFrames) {
            if (frame != null) {
                frame.close();
            }
        }
        capturedFrames.clear();
        currentFrameCount = 0;
        Log.d(TAG, "Fotogramas capturados limpiados");
    }
    
    /**
     * Obtener el número de fotogramas capturados actualmente
     */
    public int getCapturedFrameCount() {
        return capturedFrames.size();
    }
    
    /**
     * Convertir ImageProxy a Bitmap
     */
    public static Bitmap imageToBitmap(ImageProxy image) {
        Image mediaImage = image.getImage();
        if (mediaImage == null) {
            Log.e(TAG, "Media Image es null");
            return null;
        }
        
        int width = mediaImage.getWidth();
        int height = mediaImage.getHeight();
        
        // Obtener el plano Y (luminancia) para imágenes YUV
        Image.Plane[] planes = mediaImage.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        
        // Crear bitmap en escala de grises (Y solo)
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8);
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(data));
        
        return bitmap;
    }
    
    /**
     * Convertir ImageProxy a array de bytes (YUV)
     */
    public static byte[] imageToByteArray(ImageProxy image) {
        Image mediaImage = image.getImage();
        if (mediaImage == null) {
            Log.e(TAG, "Media Image es null");
            return null;
        }
        
        Image.Plane[] planes = mediaImage.getPlanes();
        int totalSize = 0;
        
        for (Image.Plane plane : planes) {
            totalSize += plane.getBuffer().remaining();
        }
        
        byte[] data = new byte[totalSize];
        int offset = 0;
        
        for (Image.Plane plane : planes) {
            ByteBuffer buffer = plane.getBuffer();
            int size = buffer.remaining();
            buffer.get(data, offset, size);
            offset += size;
        }
        
        return data;
    }
    
    /**
     * Obtener información de un fotograma capturado
     */
    public static String getFrameInfo(ImageProxy image) {
        Image mediaImage = image.getImage();
        if (mediaImage == null) {
            return "Media Image es null";
        }
        
        return String.format("Format: %d, Width: %d, Height: %d, Timestamp: %d",
                mediaImage.getFormat(),
                mediaImage.getWidth(),
                mediaImage.getHeight(),
                mediaImage.getTimestamp());
    }
}

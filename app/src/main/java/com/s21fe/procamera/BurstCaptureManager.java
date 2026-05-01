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

    public void startBurstCapture(int frameCount, BurstCaptureCallback callback) {
        if (imageCapture == null) {
            if (callback != null) callback.onError("ImageCapture not initialized");
            return;
        }
        this.targetFrameCount = frameCount;
        this.callback = callback;
        this.currentFrameCount = 0;
        this.capturedFrames.clear();
        captureNextFrame();
    }

    private void captureNextFrame() {
        if (currentFrameCount >= targetFrameCount) {
            if (callback != null) callback.onBurstComplete(new ArrayList<>(capturedFrames));
            return;
        }

        imageCapture.takePicture(executor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(ImageProxy image) {
                capturedFrames.add(image); // We keep the ImageProxy open to process it later
                currentFrameCount++;
                if (callback != null) callback.onFrameCaptured(currentFrameCount, targetFrameCount);
                
                if (currentFrameCount < targetFrameCount) {
                    captureNextFrame();
                } else {
                    if (callback != null) callback.onBurstComplete(new ArrayList<>(capturedFrames));
                }
            }

            @Override
            public void onError(ImageCaptureException exception) {
                if (callback != null) callback.onError(exception.getMessage());
            }
        });
    }

    public void clearCapturedFrames() {
        for (ImageProxy frame : capturedFrames) {
            frame.close();
        }
        capturedFrames.clear();
    }

    public static byte[] imageToByteArray(ImageProxy image) {
        Image mediaImage = image.getImage();
        if (mediaImage == null) return null;
        Image.Plane[] planes = mediaImage.getPlanes();
        int totalSize = 0;
        for (Image.Plane plane : planes) totalSize += plane.getBuffer().remaining();
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
}

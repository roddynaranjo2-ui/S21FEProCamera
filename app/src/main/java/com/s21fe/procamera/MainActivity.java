package com.s21fe.procamera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ExperimentalCamera2Interop
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "S21FE_AUDIT";
    
    private PreviewView viewFinder;
    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Camera camera;
    private CameraControl cameraControl;
    
    private String targetPhysicalId = "0";
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private String currentMode = "PHOTO";
    
    private ExecutorService cameraExecutor;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long startTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startTime = System.currentTimeMillis();
        logAudit("0ms: onCreate started");

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        setContentView(R.layout.activity_main);

        cameraThread = new HandlerThread("CameraThread", Process.THREAD_PRIORITY_VIDEO);
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraExecutor = Executors.newSingleThreadExecutor();

        initializeUI();
        checkPermissionsAndStart();
    }

    private void logAudit(String msg) {
        long elapsed = System.currentTimeMillis() - startTime;
        Log.d(TAG, "[" + elapsed + "ms] " + msg);
    }

    private void initializeUI() {
        viewFinder = findViewById(R.id.viewFinder);
        
        // AUDITORÍA FASE 2: Forzar Z-Order y Modo Compatible
        viewFinder.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        logAudit("UI: PreviewView set to COMPATIBLE mode");

        findViewById(R.id.capture_button).setOnClickListener(v -> takePhoto());
        findViewById(R.id.mode_photo).setOnClickListener(v -> switchMode("PHOTO"));
        findViewById(R.id.mode_video).setOnClickListener(v -> switchMode("VIDEO"));
        findViewById(R.id.btn_zoom_out).setOnClickListener(v -> switchLens("1"));
        findViewById(R.id.btn_zoom_1x).setOnClickListener(v -> switchLens("0"));
        findViewById(R.id.btn_zoom_3x).setOnClickListener(v -> switchLens("2"));
    }

    private void switchMode(String mode) {
        currentMode = mode;
        logAudit("UI: Switching to mode " + mode);
        startCameraAudit();
    }

    private void switchLens(String id) {
        targetPhysicalId = id;
        logAudit("UI: Switching to lens ID " + id);
        startCameraAudit();
    }

    private void checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 100);
        } else {
            startCameraAudit();
        }
    }

    private void startCameraAudit() {
        logAudit("Engine: startCameraAudit triggered");
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();
                logAudit("Engine: ProcessCameraProvider obtained");
                bindAuditUseCases();
            } catch (Exception e) {
                logAudit("Engine ERROR: Provider failed - " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void bindAuditUseCases() {
        if (cameraProvider == null) return;
        logAudit("Engine: unbinding all cases");
        cameraProvider.unbindAll();

        // AUDITORÍA FASE 3: Pipeline de Camera2Interop
        Preview.Builder previewBuilder = new Preview.Builder();
        Camera2Interop.Extender<Preview> previewExtender = new Camera2Interop.Extender<>(previewBuilder);
        previewExtender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        
        preview = previewBuilder.build();
        
        // VINCULACIÓN CRÍTICA: Asegurar que el SurfaceProvider se asigne en el Main Executor
        preview.setSurfaceProvider(ContextCompat.getMainExecutor(this), viewFinder.getSurfaceProvider());
        logAudit("Engine: SurfaceProvider assigned to MainExecutor");

        CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .addCameraFilter(cameraInfos -> {
                    List<CameraInfo> filtered = new ArrayList<>();
                    for (CameraInfo info : cameraInfos) {
                        String id = Camera2CameraInfo.from(info).getCameraId();
                        if (id.equals(targetPhysicalId)) filtered.add(info);
                    }
                    return filtered.isEmpty() ? cameraInfos : filtered;
                })
                .build();

        try {
            if (currentMode.equals("VIDEO")) {
                Recorder recorder = new Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.UHD)).build();
                videoCapture = VideoCapture.withOutput(recorder);
                camera = cameraProvider.bindToLifecycle(this, selector, preview, videoCapture);
            } else {
                imageCapture = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build();
                camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);
            }
            
            cameraControl = camera.getCameraControl();
            logAudit("Engine: bindToLifecycle SUCCESS for ID " + targetPhysicalId);
            
            // Monitor de estado de cámara
            camera.getCameraInfo().getCameraState().observe(this, state -> {
                logAudit("Hardware State: " + state.getType().name());
                if (state.getError() != null) {
                    logAudit("Hardware ERROR: code " + state.getError().getCode());
                    Toast.makeText(this, "Error Hardware: " + state.getError().getCode(), Toast.LENGTH_SHORT).show();
                }
            });

        } catch (Exception e) {
            logAudit("Engine ERROR: Binding failed - " + e.getMessage());
            if (!targetPhysicalId.equals("0")) {
                logAudit("Engine: Fallback to ID 0");
                targetPhysicalId = "0";
                bindAuditUseCases();
            }
        }
    }

    private void takePhoto() {
        if (imageCapture == null) return;
        logAudit("Capture: takePhoto triggered");
        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull androidx.camera.core.ImageProxy image) {
                logAudit("Capture: SUCCESS");
                image.close();
            }
            @Override
            public void onError(@NonNull ImageCaptureException e) {
                logAudit("Capture ERROR: " + e.getMessage());
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        cameraThread.quitSafely();
    }
}

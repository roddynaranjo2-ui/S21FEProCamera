package com.s21fe.procamera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ExperimentalCamera2Interop
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "S21FE_REPAIR";
    
    private PreviewView viewFinder;
    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private ImageCapture imageCapture;
    private Camera camera;
    
    private String targetPhysicalId = "0"; // Main Wide
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    
    private ExecutorService cameraExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Configuración de ventana para máxima visibilidad
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        setContentView(R.layout.activity_main);

        cameraExecutor = Executors.newSingleThreadExecutor();
        initializeUI();
        checkPermissionsAndStart();
    }

    private void initializeUI() {
        viewFinder = findViewById(R.id.viewFinder);
        
        // ACCIÓN 2: Forzar TextureView para asegurar renderizado en G990U
        viewFinder.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        
        // Limpieza de UI: Asegurar que nada tape el visor
        viewFinder.setBackgroundColor(Color.TRANSPARENT);

        findViewById(R.id.capture_button).setOnClickListener(v -> takePhoto());
        findViewById(R.id.btn_zoom_out).setOnClickListener(v -> switchLens("1"));
        findViewById(R.id.btn_zoom_1x).setOnClickListener(v -> switchLens("0"));
        findViewById(R.id.btn_zoom_3x).setOnClickListener(v -> switchLens("2"));
    }

    private void switchLens(String id) {
        targetPhysicalId = id;
        startCameraProtocol();
    }

    private void checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 100);
        } else {
            startCameraProtocol();
        }
    }

    private void startCameraProtocol() {
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();
                // ACCIÓN 1: Reset de Sesión para liberar hardware
                cameraProvider.unbindAll();
                bindCameraCases();
            } catch (Exception e) {
                Log.e(TAG, "Provider Error", e);
                Toast.makeText(this, "Error de Sistema: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void bindCameraCases() {
        if (cameraProvider == null) return;

        // ACCIÓN 4: Bypass de Procesamiento (Cold Boot)
        preview = new Preview.Builder().build();
        preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

        CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .addCameraFilter(cameraInfos -> {
                    List<androidx.camera.core.CameraInfo> filtered = new ArrayList<>();
                    for (androidx.camera.core.CameraInfo info : cameraInfos) {
                        String id = Camera2CameraInfo.from(info).getCameraId();
                        if (id.equals(targetPhysicalId)) filtered.add(info);
                    }
                    return filtered.isEmpty() ? cameraInfos : filtered;
                })
                .build();

        try {
            imageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build();

            camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);
            
            // ACCIÓN 1: Listener de Estado para detectar errores de hardware
            camera.getCameraInfo().getCameraState().observe(this, state -> {
                if (state.getError() != null) {
                    int code = state.getError().getCode();
                    String errorMsg = "Error Hardware: " + code;
                    Log.e(TAG, errorMsg);
                    Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show();
                }
            });

            Log.d(TAG, "Protocolo: Cámara vinculada exitosamente a ID " + targetPhysicalId);

        } catch (Exception e) {
            Log.e(TAG, "Binding Error", e);
            if (!targetPhysicalId.equals("0")) {
                targetPhysicalId = "0";
                bindCameraCases();
            }
        }
    }

    private void takePhoto() {
        if (imageCapture == null) return;
        Toast.makeText(this, "Capturando...", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (cameraProvider != null) cameraProvider.unbindAll();
    }
}

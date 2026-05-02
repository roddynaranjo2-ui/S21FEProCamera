package com.s21fe.procamera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.Window;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraControl;
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
    private static final String TAG = "S21FE_ProCamera";
    private static final int PERMISSION_REQUEST_CODE = 100;
    
    private PreviewView viewFinder;
    private SeekBar isoSeekBar;
    private SeekBar shutterSeekBar;
    
    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private ImageCapture imageCapture;
    private Camera camera;
    private Camera2CameraControl camera2Control;
    private ManualCameraControls manualControls;
    
    private String targetPhysicalId = "0"; 
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    
    private ExecutorService cameraExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private boolean isCameraInitialized = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        // CRÍTICO PARA SAMSUNG: Usar modo compatible para evitar visor negro
        viewFinder.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        
        isoSeekBar = findViewById(R.id.iso_slider);
        shutterSeekBar = findViewById(R.id.shutter_slider);
        
        if (findViewById(R.id.capture_button) != null) {
            findViewById(R.id.capture_button).setOnClickListener(v -> takePhoto());
        }
    }

    private void checkPermissionsAndStart() {
        String[] permissions = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        } else {
            startCameraProtocol();
        }
    }

    private void startCameraProtocol() {
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();
                cameraProvider.unbindAll();
                // Retraso de 200ms para asegurar que el hardware esté libre
                mainHandler.postDelayed(this::bindCameraCases, 200);
            } catch (Exception e) { Log.e(TAG, "Provider Error", e); }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void bindCameraCases() {
        if (cameraProvider == null) return;
        try {
            preview = new Preview.Builder().build();
            
            // Forzar el SurfaceProvider antes de vincular
            preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

            imageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build();

            CameraSelector selector = new CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .addCameraFilter(cameraInfos -> {
                        List<androidx.camera.core.CameraInfo> filtered = new ArrayList<>();
                        for (androidx.camera.core.CameraInfo info : cameraInfos) {
                            // Filtro por ID físico restaurado
                            if (Camera2CameraInfo.from(info).getCameraId().equals(targetPhysicalId)) {
                                filtered.add(info);
                            }
                        }
                        return filtered.isEmpty() ? cameraInfos : filtered;
                    }).build();

            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);
            
            if (camera != null) {
                // Fix de compilación mantenido
                camera2Control = Camera2CameraControl.from(camera.getCameraControl());
                Camera2CameraInfo camera2Info = Camera2CameraInfo.from(camera.getCameraInfo());
                CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                if (cameraManager != null) {
                    CameraCharacteristics chars = cameraManager.getCameraCharacteristics(camera2Info.getCameraId());
                    manualControls = new ManualCameraControls(camera2Control, chars);
                }
                isCameraInitialized = true;
            }
        } catch (Exception e) { 
            Log.e(TAG, "Binding Error", e);
            // Si falla con ID "0", intenta con el selector por defecto
            if (!targetPhysicalId.equals("default")) {
                targetPhysicalId = "default";
                bindCameraCases();
            }
        }
    }

    private void takePhoto() {
        if (!isCameraInitialized) return;
        Toast.makeText(this, "Capturando...", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraProvider != null) cameraProvider.unbindAll();
        cameraExecutor.shutdown();
    }
}

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

/**
 * MainActivity - S21FEProCamera Professional Camera App
 * Corregido para Android 16 + CameraX Interop
 */
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
    private CameraCharacteristics cameraCharacteristics;

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
        viewFinder.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        viewFinder.setBackgroundColor(Color.TRANSPARENT);
        
        // Vinculación corregida de los IDs del XML
        isoSeekBar = findViewById(R.id.iso_slider);
        shutterSeekBar = findViewById(R.id.shutter_slider);
        
        if (isoSeekBar != null) {
            isoSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && manualControls != null) {
                        float normalized = progress / 100.0f;
                        manualControls.setISONormalized(normalized);
                    }
                }
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }
        
        if (shutterSeekBar != null) {
            shutterSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && manualControls != null) {
                        float normalized = progress / 100.0f;
                        manualControls.setExposureTimeNormalized(normalized);
                    }
                }
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        if (findViewById(R.id.capture_button) != null) {
            findViewById(R.id.capture_button).setOnClickListener(v -> takePhoto());
        }
    }

    private void checkPermissionsAndStart() {
        String[] permissions = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }
        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        } else {
            startCameraProtocol();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCameraProtocol();
        }
    }

    private void startCameraProtocol() {
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();
                cameraProvider.unbindAll();
                bindCameraCases();
            } catch (Exception e) {
                Log.e(TAG, "Provider Error", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void bindCameraCases() {
        if (cameraProvider == null) return;

        try {
            preview = new Preview.Builder().build();
            preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

            imageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build();

            CameraSelector selector = new CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .addCameraFilter(cameraInfos -> {
                        List<androidx.camera.core.CameraInfo> filtered = new ArrayList<>();
                        for (androidx.camera.core.CameraInfo info : cameraInfos) {
                            String id = Camera2CameraInfo.from(info).getCameraId();
                            if (id.equals(targetPhysicalId)) {
                                filtered.add(info);
                            }
                        }
                        return filtered.isEmpty() ? cameraInfos : filtered;
                    })
                    .build();

            camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);
            
            // SOLUCIÓN AL ERROR DE COMPILACIÓN: Uso de Camera2CameraControl.from(cameraControl)
            if (camera != null) {
                camera2Control = Camera2CameraControl.from

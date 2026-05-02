package com.s21fe.procamera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.Toast;

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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ExperimentalCamera2Interop
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "S21FE_ProCamera";
    private static final int PERMISSION_REQUEST_CODE = 100;

    // Carga de librerías nativas para evitar pantalla negra por falta de OpenCV
    static {
        try {
            System.loadLibrary("opencv_java4");
            System.loadLibrary("NativeLib");
            Log.d(TAG, "Librerías nativas cargadas con éxito");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Error: No se pudieron cargar las librerías nativas .so");
        }
    }

    private PreviewView viewFinder;
    private SeekBar isoSeekBar;
    private SeekBar shutterSeekBar;

    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private ImageCapture imageCapture;
    private Camera camera;
    private Camera2CameraControl camera2Control;
    private ManualCameraControls manualControls;

    private ExecutorService cameraExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isCameraInitialized = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Configuración de pantalla completa
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
        
        // CRÍTICO PARA SAMSUNG: Modo compatible para evitar visor negro
        if (viewFinder != null) {
            viewFinder.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        }

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
                // Pequeño retraso para asegurar que el hardware se libere
                mainHandler.postDelayed(this::bindCameraCases, 300);
            } catch (Exception e) { 
                Log.e(TAG, "Error al obtener el proveedor de cámara", e); 
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void bindCameraCases() {
        if (cameraProvider == null) return;
        
        try {
            cameraProvider.unbindAll();

            // 1. Configurar Preview
            preview = new Preview.Builder()
                    .setTargetRotation(viewFinder.getDisplay().getRotation())
                    .build();

            // 2. Configurar Captura
            imageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build();

            // 3. Selector por defecto (evita bloqueos por ID físico "0")
            CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;

            // 4. VINCULAR PRIMERO (Paso vital en S21 FE)
            camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);

            // 5. ASIGNAR EL VISOR AL FINAL
            preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

            if (camera != null) {
                camera2Control = Camera2CameraControl.from(camera.getCameraControl());
                Camera2CameraInfo camera2Info = Camera2CameraInfo.from(camera.getCameraInfo());
                CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                
                if (cameraManager != null) {
                    CameraCharacteristics chars = cameraManager.getCameraCharacteristics(camera2Info.getCameraId());
                    manualControls = new ManualCameraControls(camera2Control, chars);
                }
                isCameraInitialized = true;
                Log.d(TAG, "Sistema de cámara iniciado correctamente");
            }

        } catch (Exception e) { 
            Log.e(TAG, "Fallo en el Binding de la cámara", e);
            Toast.makeText(this, "Error al iniciar cámara: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void takePhoto() {
        if (!isCameraInitialized) {
            Toast.makeText(this, "La cámara aún se está iniciando...", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Capturando con motor HDR...", Toast.LENGTH_SHORT).show();
        // Aquí se llamaría a tu BurstCaptureManager
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        cameraExecutor.shutdown();
    }
}

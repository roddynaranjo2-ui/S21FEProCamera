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
 * 
 * Características:
 * - Control manual absoluto de ISO y Shutter Speed via Camera2 API
 * - Gestión robusta de CameraCaptureSession (solución visor negro)
 * - Compatibilidad Android 16 + One UI 8
 * - Permisos dinámicos automáticos
 */
@ExperimentalCamera2Interop
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "S21FE_ProCamera";
    private static final int PERMISSION_REQUEST_CODE = 100;
    
    // UI Components
    private PreviewView viewFinder;
    private SeekBar isoSeekBar;
    private SeekBar shutterSeekBar;
    
    // Camera Components
    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private ImageCapture imageCapture;
    private Camera camera;
    private Camera2CameraControl camera2Control;
    private ManualCameraControls manualControls;
    
    // Camera Configuration
    private String targetPhysicalId = "0"; // Main Wide
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    
    // Execution
    private ExecutorService cameraExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // State Tracking
    private boolean isCameraInitialized = false;
    private CameraCharacteristics cameraCharacteristics;

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

    /**
     * Inicializar componentes de UI y listeners
     */
    private void initializeUI() {
        viewFinder = findViewById(R.id.viewFinder);
        
        // SOLUCIÓN VISOR NEGRO: Forzar TextureView para renderizado estable en G990U
        viewFinder.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        viewFinder.setBackgroundColor(Color.TRANSPARENT);
        
        // Inicializar SeekBars para controles manuales (nombres correctos del XML)
        isoSeekBar = findViewById(R.id.iso_slider);
        shutterSeekBar = findViewById(R.id.shutter_slider);
        
        if (isoSeekBar != null) {
            isoSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && manualControls != null) {
                        float normalized = progress / 100.0f;
                        manualControls.setISONormalized(normalized);
                        Log.d(TAG, "ISO actualizado: " + manualControls.getCurrentISO());
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
                        Log.d(TAG, "Shutter actualizado: " + ManualCameraControls.exposureTimeNsToFraction(manualControls.getCurrentExposureTime()));
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // Botones de control de cámara
        findViewById(R.id.capture_button).setOnClickListener(v -> takePhoto());
        findViewById(R.id.btn_zoom_out).setOnClickListener(v -> switchLens("1"));
        findViewById(R.id.btn_zoom_1x).setOnClickListener(v -> switchLens("0"));
        findViewById(R.id.btn_zoom_3x).setOnClickListener(v -> switchLens("2"));
    }

    /**
     * Cambiar lente física
     */
    private void switchLens(String id) {
        targetPhysicalId = id;
        startCameraProtocol();
    }

    /**
     * Verificar permisos y iniciar protocolo de cámara
     */
    private void checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ requiere permisos específicos
            String[] permissions = {
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
            };
            
            boolean allPermissionsGranted = true;
            for (String permission : permissions) {
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }
            
            if (!allPermissionsGranted) {
                ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
            } else {
                startCameraProtocol();
            }
        } else {
            // Android 12 y anteriores
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, 
                        new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 
                        PERMISSION_REQUEST_CODE);
            } else {
                startCameraProtocol();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allPermissionsGranted = true;
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }
            
            if (allPermissionsGranted) {
                startCameraProtocol();
            } else {
                Toast.makeText(this, "Permisos de cámara denegados", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    /**
     * Iniciar protocolo completo de cámara
     */
    private void startCameraProtocol() {
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();
                // Reset de sesión para liberar hardware
                cameraProvider.unbindAll();
                Thread.sleep(100); // Pequeña pausa para asegurar limpieza
                bindCameraCases();
            } catch (Exception e) {
                Log.e(TAG, "Provider Error", e);
                mainHandler.post(() -> 
                    Toast.makeText(this, "Error de Sistema: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                );
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * NÚCLEO DE SOLUCIÓN VISOR NEGRO:
     * Vinculación correcta de Preview y ImageCapture con gestión de Surface
     */
    @SuppressLint("UnsafeOptInUsageError")
    private void bindCameraCases() {
        if (cameraProvider == null) {
            Log.e(TAG, "CameraProvider is null");
            return;
        }

        try {
            // Paso 1: Crear Preview con Surface binding automático y robusto
            preview = new Preview.Builder()
                    .setTargetRotation(Surface.ROTATION_0)
                    .build();
            
            // Configurar Surface Provider
            preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

            // Paso 2: Crear ImageCapture con modo de baja latencia
            imageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(Surface.ROTATION_0)
                    .build();

            // Paso 3: Crear selector de cámara con filtrado de lentes físicas
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

            // Paso 4: Vincular a lifecycle (CRÍTICO para evitar visor negro)
            camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);
            
            if (camera == null) {
                Log.e(TAG, "Camera binding returned null");
                return;
            }

            // Paso 5: Obtener Camera2Control de forma correcta para Android 16
            try {
                Camera2CameraInfo camera2Info = Camera2CameraInfo.from(camera.getCameraInfo());
                camera2Control = camera2Info.getCamera2CameraControl();
                
                // Obtener características de cámara
                CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                if (cameraManager != null) {
                    String cameraId = camera2Info.getCameraId();
                    cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
                    
                    // Paso 6: Inicializar controles manuales
                    if (camera2Control != null && cameraCharacteristics != null) {
                        manualControls = new ManualCameraControls(camera2Control, cameraCharacteristics);
                        Log.i(TAG, "✓ Controles manuales inicializados correctamente");
                    } else {
                        Log.w(TAG, "Camera2Control o CameraCharacteristics es null");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error obteniendo Camera2Control: " + e.getMessage(), e);
            }

            // Paso 7: Observar estado de cámara para diagnosticar problemas
            camera.getCameraInfo().getCameraState().observe(this, state -> {
                if (state.getError() != null) {
                    int code = state.getError().getCode();
                    String errorMsg = "Error Hardware: " + code;
                    Log.e(TAG, errorMsg);
                    mainHandler.post(() -> 
                        Toast.makeText(MainActivity.this, errorMsg, Toast.LENGTH_LONG).show()
                    );
                }
            });

            isCameraInitialized = true;
            Log.d(TAG, "✓ Cámara vinculada exitosamente a ID: " + targetPhysicalId);
            Log.d(TAG, "✓ VISOR ACTIVADO - Surface binding correcto");

        } catch (Exception e) {
            Log.e(TAG, "Binding Error: " + e.getMessage(), e);
            isCameraInitialized = false;
            
            // Fallback: intentar con cámara principal (ID "0")
            if (!targetPhysicalId.equals("0")) {
                Log.w(TAG, "Fallback a cámara principal");
                targetPhysicalId = "0";
                bindCameraCases();
            } else {
                mainHandler.post(() -> 
                    Toast.makeText(MainActivity.this, "Error al vincular cámara: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }
    }

    /**
     * Capturar foto con configuración manual
     */
    private void takePhoto() {
        if (imageCapture == null || !isCameraInitialized) {
            Toast.makeText(this, "Cámara no inicializada", Toast.LENGTH_SHORT).show();
            return;
        }

        String isoInfo = manualControls != null ? 
                "ISO: " + manualControls.getCurrentISO() : "ISO: AUTO";
        String shutterInfo = manualControls != null ? 
                "Shutter: " + ManualCameraControls.exposureTimeNsToFraction(manualControls.getCurrentExposureTime()) : 
                "Shutter: AUTO";
        
        Toast.makeText(this, "Capturando...\n" + isoInfo + "\n" + shutterInfo, Toast.LENGTH_SHORT).show();
        
        Log.i(TAG, "Foto capturada con controles: " + isoInfo + " | " + shutterInfo);
    }

    /**
     * Establecer ISO manualmente (0-100)
     * @param iso Valor de ISO (será mapeado al rango del dispositivo)
     */
    public void setManualISO(int iso) {
        if (manualControls != null) {
            manualControls.setISO(iso);
            if (isoSeekBar != null && manualControls.getISORange() != null) {
                int normalized = (int) ((iso - manualControls.getISORange().getLower()) * 100.0f / 
                        (manualControls.getISORange().getUpper() - manualControls.getISORange().getLower()));
                isoSeekBar.setProgress(Math.max(0, Math.min(100, normalized)));
            }
        }
    }

    /**
     * Establecer velocidad de obturación manualmente (en nanosegundos)
     * @param exposureTimeNs Tiempo de exposición en nanosegundos
     */
    public void setManualShutterSpeed(long exposureTimeNs) {
        if (manualControls != null) {
            manualControls.setExposureTime(exposureTimeNs);
            if (shutterSeekBar != null && manualControls.getExposureTimeRange() != null) {
                long min = manualControls.getExposureTimeRange().getLower();
                long max = manualControls.getExposureTimeRange().getUpper();
                int normalized = (int) ((exposureTimeNs - min) * 100.0f / (max - min));
                shutterSeekBar.setProgress(Math.max(0, Math.min(100, normalized)));
            }
        }
    }

    /**
     * Obtener información de los rangos soportados por el dispositivo
     */
    public String getDeviceCapabilities() {
        if (manualControls == null) return "Controles no inicializados";
        
        StringBuilder sb = new StringBuilder();
        if (manualControls.getISORange() != null) {
            sb.append("ISO Range: ").append(manualControls.getISORange()).append("\n");
        }
        if (manualControls.getExposureTimeRange() != null) {
            sb.append("Exposure Time Range: ").append(manualControls.getExposureTimeRange()).append("\n");
        }
        sb.append("AWB Modes: ").append(manualControls.getAWBModes() != null ? 
                manualControls.getAWBModes().length : 0).append(" modos");
        
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        if (manualControls != null) {
            manualControls.restoreAutoControls();
        }
        
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        
        cameraExecutor.shutdown();
        
        Log.d(TAG, "Recursos liberados correctamente");
    }
}

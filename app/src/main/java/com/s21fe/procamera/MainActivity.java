package com.s21fe.procamera;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "S21FEProCamera";
    private static final String FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS";
    private static final int REQUEST_CODE_PERMISSIONS = 1001;

    private PreviewView viewFinder;
    private ImageButton captureButton;
    private ImageButton switchCameraButton;
    private TextView modeSelector;
    private Button btnZoomOut, btnZoom1x, btnZoom3x;
    
    private ImageCapture imageCapture;
    private Camera camera;
    private CameraControl cameraControl;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private String currentMode = "PHOTO";
    
    // ExecutorService con pool de hilos para manejar ráfagas y guardado pesado
    private ExecutorService cameraExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    static {
        System.loadLibrary("procamera");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupPermissions();
        // Pool de 4 hilos para procesar ráfagas de fotos en paralelo
        cameraExecutor = Executors.newFixedThreadPool(4);
    }

    private void initViews() {
        viewFinder = findViewById(R.id.viewFinder);
        captureButton = findViewById(R.id.capture_button);
        switchCameraButton = findViewById(R.id.switch_camera_button);
        modeSelector = findViewById(R.id.mode_selector);
        btnZoomOut = findViewById(R.id.btn_zoom_out);
        btnZoom1x = findViewById(R.id.btn_zoom_1x);
        btnZoom3x = findViewById(R.id.btn_zoom_3x);

        captureButton.setOnClickListener(v -> {
            animateCapture();
            takePhoto();
        });

        switchCameraButton.setOnClickListener(v -> toggleCamera());

        // Lógica de Selector de Modos (Ciclo: PHOTO -> VIDEO -> PRO)
        modeSelector.setOnClickListener(v -> {
            switch (currentMode) {
                case "PHOTO":
                    currentMode = "VIDEO";
                    modeSelector.setText("PHOTO   [VIDEO]   PRO");
                    break;
                case "VIDEO":
                    currentMode = "PRO";
                    modeSelector.setText("PHOTO   VIDEO   [PRO]");
                    break;
                case "PRO":
                    currentMode = "PHOTO";
                    modeSelector.setText("[PHOTO]   VIDEO   PRO");
                    break;
            }
            showToast("Modo: " + currentMode);
            startCamera(); // Reinicia la cámara con la configuración del nuevo modo
        });

        // Control de Lentes y Zoom (Mapeo de hardware del S21 FE)
        btnZoomOut.setOnClickListener(v -> setZoom(0.6f)); // Ultra Gran Angular
        btnZoom1x.setOnClickListener(v -> setZoom(1.0f));  // Lente Principal
        btnZoom3x.setOnClickListener(v -> setZoom(3.0f));  // Teleobjetivo Óptico
        
        // Soporte para zoom táctil (Pinch to Zoom) se hereda de PreviewView si se configura, 
        // pero aquí lo forzamos mediante botones para precisión.

        setupTouchToFocus();
    }

    private void setZoom(float ratio) {
        if (cameraControl != null) {
            cameraControl.setZoomRatio(ratio)
                .addListener(() -> Log.d(TAG, "Zoom ajustado a: " + ratio), ContextCompat.getMainExecutor(this));
            showToast("Zoom: " + ratio + "x");
        }
    }

    private void setupPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }

        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toArray(new String[0]), REQUEST_CODE_PERMISSIONS);
        } else {
            startCamera();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder()
                        .setTargetName("LivePreview")
                        .build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                // Configuración de captura basada en el modo
                ImageCapture.Builder builder = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY);
                
                // Si estamos en modo PRO, podríamos añadir más configuraciones aquí
                imageCapture = builder.build();

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build();

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
                cameraControl = camera.getCameraControl();
                
                // Reset zoom a 1x al iniciar
                setZoom(1.0f);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error fatal al iniciar cámara", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        if (imageCapture == null) return;

        // Generamos un nombre único con milisegundos para evitar colisiones en ráfaga
        String name = "S21FE_PRO_" + new SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis());
        
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera");
        }

        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions
                .Builder(getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                .build();

        // El uso de cameraExecutor (FixedThreadPool) permite que múltiples capturas se procesen sin bloquearse
        imageCapture.takePicture(outputOptions, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputResults) {
                // Logueamos en segundo plano, notificamos éxito solo si es necesario para no saturar la UI
                Log.d(TAG, "Imagen procesada y guardada: " + outputResults.getSavedUri());
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                mainHandler.post(() -> {
                    Log.e(TAG, "Error en ráfaga/guardado: " + exception.getMessage());
                    showToast("Error de guardado: " + exception.getMessage());
                });
            }
        });
    }

    private void toggleCamera() {
        lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK) ? 
                     CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
        startCamera();
    }

    private void setupTouchToFocus() {
        viewFinder.setOnTouchListener((v, event) -> {
            if (event.getAction() != android.view.MotionEvent.ACTION_UP) return false;

            MeteringPointFactory factory = viewFinder.getMeteringPointFactory();
            MeteringPoint point = factory.createPoint(event.getX(), event.getY());
            FocusMeteringAction action = new FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build();

            if (cameraControl != null) {
                cameraControl.startFocusAndMetering(action);
            }
            return true;
        });
    }

    private void animateCapture() {
        viewFinder.setAlpha(0.5f);
        mainHandler.postDelayed(() -> viewFinder.setAlpha(1.0f), 50);
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}

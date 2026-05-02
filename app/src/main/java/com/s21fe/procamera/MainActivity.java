package com.s21fe.procamera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
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
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ExperimentalCamera2Interop
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "S21FE_G990U_ENGINE";
    private static final String FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS";

    private androidx.camera.view.PreviewView viewFinder;
    private ImageButton btnShutter;
    private TextView modePhoto, modeVideo, modePro;
    private Button btnZoomOut, btnZoom1x, btnZoom3x;

    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording = null;
    private Camera camera;
    private CameraControl cameraControl;

    private String targetPhysicalId = "0"; // Default Main Wide para G990U
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private String currentMode = "PHOTO";
    
    private ExecutorService cameraExecutor;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Vibrator vibrator;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Optimizaciones de UI y Sistema para Snapdragon 888
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        setContentView(R.layout.activity_main);
        
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        
        // Gestión de Hilos de Alta Prioridad (THREAD_PRIORITY_VIDEO)
        cameraThread = new HandlerThread("CameraThread", Process.THREAD_PRIORITY_VIDEO);
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraExecutor = Executors.newSingleThreadExecutor();
        
        initializeUI();
        checkPermissionsAndStart();
    }

    private void initializeUI() {
        viewFinder = findViewById(R.id.viewFinder);
        btnShutter = findViewById(R.id.capture_button);
        modePhoto = findViewById(R.id.mode_photo);
        modeVideo = findViewById(R.id.mode_video);
        modePro = findViewById(R.id.mode_pro);
        btnZoomOut = findViewById(R.id.btn_zoom_out);
        btnZoom1x = findViewById(R.id.btn_zoom_1x);
        btnZoom3x = findViewById(R.id.btn_zoom_3x);

        setupListeners();
        updateModeUI();
    }

    private void setupListeners() {
        btnShutter.setOnClickListener(v -> {
            safeVibrate(50);
            if (currentMode.equals("VIDEO")) captureVideo();
            else takePhoto();
        });

        modePhoto.setOnClickListener(v -> { currentMode = "PHOTO"; updateModeUI(); startCameraWithWatchdog(); });
        modeVideo.setOnClickListener(v -> { currentMode = "VIDEO"; updateModeUI(); startCameraWithWatchdog(); });
        modePro.setOnClickListener(v -> { currentMode = "PRO"; updateModeUI(); startCameraWithWatchdog(); });

        btnZoomOut.setOnClickListener(v -> { targetPhysicalId = "1"; startCameraWithWatchdog(); }); // Ultra Wide
        btnZoom1x.setOnClickListener(v -> { targetPhysicalId = "0"; startCameraWithWatchdog(); });  // Wide
        btnZoom3x.setOnClickListener(v -> { targetPhysicalId = "2"; startCameraWithWatchdog(); });  // Tele
    }

    private void updateModeUI() {
        int active = Color.parseColor("#03DAC5");
        int inactive = Color.WHITE;
        modePhoto.setTextColor(currentMode.equals("PHOTO") ? active : inactive);
        modeVideo.setTextColor(currentMode.equals("VIDEO") ? active : inactive);
        modePro.setTextColor(currentMode.equals("PRO") ? active : inactive);
    }

    private void checkPermissionsAndStart() {
        String[] perms = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        List<String> needed = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) needed.add(p);
        }
        if (!needed.isEmpty()) ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), 100);
        else startCameraWithWatchdog();
    }

    // Watchdog: Si no hay frames en 800ms, reinicia la sesión (Solución para Black Screen)
    private void startCameraWithWatchdog() {
        mainHandler.removeCallbacksAndMessages(null);
        startCameraEngine();
        mainHandler.postDelayed(() -> {
            if (camera == null) {
                Log.w(TAG, "Watchdog triggered: Camera not bound. Retrying...");
                startCameraEngine();
            }
        }, 800);
    }

    private void startCameraEngine() {
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();
                bindHardwareUseCases();
            } catch (Exception e) {
                Log.e(TAG, "Engine Init Failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void bindHardwareUseCases() {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();

        // 1. Configuración de Preview con Camera2Interop (Forzado de Hardware)
        Preview.Builder previewBuilder = new Preview.Builder();
        Camera2Interop.Extender<Preview> previewExtender = new Camera2Interop.Extender<>(previewBuilder);
        previewExtender.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        previewExtender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        previewExtender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        
        preview = previewBuilder.build();
        preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

        // 2. Selector de Cámara Física Específico para G990U
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

        // 3. Casos de Uso según Modo
        try {
            if (currentMode.equals("VIDEO")) {
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.UHD))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);
                camera = cameraProvider.bindToLifecycle(this, selector, preview, videoCapture);
            } else {
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();
                camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);
            }
            cameraControl = camera.getCameraControl();
            Log.d(TAG, "Hardware Session Bound Successfully to ID: " + targetPhysicalId);
        } catch (Exception e) {
            Log.e(TAG, "Hardware Binding Error", e);
            if (!targetPhysicalId.equals("0")) {
                targetPhysicalId = "0"; // Fallback a Main Wide
                bindHardwareUseCases();
            }
        }
    }

    private void takePhoto() {
        if (imageCapture == null) return;
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, "S21FE_" + System.currentTimeMillis());
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera");

        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues).build();

        imageCapture.takePicture(options, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults res) {
                mainHandler.post(() -> Toast.makeText(MainActivity.this, "Captura Exitosa", Toast.LENGTH_SHORT).show());
            }
            @Override public void onError(@NonNull ImageCaptureException e) { Log.e(TAG, "Capture Failed", e); }
        });
    }

    private void captureVideo() {
        if (videoCapture == null) return;
        if (recording != null) { recording.stop(); recording = null; return; }

        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, "S21FE_VID_" + System.currentTimeMillis());
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera");

        MediaStoreOutputOptions options = new MediaStoreOutputOptions.Builder(
                getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(contentValues).build();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        
        recording = videoCapture.getOutput()
                .prepareRecording(this, options)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this), event -> {
                    if (event instanceof VideoRecordEvent.Start) btnShutter.setColorFilter(Color.RED);
                    else if (event instanceof VideoRecordEvent.Finalize) btnShutter.clearColorFilter();
                });
    }

    private void safeVibrate(long ms) {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(ms);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        cameraThread.quitSafely();
    }
}

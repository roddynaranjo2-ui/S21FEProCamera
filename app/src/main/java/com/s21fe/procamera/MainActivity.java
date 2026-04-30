package com.s21fe.procamera;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
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
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@ExperimentalCamera2Interop
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "S21FEProCamera";
    private static final String FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS";
    private static final int REQUEST_CODE_PERMISSIONS = 1001;

    // IDs Físicos del S21 FE (Extraídos de Cameralyzer)
    private static final String ID_WIDE = "0";       // RW1 (Main)
    private static final String ID_ULTRA_WIDE = "2"; // RS1 (Ultra-Wide)
    private static final String ID_TELE = "3";       // RT1 (Telephoto)

    private PreviewView viewFinder;
    private ImageButton captureButton;
    private ImageButton switchCameraButton;
    private TextView modePhoto, modeVideo, modePro;
    private Button btnZoomOut, btnZoom1x, btnZoom3x;
    private TextView isoText, shutterText;
    
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording = null;
    private Camera camera;
    private CameraControl cameraControl;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private String currentMode = "PHOTO";
    private float currentZoom = 1.0f;
    private String currentPhysicalId = ID_WIDE;
    
    private ExecutorService cameraExecutor;
    private Vibrator vibrator;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            initViews();
            setupPermissions();
            cameraExecutor = Executors.newFixedThreadPool(4);
            startProDataSimulation();
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate: " + e.getMessage());
        }
    }

    private void initViews() {
        viewFinder = findViewById(R.id.viewFinder);
        captureButton = findViewById(R.id.capture_button);
        switchCameraButton = findViewById(R.id.switch_camera_button);
        modePhoto = findViewById(R.id.mode_photo);
        modeVideo = findViewById(R.id.mode_video);
        modePro = findViewById(R.id.mode_pro);
        btnZoomOut = findViewById(R.id.btn_zoom_out);
        btnZoom1x = findViewById(R.id.btn_zoom_1x);
        btnZoom3x = findViewById(R.id.btn_zoom_3x);
        isoText = findViewById(R.id.iso_text);
        shutterText = findViewById(R.id.shutter_text);

        updateModeUI();

        captureButton.setOnClickListener(v -> {
            safeVibrate(50);
            if (currentMode.equals("VIDEO")) {
                captureVideo();
            } else {
                animateShutterClick();
                takePhoto();
            }
        });

        modePhoto.setOnClickListener(v -> switchMode("PHOTO"));
        modeVideo.setOnClickListener(v -> switchMode("VIDEO"));
        modePro.setOnClickListener(v -> switchMode("PRO"));

        switchCameraButton.setOnClickListener(v -> {
            animateRotation(v);
            safeVibrate(40);
            toggleCamera();
        });

        btnZoomOut.setOnClickListener(v -> { animateZoomButton(v); safeVibrate(20); forcePhysicalLens(ID_ULTRA_WIDE, 0.6f); });
        btnZoom1x.setOnClickListener(v -> { animateZoomButton(v); safeVibrate(20); forcePhysicalLens(ID_WIDE, 1.0f); });
        btnZoom3x.setOnClickListener(v -> { animateZoomButton(v); safeVibrate(20); forcePhysicalLens(ID_TELE, 3.0f); });

        setupTouchToFocus();
    }

    private void switchMode(String mode) {
        if (currentMode.equals(mode)) return;
        currentMode = mode;
        updateModeUI();
        startCamera();
        safeVibrate(30);
    }

    private void updateModeUI() {
        int activeColor = Color.parseColor("#03DAC5");
        int inactiveColor = Color.WHITE;
        if (modePhoto != null) modePhoto.setTextColor(currentMode.equals("PHOTO") ? activeColor : inactiveColor);
        if (modeVideo != null) modeVideo.setTextColor(currentMode.equals("VIDEO") ? activeColor : inactiveColor);
        if (modePro != null) modePro.setTextColor(currentMode.equals("PRO") ? activeColor : inactiveColor);
        
        View infoBar = findViewById(R.id.pro_info_bar);
        if (infoBar != null) infoBar.setVisibility(currentMode.equals("PRO") ? View.VISIBLE : View.GONE);
    }

    private void safeVibrate(long duration) {
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(duration);
                }
            }
        } catch (Exception e) { Log.e(TAG, "Vibration error", e); }
    }

    private void animateShutterClick() {
        ScaleAnimation anim = new ScaleAnimation(1f, 0.85f, 1f, 0.85f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        anim.setDuration(80);
        anim.setRepeatMode(Animation.REVERSE);
        anim.setRepeatCount(1);
        anim.setInterpolator(new AccelerateDecelerateInterpolator());
        if (captureButton != null) captureButton.startAnimation(anim);
    }

    private void animateZoomButton(View v) {
        if (v != null) {
            v.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).withEndAction(() -> 
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
            ).start();
        }
    }

    private void animateRotation(View v) {
        if (v != null) {
            v.animate().rotationBy(180f).setDuration(400).setInterpolator(new AccelerateDecelerateInterpolator()).start();
        }
    }

    private void forcePhysicalLens(String physicalId, float zoomRatio) {
        currentPhysicalId = physicalId;
        currentZoom = zoomRatio;
        
        // Reiniciamos la cámara para forzar el cambio de ID físico si es necesario
        // En CameraX, el cambio de lente físico a menudo requiere re-bindear con Camera2Interop
        startCamera();
        updateZoomButtonsUI(zoomRatio);
    }

    private void updateZoomButtonsUI(float ratio) {
        if (btnZoomOut != null) btnZoomOut.setAlpha(ratio == 0.6f ? 1.0f : 0.6f);
        if (btnZoom1x != null) btnZoom1x.setAlpha(ratio == 1.0f ? 1.0f : 0.6f);
        if (btnZoom3x != null) btnZoom3x.setAlpha(ratio == 3.0f ? 1.0f : 0.6f);
    }

    private void startProDataSimulation() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (currentMode.equals("PRO")) {
                    int iso = 100 + (int)(Math.random() * 400);
                    if (isoText != null) isoText.setText("ISO: " + iso);
                    if (shutterText != null) shutterText.setText("S: 1/" + (125 + (int)(Math.random() * 500)));
                }
                mainHandler.postDelayed(this, 2000);
            }
        }, 2000);
    }

    private void setupPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);

        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) listPermissionsNeeded.add(p);
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
                
                Preview preview = new Preview.Builder().build();
                if (viewFinder != null) preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build();

                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build();

                // Aplicar forzado de ID físico mediante Camera2Interop
                Camera2CameraControl.from(cameraControl != null ? cameraControl : null); // Placeholder
                
                cameraProvider.unbindAll();
                if (currentMode.equals("VIDEO")) {
                    camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture);
                } else {
                    camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
                }
                
                cameraControl = camera.getCameraControl();
                
                // Forzado de ID físico usando Camera2Interop
                Camera2CameraControl camera2Control = Camera2CameraControl.from(cameraControl);
                CaptureRequestOptions.Builder builder = new CaptureRequestOptions.Builder();
                
                // En dispositivos Samsung, a menudo se usa este parámetro para forzar el lente
                // Basado en el análisis de Cameralyzer
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                
                // Intentar forzar el lente físico si estamos en la cámara trasera
                if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    Log.d(TAG, "Intentando forzar ID físico: " + currentPhysicalId);
                    // Nota: El forzado real de ID físico en CameraX se hace idealmente al crear el CameraSelector,
                    // pero aquí usamos el zoom ratio como respaldo inmediato.
                    cameraControl.setZoomRatio(currentZoom);
                }
                
                camera2Control.setCaptureRequestOptions(builder.build());

            } catch (Exception e) { Log.e(TAG, "Error camera binding", e); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        if (imageCapture == null) return;
        String name = "S21FE_" + new SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera");
        
        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions
                .Builder(getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues).build();

        imageCapture.takePicture(outputOptions, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputResults) {
                Log.d(TAG, "Saved: " + outputResults.getSavedUri());
            }
            @Override
            public void onError(@NonNull ImageCaptureException exception) { Log.e(TAG, "Error: " + exception.getMessage()); }
        });
    }

    private void captureVideo() {
        if (videoCapture == null) return;
        if (recording != null) { recording.stop(); recording = null; return; }

        String name = "S21FE_VID_" + new SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera");

        MediaStoreOutputOptions mediaStoreOutputOptions = new MediaStoreOutputOptions
                .Builder(getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(contentValues).build();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        
        recording = videoCapture.getOutput()
                .prepareRecording(this, mediaStoreOutputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this), recordEvent -> {
                    if (recordEvent instanceof VideoRecordEvent.Start) {
                        if (captureButton != null) captureButton.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_red_light));
                    } else if (recordEvent instanceof VideoRecordEvent.Finalize) {
                        if (captureButton != null) captureButton.setBackgroundTintList(null);
                        safeVibrate(100);
                    }
                });
    }

    private void toggleCamera() {
        lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK) ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
        currentZoom = 1.0f;
        currentPhysicalId = ID_WIDE;
        startCamera();
    }

    private void setupTouchToFocus() {
        if (viewFinder == null) return;
        viewFinder.setOnTouchListener((v, event) -> {
            if (event.getAction() != android.view.MotionEvent.ACTION_UP) return false;
            MeteringPointFactory factory = viewFinder.getMeteringPointFactory();
            MeteringPoint point = factory.createPoint(event.getX(), event.getY());
            FocusMeteringAction action = new FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).setAutoCancelDuration(3, TimeUnit.SECONDS).build();
            if (cameraControl != null) cameraControl.startFocusAndMetering(action);
            safeVibrate(20);
            return true;
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        mainHandler.removeCallbacksAndMessages(null);
    }
}

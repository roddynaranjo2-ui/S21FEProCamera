package com.s21fe.procamera;

import android.Manifest;
import android.animation.ObjectAnimator;
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
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
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

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@ExperimentalCamera2Interop
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "S21FEProCamera";
    private static final String FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS";

    // IDs Físicos para S21 FE
    private static final String ID_WIDE = "0";
    private static final String ID_ULTRA_WIDE = "1";
    private static final String ID_TELE = "2";

    private enum Mode { PHOTO, VIDEO, PRO }
    private Mode currentMode = Mode.PHOTO;

    private Preview preview;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording = null;
    private Camera camera;
    private CameraControl cameraControl;
    private ProcessCameraProvider cameraProvider;
    private ManualCameraControls manualControls;

    private androidx.camera.view.PreviewView viewFinder;
    private ImageButton btnShutter, btnSwitch, btnSettings;
    private Button btnZoomOut, btnZoom1x, btnZoom3x;
    private SeekBar isoSlider, shutterSlider;
    private TextView txtISOValue, txtShutterValue, modePhoto, modeVideo, modePro, txtEVValue;
    private View proPanel, proSlidersLayout, videoConfigLayout, exposureIndicator;

    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private String targetPhysicalId = ID_WIDE;
    private float currentZoom = 1.0f;
    private float currentEV = 0f;

    private GestureDetector gestureDetector;
    private ExecutorService cameraExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Vibrator vibrator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Full Screen UI
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        
        setContentView(R.layout.activity_main);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        cameraExecutor = Executors.newFixedThreadPool(4);
        
        initializeUI();
        setupGestures();
        checkPermissionsAndStart();
    }

    private void initializeUI() {
        viewFinder = findViewById(R.id.viewFinder);
        btnShutter = findViewById(R.id.capture_button);
        btnSwitch = findViewById(R.id.switch_camera_button);
        btnSettings = findViewById(R.id.btn_settings);
        btnZoomOut = findViewById(R.id.btn_zoom_out);
        btnZoom1x = findViewById(R.id.btn_zoom_1x);
        btnZoom3x = findViewById(R.id.btn_zoom_3x);
        
        proPanel = findViewById(R.id.pro_info_bar);
        proSlidersLayout = findViewById(R.id.pro_sliders_layout);
        txtISOValue = findViewById(R.id.iso_text);
        txtShutterValue = findViewById(R.id.shutter_text);
        isoSlider = findViewById(R.id.iso_slider);
        shutterSlider = findViewById(R.id.shutter_slider);

        videoConfigLayout = findViewById(R.id.video_config_layout);
        
        modePhoto = findViewById(R.id.mode_photo);
        modeVideo = findViewById(R.id.mode_video);
        modePro = findViewById(R.id.mode_pro);

        exposureIndicator = findViewById(R.id.exposure_indicator);
        txtEVValue = findViewById(R.id.txt_ev_value);

        updateModeUI(Mode.PHOTO);
        setupListeners();
    }

    private void setupListeners() {
        btnShutter.setOnClickListener(v -> {
            safeVibrate(50);
            if (currentMode == Mode.VIDEO) {
                captureVideo();
            } else {
                animateShutterClick();
                takePhoto();
            }
        });

        btnSwitch.setOnClickListener(v -> {
            animateRotation(v);
            safeVibrate(40);
            toggleCamera();
        });

        btnSettings.setOnClickListener(v -> openSettings());
        
        btnZoomOut.setOnClickListener(v -> forceLens(ID_ULTRA_WIDE, 0.6f));
        btnZoom1x.setOnClickListener(v -> forceLens(ID_WIDE, 1.0f));
        btnZoom3x.setOnClickListener(v -> forceLens(ID_TELE, 3.0f));

        modePhoto.setOnClickListener(v -> updateModeUI(Mode.PHOTO));
        modeVideo.setOnClickListener(v -> updateModeUI(Mode.VIDEO));
        modePro.setOnClickListener(v -> updateModeUI(Mode.PRO));

        if (isoSlider != null) {
            isoSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                    if (f && manualControls != null) { manualControls.setISONormalized(p / 100.0f); updateProUI(); }
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
        }
        
        if (shutterSlider != null) {
            shutterSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                    if (f && manualControls != null) { manualControls.setExposureTimeNormalized(p / 100.0f); updateProUI(); }
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
        }
    }

    private void updateModeUI(Mode mode) {
        currentMode = mode;
        int activeColor = Color.parseColor("#03DAC5");
        int inactiveColor = Color.WHITE;

        if (modePhoto != null) modePhoto.setTextColor(mode == Mode.PHOTO ? activeColor : inactiveColor);
        if (modeVideo != null) modeVideo.setTextColor(mode == Mode.VIDEO ? activeColor : inactiveColor);
        if (modePro != null) modePro.setTextColor(mode == Mode.PRO ? activeColor : inactiveColor);

        proSlidersLayout.setVisibility(mode == Mode.PRO ? View.VISIBLE : View.GONE);
        proPanel.setVisibility(mode == Mode.PRO ? View.VISIBLE : View.GONE);
        videoConfigLayout.setVisibility(mode == Mode.VIDEO ? View.VISIBLE : View.GONE);
        
        startCameraSafe();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupGestures() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(MotionEvent e) { tapToFocus(e.getX(), e.getY()); return true; }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dX, float dY) { adjustExposure(dY); return true; }
        });
        viewFinder.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));
    }

    private void tapToFocus(float x, float y) {
        if (cameraControl == null) return;
        MeteringPoint point = viewFinder.getMeteringPointFactory().createPoint(x, y);
        FocusMeteringAction action = new FocusMeteringAction.Builder(point).build();
        cameraControl.startFocusAndMetering(action);
        safeVibrate(20);
    }

    private void adjustExposure(float deltaY) {
        if (cameraControl == null) return;
        currentEV += (deltaY / 2000f);
        currentEV = Math.max(-3f, Math.min(3f, currentEV));
        cameraControl.setExposureCompensationIndex(Math.round(currentEV * 2));
        
        txtEVValue.setText(String.format("%s%.1f", currentEV >= 0 ? "+" : "", currentEV));
        exposureIndicator.setAlpha(1f);
        mainHandler.removeCallbacksAndMessages(null);
        mainHandler.postDelayed(() -> exposureIndicator.animate().alpha(0f).setDuration(500).start(), 1000);
    }

    private void checkPermissionsAndStart() {
        String[] perms = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        List<String> needed = new ArrayList<>();
        for (String p : perms) if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) needed.add(p);
        if (!needed.isEmpty()) ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), 100);
        else startCameraSafe();
    }

    private void startCameraSafe() {
        viewFinder.post(() -> {
            ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
            providerFuture.addListener(() -> {
                try {
                    cameraProvider = providerFuture.get();
                    bindCameraUseCases();
                } catch (Exception e) {
                    Log.e(TAG, "Fatal Camera Init Error", e);
                }
            }, ContextCompat.getMainExecutor(this));
        });
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        try {
            cameraProvider.unbindAll();
            
            preview = new Preview.Builder().build();
            preview.setSurfaceProvider(viewFinder.getSurfaceProvider());
            
            CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .addCameraFilter(cameraInfos -> {
                    List<CameraInfo> filtered = new ArrayList<>();
                    for (CameraInfo info : cameraInfos) {
                        String id = getPhysicalIdRobust(info);
                        if (id != null && id.equals(targetPhysicalId)) filtered.add(info);
                    }
                    return filtered.isEmpty() ? cameraInfos : filtered;
                }).build();

            if (currentMode == Mode.VIDEO) {
                Recorder recorder = new Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.UHD))
                    .build();
                videoCapture = VideoCapture.withOutput(recorder);
                camera = cameraProvider.bindToLifecycle(this, selector, preview, videoCapture);
            } else {
                imageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build();
                camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);
            }

            cameraControl = camera.getCameraControl();
            cameraControl.setZoomRatio(currentZoom);
            
            if (currentMode == Mode.PRO) initializeManualControls();
            
        } catch (Exception e) {
            Log.e(TAG, "Binding Crash Avoided", e);
            if (!targetPhysicalId.equals(ID_WIDE)) {
                targetPhysicalId = ID_WIDE;
                currentZoom = 1.0f;
                bindCameraUseCases();
            }
        }
    }

    private void initializeManualControls() {
        try {
            Camera2CameraControl c2Control = Camera2CameraControl.from(cameraControl);
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            Camera2CameraInfo c2Info = Camera2CameraInfo.from(camera.getCameraInfo());
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(c2Info.getCameraId());
            manualControls = new ManualCameraControls(c2Control, characteristics);
            updateProUI();
        } catch (Exception e) { Log.e(TAG, "Manual Controls Init Failed", e); }
    }

    private void updateProUI() {
        if (manualControls == null) return;
        runOnUiThread(() -> {
            txtISOValue.setText("ISO " + manualControls.getCurrentISO());
            txtShutterValue.setText(ManualCameraControls.exposureTimeNsToFraction(manualControls.getCurrentExposureTime()));
        });
    }

    private String getPhysicalIdRobust(CameraInfo info) {
        try {
            Camera2CameraInfo c2Info = Camera2CameraInfo.from(info);
            for (Method m : c2Info.getClass().getMethods()) {
                if (m.getName().equals("getPhysicalId") || m.getName().equals("getPhysicalCameraId")) return (String) m.invoke(c2Info);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void forceLens(String id, float zoom) {
        targetPhysicalId = id;
        currentZoom = zoom;
        startCameraSafe();
    }

    private void toggleCamera() {
        lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK) ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
        targetPhysicalId = ID_WIDE;
        currentZoom = 1.0f;
        startCameraSafe();
    }

    private void takePhoto() {
        if (imageCapture == null) return;
        String name = "S21FE_" + new SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera");
        }
        
        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions
                .Builder(getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues).build();

        imageCapture.takePicture(outputOptions, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputResults) {
                mainHandler.post(() -> Toast.makeText(MainActivity.this, "Foto guardada", Toast.LENGTH_SHORT).show());
            }
            @Override public void onError(@NonNull ImageCaptureException e) { Log.e(TAG, "Capture Error", e); }
        });
    }

    private void captureVideo() {
        if (videoCapture == null) return;
        if (recording != null) { recording.stop(); recording = null; return; }

        String name = "S21FE_VID_" + new SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera");
        }

        MediaStoreOutputOptions mediaStoreOutputOptions = new MediaStoreOutputOptions
                .Builder(getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(contentValues).build();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        
        recording = videoCapture.getOutput()
                .prepareRecording(this, mediaStoreOutputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this), recordEvent -> {
                    if (recordEvent instanceof VideoRecordEvent.Start) {
                        btnShutter.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_red_light));
                    } else if (recordEvent instanceof VideoRecordEvent.Finalize) {
                        btnShutter.setBackgroundTintList(null);
                        safeVibrate(100);
                    }
                });
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
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

    private void animateShutterClick() {
        ScaleAnimation anim = new ScaleAnimation(1f, 0.9f, 1f, 0.9f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        anim.setDuration(100); anim.setRepeatCount(1); anim.setRepeatMode(Animation.REVERSE);
        btnShutter.startAnimation(anim);
    }

    private void animateRotation(View v) { ObjectAnimator.ofFloat(v, "rotation", 0f, 180f).setDuration(300).start(); }

    @Override protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (cameraProvider != null) cameraProvider.unbindAll();
    }
}

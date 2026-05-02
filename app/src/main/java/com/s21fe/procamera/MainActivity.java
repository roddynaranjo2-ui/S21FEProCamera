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
import androidx.camera.camera2.interop.Camera2Interop;
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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ExperimentalCamera2Interop
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "S21FE_G990U_PRO";
    
    private static final String ID_WIDE = "0";
    private static final String ID_ULTRA_WIDE = "1";
    private static final String ID_TELE = "2";

    private enum Mode { PHOTO, VIDEO, PRO }
    private Mode currentMode = Mode.PHOTO;

    private PreviewView viewFinder;
    private ImageButton btnShutter, btnSwitch, btnSettings;
    private Button btnZoomOut, btnZoom1x, btnZoom3x;
    private SeekBar isoSlider, shutterSlider;
    private TextView txtISOValue, txtShutterValue, modePhoto, modeVideo, modePro, txtEVValue, txtVideoConfig;
    private View proPanel, proSlidersLayout, videoConfigLayout, exposureIndicator;

    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording = null;
    private Camera camera;
    private CameraControl cameraControl;
    private ManualCameraControls manualControls;

    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private String targetPhysicalId = ID_WIDE;
    private float currentZoom = 1.0f;
    private float currentEV = 0f;
    private Quality currentQuality = Quality.UHD;

    private GestureDetector gestureDetector;
    private ExecutorService cameraExecutor;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Vibrator vibrator;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        setContentView(R.layout.activity_main);

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        cameraThread = new HandlerThread("CameraThread", Process.THREAD_PRIORITY_VIDEO);
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraExecutor = Executors.newSingleThreadExecutor();

        initializeUI();
        setupGestures();
        checkPermissionsAndStart();
    }

    private void initializeUI() {
        viewFinder = findViewById(R.id.viewFinder);
        // REPARACIÓN CRÍTICA: Forzar Modo Compatible (TextureView) para evitar pantalla negra en G990U
        viewFinder.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);

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
        txtVideoConfig = findViewById(R.id.txt_video_res_fps);
        
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
            animateShutterClick();
            safeVibrate(50);
            if (currentMode == Mode.VIDEO) captureVideo();
            else takePhoto();
        });

        if (btnSwitch != null) btnSwitch.setOnClickListener(v -> { animateRotation(v); safeVibrate(40); toggleCamera(); });
        if (btnSettings != null) btnSettings.setOnClickListener(v -> openSettings());
        
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
                    // RESET LIMPIO: Unbind total y delay para ISP del Snapdragon
                    cameraProvider.unbindAll();
                    mainHandler.postDelayed(this::bindHardwareUseCases, 200);
                } catch (Exception e) {
                    Log.e(TAG, "Engine Init Failed", e);
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }, ContextCompat.getMainExecutor(this));
        });
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void bindHardwareUseCases() {
        if (cameraProvider == null) return;
        try {
            // 1. Configuración de Preview con Camera2Interop (Forzado de Hardware)
            Preview.Builder previewBuilder = new Preview.Builder();
            Camera2Interop.Extender<Preview> previewExtender = new Camera2Interop.Extender<>(previewBuilder);
            previewExtender.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            
            preview = previewBuilder.build();
            preview.setSurfaceProvider(viewFinder.getSurfaceProvider());
            
            // 2. Selector de Cámara Física Robusto
            CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .addCameraFilter(cameraInfos -> {
                    List<CameraInfo> filtered = new ArrayList<>();
                    for (CameraInfo info : cameraInfos) {
                        String id = Camera2CameraInfo.from(info).getCameraId();
                        if (id.equals(targetPhysicalId)) filtered.add(info);
                    }
                    return filtered.isEmpty() ? cameraInfos : filtered;
                }).build();

            // 3. Casos de Uso
            if (currentMode == Mode.VIDEO) {
                Recorder recorder = new Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(currentQuality))
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
            cameraControl.setZoomRatio(currentZoom);
            
            if (currentMode == Mode.PRO) initializeManualControls();
            
        } catch (Exception e) {
            Log.e(TAG, "Binding Error", e);
            Toast.makeText(this, "Error de Vinculación: " + e.getMessage(), Toast.LENGTH_LONG).show();
            if (!targetPhysicalId.equals(ID_WIDE)) {
                targetPhysicalId = ID_WIDE;
                currentZoom = 1.0f;
                bindHardwareUseCases();
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
                mainHandler.post(() -> Toast.makeText(MainActivity.this, "Foto Guardada", Toast.LENGTH_SHORT).show());
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

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    private void safeVibrate(long ms) {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else vibrator.vibrate(ms);
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
        cameraThread.quitSafely();
        if (cameraProvider != null) cameraProvider.unbindAll();
    }
}

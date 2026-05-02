package com.s21fe.procamera;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
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
import android.content.Intent;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.Camera2CameraInfo;
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
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.VideoCapture;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

@ExperimentalCamera2Interop
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "S21FEProCamera";
    
    private static final String ID_WIDE = "0";
    private static final String ID_ULTRA_WIDE = "1";
    private static final String ID_TELE = "2";

    private enum Mode { PHOTO, VIDEO, PRO }
    private Mode currentMode = Mode.PHOTO;

    private Preview preview;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
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
    private Quality currentQuality = Quality.UHD;

    private GestureDetector gestureDetector;
    private ExecutorService cameraExecutor;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        cameraExecutor = Executors.newSingleThreadExecutor();
        initializeUI();
        setupGestures();
        setupPermissions();
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
        if (btnShutter != null) btnShutter.setOnClickListener(v -> { animateShutterClick(); safeVibrate(50); takePhoto(); });
        if (btnSwitch != null) btnSwitch.setOnClickListener(v -> { animateRotation(v); safeVibrate(40); toggleCamera(); });
        if (btnSettings != null) btnSettings.setOnClickListener(v -> openSettings());
        
        if (btnZoomOut != null) btnZoomOut.setOnClickListener(v -> forceLens(ID_ULTRA_WIDE, 0.6f));
        if (btnZoom1x != null) btnZoom1x.setOnClickListener(v -> forceLens(ID_WIDE, 1.0f));
        if (btnZoom3x != null) btnZoom3x.setOnClickListener(v -> forceLens(ID_TELE, 3.0f));

        // Dial de Zoom Fluido (Long Click)
        View.OnLongClickListener zoomLongClick = v -> {
            Toast.makeText(this, "Zoom Dial Activado", Toast.LENGTH_SHORT).show();
            safeVibrate(30);
            return true;
        };
        btnZoomOut.setOnLongClickListener(zoomLongClick);
        btnZoom1x.setOnLongClickListener(zoomLongClick);
        btnZoom3x.setOnLongClickListener(zoomLongClick);

        if (modePhoto != null) modePhoto.setOnClickListener(v -> updateModeUI(Mode.PHOTO));
        if (modeVideo != null) modeVideo.setOnClickListener(v -> updateModeUI(Mode.VIDEO));
        if (modePro != null) modePro.setOnClickListener(v -> updateModeUI(Mode.PRO));

        if (isoSlider != null) {
            isoSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                    if (f && manualControls != null) { manualControls.setISONormalized(p / 100.0f); updateProUI(); }
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
        }
    }

    private void updateModeUI(Mode mode) {
        currentMode = mode;
        int activeColor = ContextCompat.getColor(this, R.color.accent_teal);
        int inactiveColor = ContextCompat.getColor(this, R.color.white);

        if (modePhoto != null) modePhoto.setTextColor(mode == Mode.PHOTO ? activeColor : inactiveColor);
        if (modeVideo != null) modeVideo.setTextColor(mode == Mode.VIDEO ? activeColor : inactiveColor);
        if (modePro != null) modePro.setTextColor(mode == Mode.PRO ? activeColor : inactiveColor);

        // UI Invisible: Solo mostrar si es PRO
        proSlidersLayout.setVisibility(mode == Mode.PRO ? View.VISIBLE : View.GONE);
        proPanel.setVisibility(mode == Mode.PRO ? View.VISIBLE : View.GONE);
        videoConfigLayout.setVisibility(mode == Mode.VIDEO ? View.VISIBLE : View.GONE);
        
        startCameraAsync();
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
        currentEV += (deltaY / 1500f); // Más suave
        currentEV = Math.max(-3f, Math.min(3f, currentEV));
        cameraControl.setExposureCompensationIndex(Math.round(currentEV * 2));
        
        // Animación efímera del indicador EV
        txtEVValue.setText(String.format("%s%.1f", currentEV >= 0 ? "+" : "", currentEV));
        exposureIndicator.setAlpha(1f);
        mainHandler.removeCallbacksAndMessages(null);
        mainHandler.postDelayed(() -> exposureIndicator.animate().alpha(0f).setDuration(500).start(), 1500);
    }

    private void forceLens(String id, float zoom) {
        targetPhysicalId = id;
        currentZoom = zoom;
        startCameraAsync();
    }

    private void setupPermissions() {
        String[] perms = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        List<String> needed = new ArrayList<>();
        for (String p : perms) if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) needed.add(p);
        if (!needed.isEmpty()) ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), 100);
        else startCameraAsync();
    }

    private void startCameraAsync() {
        // Inicialización Asíncrona para mantener 120Hz
        cameraExecutor.execute(() -> {
            ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
            providerFuture.addListener(() -> {
                try {
                    cameraProvider = providerFuture.get();
                    mainHandler.post(this::bindCameraUseCases);
                } catch (Exception e) { Log.e(TAG, "Async Init Failed", e); }
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
                Recorder recorder = new Recorder.Builder().setQualitySelector(QualitySelector.from(currentQuality)).build();
                videoCapture = VideoCapture.withOutput(recorder);
                camera = cameraProvider.bindToLifecycle(this, selector, preview, videoCapture);
            } else {
                imageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY) // Zero Shutter Lag
                    .build();
                camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);
            }

            cameraControl = camera.getCameraControl();
            cameraControl.setZoomRatio(currentZoom);
            if (currentMode == Mode.PRO) initializeManualControls();
            
        } catch (Exception e) { Log.e(TAG, "Binding failed", e); }
    }

    private void initializeManualControls() {
        try {
            Camera2CameraControl c2Control = Camera2CameraControl.from(cameraControl);
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            Camera2CameraInfo c2Info = Camera2CameraInfo.from(camera.getCameraInfo());
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(c2Info.getCameraId());
            manualControls = new ManualCameraControls(c2Control, characteristics);
            updateProUI();
        } catch (Exception e) { Log.e(TAG, "Manual controls error", e); }
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

    private void takePhoto() {
        if (imageCapture == null) return;
        imageCapture.takePicture(ContextCompat.getMainExecutor(this), new ImageCapture.OnImageCapturedCallback() {
            @Override public void onCaptureSuccess(@NonNull ImageProxy image) { image.close(); }
            @Override public void onError(@NonNull ImageCaptureException e) { Log.e(TAG, "Error", e); }
        });
    }

    private void toggleCamera() {
        lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK) ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
        targetPhysicalId = ID_WIDE;
        startCameraAsync();
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    private void safeVibrate(long ms) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
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

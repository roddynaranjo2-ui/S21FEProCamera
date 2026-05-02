package com.s21fe.procamera;

import android.Manifest;
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
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Method;

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
    private SeekBar zoomSlider, isoSlider, shutterSlider;
    private TextView txtISOValue, txtShutterValue, modePhoto, modeVideo, modePro;
    private TextView btnResSelector, btnFpsSelector;
    private View proPanel, proSlidersLayout, videoConfigLayout;

    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private String targetPhysicalId = ID_WIDE;
    private float currentZoom = 1.0f;
    private float currentEV = 0f;
    private Quality currentQuality = Quality.UHD;

    private GestureDetector gestureDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

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
        zoomSlider = findViewById(R.id.zoom_slider);
        
        proPanel = findViewById(R.id.pro_info_bar);
        proSlidersLayout = findViewById(R.id.pro_sliders_layout);
        txtISOValue = findViewById(R.id.iso_text);
        txtShutterValue = findViewById(R.id.shutter_text);
        isoSlider = findViewById(R.id.iso_slider);
        shutterSlider = findViewById(R.id.shutter_slider);

        videoConfigLayout = findViewById(R.id.video_config_layout);
        btnResSelector = findViewById(R.id.btn_res_selector);
        btnFpsSelector = findViewById(R.id.btn_fps_selector);

        modePhoto = findViewById(R.id.mode_photo);
        modeVideo = findViewById(R.id.mode_video);
        modePro = findViewById(R.id.mode_pro);

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

        if (modePhoto != null) modePhoto.setOnClickListener(v -> updateModeUI(Mode.PHOTO));
        if (modeVideo != null) modeVideo.setOnClickListener(v -> updateModeUI(Mode.VIDEO));
        if (modePro != null) modePro.setOnClickListener(v -> updateModeUI(Mode.PRO));

        if (btnResSelector != null) btnResSelector.setOnClickListener(v -> toggleResolution());
        if (btnFpsSelector != null) btnFpsSelector.setOnClickListener(v -> toggleFPS());

        if (zoomSlider != null) {
            zoomSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean f) { if (f) updateZoomFromSlider(p / 10.0f); }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
        }

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
        int activeColor = ContextCompat.getColor(this, R.color.accent_teal);
        int inactiveColor = ContextCompat.getColor(this, R.color.white);

        if (modePhoto != null) modePhoto.setTextColor(mode == Mode.PHOTO ? activeColor : inactiveColor);
        if (modeVideo != null) modeVideo.setTextColor(mode == Mode.VIDEO ? activeColor : inactiveColor);
        if (modePro != null) modePro.setTextColor(mode == Mode.PRO ? activeColor : inactiveColor);

        if (proSlidersLayout != null) proSlidersLayout.setVisibility(mode == Mode.PRO ? View.VISIBLE : View.GONE);
        if (proPanel != null) proPanel.setVisibility(mode == Mode.PRO ? View.VISIBLE : View.GONE);
        if (videoConfigLayout != null) videoConfigLayout.setVisibility(mode == Mode.VIDEO ? View.VISIBLE : View.GONE);
        
        startCamera();
    }

    private void toggleResolution() {
        if (currentQuality == Quality.UHD) {
            currentQuality = Quality.FHD;
            btnResSelector.setText("1080P");
        } else if (currentQuality == Quality.FHD) {
            currentQuality = Quality.HD;
            btnResSelector.setText("720P");
        } else {
            currentQuality = Quality.UHD;
            btnResSelector.setText("4K");
        }
        startCamera();
    }

    private void toggleFPS() {
        // En CameraX, los FPS se gestionan mediante el selector de calidad o configuraciones de Camera2Interop
        Toast.makeText(this, "FPS ajustados según hardware", Toast.LENGTH_SHORT).show();
    }

    private void openSettings() {
        Toast.makeText(this, "Menú de Configuraciones (Próximamente)", Toast.LENGTH_SHORT).show();
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
        cameraControl.startFocusAndMetering(new FocusMeteringAction.Builder(point).build());
        safeVibrate(20);
    }

    private void adjustExposure(float deltaY) {
        if (cameraControl == null) return;
        currentEV += (deltaY / 1000f);
        currentEV = Math.max(-3f, Math.min(3f, currentEV));
        cameraControl.setExposureCompensationIndex(Math.round(currentEV * 2));
    }

    private void updateZoomFromSlider(float zoom) {
        currentZoom = Math.max(0.6f, zoom);
        String newId = (currentZoom < 1.0f) ? ID_ULTRA_WIDE : (currentZoom < 3.0f ? ID_WIDE : ID_TELE);
        if (!newId.equals(targetPhysicalId)) {
            targetPhysicalId = newId;
            startCamera();
        } else if (cameraControl != null) {
            cameraControl.setZoomRatio(currentZoom);
        }
    }

    private void forceLens(String id, float zoom) {
        targetPhysicalId = id;
        currentZoom = zoom;
        if (zoomSlider != null) zoomSlider.setProgress((int)(zoom * 10));
        startCamera();
    }

    private void setupPermissions() {
        String[] perms = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        List<String> needed = new ArrayList<>();
        for (String p : perms) if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) needed.add(p);
        if (!needed.isEmpty()) ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), 100);
        else startCamera();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();
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
                    imageCapture = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build();
                    camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);
                }

                cameraControl = camera.getCameraControl();
                cameraControl.setZoomRatio(currentZoom);
                if (currentMode == Mode.PRO) initializeManualControls();
                
            } catch (Exception e) { 
                Log.e(TAG, "Camera binding failed", e);
                if (!targetPhysicalId.equals(ID_WIDE)) { targetPhysicalId = ID_WIDE; startCamera(); }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void initializeManualControls() {
        try {
            Camera2CameraControl c2Control = Camera2CameraControl.from(cameraControl);
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            Camera2CameraInfo c2Info = Camera2CameraInfo.from(camera.getCameraInfo());
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(c2Info.getCameraId());
            manualControls = new ManualCameraControls(c2Control, characteristics);
            updateProUI();
        } catch (Exception e) { Log.e(TAG, "Manual controls init failed", e); }
    }

    private void updateProUI() {
        if (manualControls == null) return;
        runOnUiThread(() -> {
            if (txtISOValue != null) txtISOValue.setText("ISO " + manualControls.getCurrentISO());
            if (txtShutterValue != null) txtShutterValue.setText(ManualCameraControls.exposureTimeNsToFraction(manualControls.getCurrentExposureTime()));
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
        if (currentMode == Mode.VIDEO) {
            Toast.makeText(this, "Grabación de Video iniciada", Toast.LENGTH_SHORT).show();
            return;
        }
        if (imageCapture == null) return;
        imageCapture.takePicture(ContextCompat.getMainExecutor(this), new ImageCapture.OnImageCapturedCallback() {
            @Override public void onCaptureSuccess(@NonNull ImageProxy image) { image.close(); }
            @Override public void onError(@NonNull ImageCaptureException e) { Log.e(TAG, "Capture error: " + e.getMessage()); }
        });
    }

    private void toggleCamera() {
        lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK) ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
        targetPhysicalId = ID_WIDE;
        startCamera();
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
        if (btnShutter != null) btnShutter.startAnimation(anim);
    }

    private void animateRotation(View v) { ObjectAnimator.ofFloat(v, "rotation", 0f, 180f).setDuration(300).start(); }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (cameraProvider != null) cameraProvider.unbindAll();
    }
}

package com.s21fe.procamera;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Method;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
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
import androidx.camera.core.MeteringPointFactory;
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

    private Preview preview;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Camera camera;
    private CameraControl cameraControl;
    private ProcessCameraProvider cameraProvider;

    private View viewFinder;
    private ImageButton btnShutter, btnSwitch;
    private View btnGallery;
    private Button btnZoomOut, btnZoom1x, btnZoom3x;
    private SeekBar zoomSlider;
    private TextView txtZoomInfo, txtProData;

    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private String targetPhysicalId = ID_WIDE;
    private String currentlyBoundPhysicalId = "";
    private float currentZoom = 1.0f;
    private float currentZoomUltraWide = 0.6f;
    private float currentZoomWide = 1.0f;
    private float currentZoomTele = 3.0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        // Sincronización exacta con activity_main.xml
        viewFinder = findViewById(R.id.viewFinder);
        btnShutter = findViewById(R.id.capture_button);
        btnSwitch = findViewById(R.id.switch_camera_button);
        btnGallery = findViewById(R.id.gallery_preview);
        btnZoomOut = findViewById(R.id.btn_zoom_out);
        btnZoom1x = findViewById(R.id.btn_zoom_1x);
        btnZoom3x = findViewById(R.id.btn_zoom_3x);
        zoomSlider = findViewById(R.id.zoom_slider);
        txtZoomInfo = findViewById(R.id.iso_text); 
        txtProData = findViewById(R.id.shutter_text);

        setupPermissions();

        if (btnShutter != null) {
            btnShutter.setOnClickListener(v -> {
                animateShutterClick();
                safeVibrate(50);
                takePhoto();
            });
        }

        if (btnSwitch != null) {
            btnSwitch.setOnClickListener(v -> {
                animateRotation(v);
                safeVibrate(40);
                toggleCamera();
            });
        }

        if (btnZoomOut != null) btnZoomOut.setOnClickListener(v -> { animateZoomButton(v); safeVibrate(20); forceLens(ID_ULTRA_WIDE, 0.6f); });
        if (btnZoom1x != null) btnZoom1x.setOnClickListener(v -> { animateZoomButton(v); safeVibrate(20); forceLens(ID_WIDE, 1.0f); });
        if (btnZoom3x != null) btnZoom3x.setOnClickListener(v -> { animateZoomButton(v); safeVibrate(20); forceLens(ID_TELE, 3.0f); });

        if (zoomSlider != null) {
            zoomSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        float zoom = progress / 10.0f;
                        if (zoom < 0.6f) zoom = 0.6f;
                        updateZoomFromSlider(zoom);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        setupTouchToFocus();
        startProDataSimulation();
    }

    private void updateZoomFromSlider(float zoom) {
        currentZoom = zoom;
        String newTargetPhysicalId = targetPhysicalId;
        if (zoom < 1.0f) newTargetPhysicalId = ID_ULTRA_WIDE;
        else if (zoom < 3.0f) newTargetPhysicalId = ID_WIDE;
        else newTargetPhysicalId = ID_TELE;

        if (!newTargetPhysicalId.equals(targetPhysicalId)) {
            targetPhysicalId = newTargetPhysicalId;
            startCamera();
        } else {
            if (cameraControl != null) {
                cameraControl.setZoomRatio(zoom);
            }
        }

        if (targetPhysicalId.equals(ID_ULTRA_WIDE)) currentZoomUltraWide = zoom;
        else if (targetPhysicalId.equals(ID_WIDE)) currentZoomWide = zoom;
        else if (targetPhysicalId.equals(ID_TELE)) currentZoomTele = zoom;

        updateZoomButtonsUI(currentZoom);
    }

    private void forceLens(String physicalId, float zoom) {
        targetPhysicalId = physicalId;
        if (targetPhysicalId.equals(ID_ULTRA_WIDE)) currentZoom = currentZoomUltraWide;
        else if (targetPhysicalId.equals(ID_WIDE)) currentZoom = currentZoomWide;
        else if (targetPhysicalId.equals(ID_TELE)) currentZoom = currentZoomTele;

        if (zoomSlider != null) zoomSlider.setProgress((int)(currentZoom * 10));

        if (!currentlyBoundPhysicalId.equals(physicalId)) {
            if (viewFinder != null) {
                viewFinder.animate().alpha(0.7f).setDuration(80).withEndAction(() -> {
                    viewFinder.animate().alpha(1.0f).setDuration(150).start();
                    startCamera();
                }).start();
            } else {
                startCamera();
            }
        } else {
            if (cameraControl != null) {
                cameraControl.setZoomRatio(currentZoom);
            }
            updateZoomButtonsUI(currentZoom);
        }
    }

    private void safeVibrate(long duration) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(duration);
                }
            }
        } catch (Exception e) { Log.e(TAG, "Vibration error", e); }
    }

    private void animateShutterClick() {
        if (btnShutter == null) return;
        ScaleAnimation anim = new ScaleAnimation(1f, 0.9f, 1f, 0.9f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        anim.setDuration(100);
        anim.setRepeatCount(1);
        anim.setRepeatMode(Animation.REVERSE);
        btnShutter.startAnimation(anim);
    }

    private void animateZoomButton(View v) {
        v.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()).start();
    }

    private void animateRotation(View v) {
        ObjectAnimator.ofFloat(v, "rotation", 0f, 180f).setDuration(300).start();
    }

    private void updateZoomButtonsUI(float ratio) {
        if (txtZoomInfo != null) txtZoomInfo.setText(String.format("%.1fx", ratio));
    }

    private void startProDataSimulation() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (txtProData != null) txtProData.setText("ISO 100 | 1/125 | WB 5000K");
        }, 1500);
    }

    private void setupPermissions() {
        String[] permissions = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }
        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toArray(new String[0]), 100);
        } else {
            startCamera();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                cameraProvider.unbindAll();

                preview = new Preview.Builder().build();
                if (viewFinder instanceof androidx.camera.view.PreviewView) {
                    preview.setSurfaceProvider(((androidx.camera.view.PreviewView)viewFinder).getSurfaceProvider());
                }

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setJpegQuality(100)
                        .build();

                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);

                CameraSelector cameraSelector = new CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .addCameraFilter(cameraInfos -> {
                        List<CameraInfo> filtered = new ArrayList<>();
                        for (CameraInfo info : cameraInfos) {
                            String physicalId = getPhysicalIdRobust(info);
                            if (physicalId != null && physicalId.equals(targetPhysicalId)) {
                                filtered.add(info);
                            }
                        }
                        return filtered;
                    })
                    .build();

                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, videoCapture);
                cameraControl = camera.getCameraControl();
                cameraControl.setZoomRatio(currentZoom);
                currentlyBoundPhysicalId = targetPhysicalId;

            } catch (Exception e) { Log.e(TAG, "Error camera binding", e); }
        }, ContextCompat.getMainExecutor(this));
    }

    // SOLUCIÓN MAESTRA: Detección robusta del ID físico mediante reflexión
    private String getPhysicalIdRobust(CameraInfo info) {
        try {
            Camera2CameraInfo c2Info = Camera2CameraInfo.from(info);
            // Intentar getPhysicalId()
            try {
                Method m = c2Info.getClass().getMethod("getPhysicalId");
                return (String) m.invoke(c2Info);
            } catch (Exception e1) {
                // Intentar getPhysicalCameraId()
                try {
                    Method m = c2Info.getClass().getMethod("getPhysicalCameraId");
                    return (String) m.invoke(c2Info);
                } catch (Exception e2) {
                    Log.e(TAG, "No se pudo encontrar el método para obtener el ID físico");
                    return null;
                }
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void takePhoto() {
        if (imageCapture == null) return;
        imageCapture.takePicture(ContextCompat.getMainExecutor(this), new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                Log.d(TAG, "Photo captured successfully");
                image.close();
            }
            @Override
            public void onError(@NonNull ImageCaptureException exception) { Log.e(TAG, "Error: " + exception.getMessage()); }
        });
    }

    private void toggleCamera() {
        lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK) ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
        targetPhysicalId = (lensFacing == CameraSelector.LENS_FACING_BACK) ? ID_WIDE : "0";
        startCamera();
    }

    private void setupTouchToFocus() {
        if (viewFinder == null) return;
        viewFinder.setOnTouchListener((v, event) -> {
            if (event.getAction() != android.view.MotionEvent.ACTION_UP) return false;
            if (viewFinder instanceof androidx.camera.view.PreviewView) {
                MeteringPointFactory factory = ((androidx.camera.view.PreviewView)viewFinder).getMeteringPointFactory();
                MeteringPoint point = factory.createPoint(event.getX(), event.getY());
                FocusMeteringAction action = new FocusMeteringAction.Builder(point).build();
                if (cameraControl != null) cameraControl.startFocusAndMetering(action);
            }
            return true;
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraProvider != null) cameraProvider.unbindAll();
    }
}

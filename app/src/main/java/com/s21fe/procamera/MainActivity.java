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
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@ExperimentalCamera2Interop
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "S21FEProCamera";
    private static final String FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS";
    private static final int REQUEST_CODE_PERMISSIONS = 1001;

    // IDs Físicos del S21 FE
    private static final String ID_WIDE = "0";       
    private static final String ID_ULTRA_WIDE = "2"; 
    private static final String ID_TELE = "3";       

    private PreviewView viewFinder;
    private ImageButton captureButton;
    private ImageButton switchCameraButton;
    private TextView modePhoto, modeVideo, modePro;
    private Button btnZoomOut, btnZoom1x, btnZoom3x;
    private SeekBar zoomSlider;
    private TextView isoText, shutterText;
    
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording = null;
    private Camera camera;
    private CameraControl cameraControl;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private String currentMode = "PHOTO";
    private float currentZoom = 1.0f;
    private String targetPhysicalId = ID_WIDE;
    
    private ExecutorService cameraExecutor;
    private Vibrator vibrator;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
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
        zoomSlider = findViewById(R.id.zoom_slider);
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

        btnZoomOut.setOnClickListener(v -> { animateZoomButton(v); safeVibrate(20); forceLens(ID_ULTRA_WIDE, 0.6f); });
        btnZoom1x.setOnClickListener(v -> { animateZoomButton(v); safeVibrate(20); forceLens(ID_WIDE, 1.0f); });
        btnZoom3x.setOnClickListener(v -> { animateZoomButton(v); safeVibrate(20); forceLens(ID_TELE, 3.0f); });

        // Configuración del Slider de Zoom (0.6x a 30x)
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
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        setupTouchToFocus();
    }

    private void updateZoomFromSlider(float zoom) {
        currentZoom = zoom;
        if (zoom < 1.0f) targetPhysicalId = ID_ULTRA_WIDE;
        else if (zoom < 3.0f) targetPhysicalId = ID_WIDE;
        else targetPhysicalId = ID_TELE;
        
        if (cameraControl != null) {
            cameraControl.setZoomRatio(zoom);
        }
        updateZoomButtonsUI(zoom);
    }

    private void forceLens(String physicalId, float zoom) {
        targetPhysicalId = physicalId;
        currentZoom = zoom;
        if (zoomSlider != null) zoomSlider.setProgress((int)(zoom * 10));
        
        if (viewFinder != null) {
            viewFinder.animate().alpha(0.7f).setDuration(80).withEndAction(() -> {
                startCamera();
                viewFinder.animate().alpha(1.0f).setDuration(150).start();
            }).start();
        } else {
            startCamera();
        }
        updateZoomButtonsUI(zoom);
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
        ScaleAnimation anim = new ScaleAnimation(1f, 0.9f, 1f, 0.9f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        anim.setDuration(70);
        anim.setRepeatMode(Animation.REVERSE);
        anim.setRepeatCount(1);
        if (captureButton != null) captureButton.startAnimation(anim);
    }

    private void animateZoomButton(View v) {
        if (v != null) {
            v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(80).withEndAction(() -> 
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
            ).start();
        }
    }

    private void animateRotation(View v) {
        if (v != null) {
            v.animate().rotationBy(180f).setDuration(350).setInterpolator(new AccelerateDecelerateInterpolator()).start();
        }
    }

    private void updateZoomButtonsUI(float ratio) {
        if (btnZoomOut != null) btnZoomOut.setAlpha(ratio == 0.6f ? 1.0f : 0.5f);
        if (btnZoom1x != null) btnZoom1x.setAlpha(ratio == 1.0f ? 1.0f : 0.5f);
        if (btnZoom3x != null) btnZoom3x.setAlpha(ratio == 3.0f ? 1.0f : 0.5f);
    }

    private void startProDataSimulation() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (currentMode.equals("PRO")) {
                    int iso = 50 + (int)(Math.random() * 800);
                    if (isoText != null) isoText.setText("ISO " + iso);
                    if (shutterText != null) shutterText.setText("S 1/" + (250 + (int)(Math.random() * 1000)));
                }
                mainHandler.postDelayed(this, 1500);
            }
        }, 1500);
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
                
                Preview preview = new Preview.Builder()
                        .setTargetName("S26Preview")
                        .build();
                if (viewFinder != null) preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setJpegQuality(100)
                        .build();

                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build();

                cameraProvider.unbindAll();
                if (currentMode.equals("VIDEO")) {
                    camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture);
                } else {
                    camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
                }
                
                cameraControl = camera.getCameraControl();
                
                // PROCESAMIENTO COMPUTACIONAL (ESTILO GCAM/SAMSUNG)
                Camera2CameraControl camera2Control = Camera2CameraControl.from(cameraControl);
                CaptureRequestOptions.Builder builder = new CaptureRequestOptions.Builder();
                
                if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                    cameraControl.setZoomRatio(currentZoom);
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        // Forzado de reducción de ruido y HDR computacional
                        builder.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
                        builder.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY);
                        builder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY);
                        builder.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY);
                        builder.setCaptureRequestOption(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY);
                    }
                }
                
                camera2Control.setCaptureRequestOptions(builder.build());

            } catch (Exception e) { Log.e(TAG, "Error camera binding", e); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        if (imageCapture == null) return;
        String name = "S21FE_PRO_" + new SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis());
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
        targetPhysicalId = ID_WIDE;
        if (zoomSlider != null) zoomSlider.setProgress(10);
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

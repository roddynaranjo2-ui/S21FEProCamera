package com.s21fe.procamera;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
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
    private TextView modePhoto, modeVideo, modePro;
    private Button btnZoomOut, btnZoom1x, btnZoom3x;
    
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording = null;
    private Camera camera;
    private CameraControl cameraControl;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private String currentMode = "PHOTO";
    
    private ExecutorService cameraExecutor;
    private Vibrator vibrator;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    static {
        System.loadLibrary("procamera");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        initViews();
        setupPermissions();
        cameraExecutor = Executors.newFixedThreadPool(4);
    }

    private void initViews() {
        viewFinder = findViewById(R.id.viewFinder);
        captureButton = findViewById(R.id.capture_button);
        switchCameraButton = findViewById(R.id.switch_camera_button);
        
        // Selector de modos individual para mejor control de color
        modePhoto = findViewById(R.id.mode_photo);
        modeVideo = findViewById(R.id.mode_video);
        modePro = findViewById(R.id.mode_pro);

        btnZoomOut = findViewById(R.id.btn_zoom_out);
        btnZoom1x = findViewById(R.id.btn_zoom_1x);
        btnZoom3x = findViewById(R.id.btn_zoom_3x);

        updateModeUI();

        captureButton.setOnClickListener(v -> {
            vibrate(50);
            if (currentMode.equals("VIDEO")) {
                captureVideo();
            } else {
                animateButtonClick();
                takePhoto();
            }
        });

        modePhoto.setOnClickListener(v -> { currentMode = "PHOTO"; updateModeUI(); startCamera(); vibrate(30); });
        modeVideo.setOnClickListener(v -> { currentMode = "VIDEO"; updateModeUI(); startCamera(); vibrate(30); });
        modePro.setOnClickListener(v -> { currentMode = "PRO"; updateModeUI(); startCamera(); vibrate(30); });

        switchCameraButton.setOnClickListener(v -> { vibrate(40); toggleCamera(); });

        btnZoomOut.setOnClickListener(v -> { vibrate(20); setZoom(0.6f); });
        btnZoom1x.setOnClickListener(v -> { vibrate(20); setZoom(1.0f); });
        btnZoom3x.setOnClickListener(v -> { vibrate(20); setZoom(3.0f); });

        setupTouchToFocus();
    }

    private void updateModeUI() {
        int activeColor = Color.parseColor("#03DAC5"); // Verde azulado
        int inactiveColor = Color.WHITE;

        modePhoto.setTextColor(currentMode.equals("PHOTO") ? activeColor : inactiveColor);
        modeVideo.setTextColor(currentMode.equals("VIDEO") ? activeColor : inactiveColor);
        modePro.setTextColor(currentMode.equals("PRO") ? activeColor : inactiveColor);
    }

    private void vibrate(long duration) {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(duration);
            }
        }
    }

    private void animateButtonClick() {
        ScaleAnimation anim = new ScaleAnimation(1f, 0.9f, 1f, 0.9f, 
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        anim.setDuration(100);
        anim.setRepeatMode(Animation.REVERSE);
        anim.setRepeatCount(1);
        captureButton.startAnimation(anim);
    }

    private void setZoom(float ratio) {
        if (cameraControl != null) {
            cameraControl.setZoomRatio(ratio);
        }
    }

    private void setupPermissions() {
        String[] permissions = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P ?
                new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE} :
                new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};

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

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

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

                cameraProvider.unbindAll();
                
                if (currentMode.equals("VIDEO")) {
                    camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture);
                } else {
                    camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
                }
                
                cameraControl = camera.getCameraControl();

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error camera", e);
            }
        }, ContextCompat.getMainExecutor(this));
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
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputResults) {
                Log.d(TAG, "Saved: " + outputResults.getSavedUri());
            }
            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "Error: " + exception.getMessage());
            }
        });
    }

    private void captureVideo() {
        if (videoCapture == null) return;

        Recording curRecording = recording;
        if (curRecording != null) {
            curRecording.stop();
            recording = null;
            return;
        }

        String name = "S21FE_VID_" + new SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera");
        }

        MediaStoreOutputOptions mediaStoreOutputOptions = new MediaStoreOutputOptions
                .Builder(getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues)
                .build();

        recording = videoCapture.getOutput()
                .prepareRecording(this, mediaStoreOutputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this), recordEvent -> {
                    if (recordEvent instanceof VideoRecordEvent.Start) {
                        captureButton.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_red_light));
                        // Animación de grabación (pestañeo o escala)
                    } else if (recordEvent instanceof VideoRecordEvent.Finalize) {
                        captureButton.setBackgroundTintList(null);
                        vibrate(100);
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
            FocusMeteringAction action = new FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS).build();
            if (cameraControl != null) cameraControl.startFocusAndMetering(action);
            vibrate(20);
            return true;
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}

@SuppressLint("UnsafeOptInUsageError")
private void bindCameraCases() {
    if (cameraProvider == null) return;
    try {
        cameraProvider.unbindAll();

        // 1. Configurar Preview
        preview = new Preview.Builder()
                .setTargetRotation(viewFinder.getDisplay().getRotation())
                .build();

        // 2. Configurar Captura
        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build();

        // 3. Selector simplificado para evitar bloqueo por ID
        // Si queremos el lente principal, usamos DEFAULT_BACK_CAMERA primero
        CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;

        // 4. VINCULAR PRIMERO
        camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);

        // 5. ASIGNAR EL SURFACE DESPUÉS DE VINCULAR
        preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

        if (camera != null) {
            camera2Control = Camera2CameraControl.from(camera.getCameraControl());
            Camera2CameraInfo camera2Info = Camera2CameraInfo.from(camera.getCameraInfo());
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            
            if (cameraManager != null) {
                String cameraId = camera2Info.getCameraId();
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(cameraId);
                manualControls = new ManualCameraControls(camera2Control, chars);
                Log.d(TAG, "Cámara iniciada en ID: " + cameraId);
            }
            isCameraInitialized = true;
        }
    } catch (Exception e) { 
        Log.e(TAG, "Error crítico al vincular cámara", e);
        Toast.makeText(this, "Error de hardware: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }
}

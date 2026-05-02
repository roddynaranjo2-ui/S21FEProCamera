@SuppressLint("UnsafeOptInUsageError")
private void bindCameraCases() {
    if (cameraProvider == null) return;
    try {
        cameraProvider.unbindAll();

        // 1. Configurar Preview con modo compatible para Samsung
        preview = new Preview.Builder()
                .setTargetRotation(viewFinder.getDisplay().getRotation())
                .build();

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build();

        // 2. Usar el selector por defecto para asegurar imagen
        CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;

        // 3. VINCULAR PRIMERO AL CICLO DE VIDA
        camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);

        // 4. CONECTAR EL VISOR DESPUÉS DE VINCULAR
        // Esto evita que el Surface se quede esperando infinitamente
        preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

        isCameraInitialized = true;
        Log.d(TAG, "Cámara vinculada con éxito");

    } catch (Exception e) { 
        Log.e(TAG, "Error crítico de binding", e);
    }
}

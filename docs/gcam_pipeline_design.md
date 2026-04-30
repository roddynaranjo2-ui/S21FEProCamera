# Diseño del Pipeline de Procesamiento Inspirado en GCam para S21FEProCamera

## 1. Introducción

Este documento detalla el diseño propuesto para integrar funcionalidades de fotografía computacional inspiradas en GCam en la aplicación S21FEProCamera. El objetivo es replicar la calidad de imagen y las características avanzadas de GCam, utilizando las capacidades existentes de la aplicación (Camera2 API, módulos C++) y expandiéndolas con nuevas implementaciones.

## 2. Análisis del Pipeline de GCam (Basado en `pasted_content.txt`)

El análisis del APK de GCam revela un pipeline sofisticado que combina captura multi-frame, procesamiento nativo acelerado por hardware (DSP), algoritmos de IA y un cuidadoso "color science". Las claves de su calidad residen en:

*   **Captura Multi-frame**: Fusión de múltiples exposiciones para HDR+ y reducción de ruido en baja luz.
*   **Alineación de Frames**: Uso de librerías como `libcyclops.so` para alinear con precisión los fotogramas capturados.
*   **Procesamiento Nativo**: Librerías como `libimage.so`, `libagc.so`, `libSeeDarkJni.so` y `libhalide_hexagon_host.so` para HDR, Night Sight y aceleración por DSP.
*   **Denoise Inteligente**: Combinación de IA y técnicas temporales.
*   **Tone Mapping y Color Science**: Curvas de tono específicas y LUTs para el "look GCam".
*   **AI Scoring**: Evaluación de calidad para selección de frames y ajustes dinámicos.

**Limitación clave**: Las librerías propietarias de Google (`libagc.so`, `libimage.so`, `libcyclops.so`) no pueden ser reutilizadas directamente. Debemos implementar alternativas de código abierto o propias.

## 3. Arquitectura Propuesta para S21FEProCamera

La integración se centrará en extender los módulos C++ existentes (`hdr_processor.cpp`, `denoise_processor.cpp`, `native-lib.cpp`) y la interacción con `MainActivity.java` a través de JNI.

### 3.1. Módulos C++ (app/src/main/cpp/)

*   **`native-lib.cpp`**: Actuará como el punto de entrada JNI, orquestando las llamadas a los procesadores de imagen.
    *   `processHDR(Bitmap[] rawFrames)`: Recibirá un array de `Bitmap` (o `Image` de CameraX) y coordinará la alineación y fusión.
    *   `applyDenoise(Bitmap input)`: Aplicará algoritmos de reducción de ruido.
    *   `applyToneMapping(Bitmap input)`: Aplicará las curvas de tono y color.

*   **`hdr_processor.cpp`**: Implementará la lógica de HDR multi-frame.
    *   `alignFrames(Bitmap[] frames)`: Utilizará OpenCV (si se integra) o algoritmos propios para alinear los fotogramas.
    *   `fuseFrames(Bitmap[] alignedFrames)`: Combinará los fotogramas alineados para generar una imagen HDR final.

*   **`denoise_processor.cpp`**: Implementará algoritmos de reducción de ruido.
    *   `applyTemporalDenoise(Bitmap[] frames)`: Reducción de ruido temporal.
    *   `applySpatialDenoise(Bitmap input)`: Reducción de ruido espacial (e.g., Bilateral Filter, BM3D).

*   **`image_processor.cpp` (Nuevo)**: Un módulo general para tone mapping, color science y sharpening.
    *   `applyToneMapping(Bitmap input, float[] toneCurve)`.
    *   `applyColorCorrection(Bitmap input, float[] lut)`.
    *   `applySharpening(Bitmap input)`.

### 3.2. Integración de OpenCV

Dado que `pasted_content.txt` sugiere OpenCV para la alineación de frames y nuestro `app/build.gradle` no lo incluye, será necesario:

1.  **Añadir dependencia de OpenCV**: Modificar `app/build.gradle` para incluir la librería nativa de OpenCV para Android.
2.  **Configurar CMake**: Actualizar `app/src/main/cpp/CMakeLists.txt` para enlazar OpenCV.

### 3.3. Controles Manuales (ISO, Shutter, WB)

La simulación actual de ISO y Shutter en `MainActivity.java` será reemplazada por controles reales que interactúen con la Camera2 API.

*   **`MainActivity.java`**: Gestionará la UI y enviará los valores de ISO, tiempo de exposición y balance de blancos al `CameraControl` de CameraX, que a su vez los traducirá a `CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION`, `CaptureRequest.SENSOR_EXPOSURE_TIME`, `CaptureRequest.SENSOR_SENSITIVITY` y `CaptureRequest.CONTROL_AWB_MODE`.
*   **`Camera2CameraControl`**: Se utilizará para inyectar directamente los `CaptureRequestOptions` con los parámetros manuales.

### 3.4. Captura Multi-frame (Burst)

Para HDR+ y Night Sight, se implementará una captura en ráfaga:

*   **`ImageCapture`**: Se configurará para capturar una secuencia de imágenes RAW o YUV.
*   **`ImageAnalysis`**: Un `UseCase` de CameraX podría utilizarse para procesar los frames en tiempo real o en segundo plano.

## 4. Fases de Implementación

1.  **Configuración de Entorno**: Integrar OpenCV en el proyecto Android. (Fase 1 del plan)
2.  **Captura Multi-frame**: Implementar la captura en ráfaga y la transferencia de frames a C++.
3.  **Alineación y Fusión HDR**: Desarrollar `hdr_processor.cpp` con alineación (OpenCV) y fusión.
4.  **Controles Manuales Reales**: Conectar la UI de ISO/Shutter con la Camera2 API.
5.  **Reducción de Ruido**: Desarrollar `denoise_processor.cpp`.
6.  **Tone Mapping y Color**: Implementar `image_processor.cpp`.

## 5. Próximos Pasos

El siguiente paso es la integración de OpenCV en el proyecto para habilitar la alineación de frames en el módulo C++.

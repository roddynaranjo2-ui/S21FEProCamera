package com.s21fe.procamera;

/**
 * Clase que proporciona acceso a las funciones nativas (C++) a través de JNI.
 * Incluye métodos para procesamiento HDR, tone mapping, denoise y color correction.
 */
public class NativeLib {
    
    static {
        System.loadLibrary("procamera");
    }
    
    // ============ HDR Processor ============
    
    /**
     * Inicializar el procesador HDR
     * @param maxFrames Número máximo de fotogramas a procesar
     */
    public static native void initHDRProcessor(int maxFrames);
    
    /**
     * Liberar el procesador HDR
     */
    public static native void releaseHDRProcessor();
    
    /**
     * Añadir un fotograma para procesamiento HDR
     * @param data Array de bytes del fotograma
     * @param width Ancho del fotograma
     * @param height Alto del fotograma
     * @param channels Número de canales (3 para RGB, 4 para RGBA)
     * @param exposure Valor de exposición (normalizado)
     * @return true si se añadió exitosamente, false en caso contrario
     */
    public static native boolean addFrameForHDR(byte[] data, int width, int height, int channels, float exposure);
    
    /**
     * Obtener el número de fotogramas capturados
     * @return Número de fotogramas
     */
    public static native int getHDRFrameCount();
    
    /**
     * Alinear los fotogramas capturados
     * @return true si la alineación fue exitosa
     */
    public static native boolean alignHDRFrames();
    
    /**
     * Fusionar los fotogramas para crear HDR
     * @param output Array de bytes de salida
     * @param width Ancho de la imagen
     * @param height Alto de la imagen
     * @param channels Número de canales
     * @return true si la fusión fue exitosa
     */
    public static native boolean fuseHDRFrames(byte[] output, int width, int height, int channels);
    
    /**
     * Limpiar los fotogramas almacenados
     */
    public static native void clearHDRFrames();
    
    // ============ Image Processor ============
    
    /**
     * Aplicar tone mapping a una imagen
     * @param input Array de bytes de entrada
     * @param output Array de bytes de salida
     * @param width Ancho de la imagen
     * @param height Alto de la imagen
     * @param channels Número de canales
     * @param strength Intensidad del tone mapping (1.0 es normal)
     * @return true si fue exitoso
     */
    public static native boolean applyToneMapping(byte[] input, byte[] output, int width, int height, int channels, float strength);
    
    /**
     * Aplicar denoise temporal
     * @param output Array de bytes de salida
     * @param width Ancho de la imagen
     * @param height Alto de la imagen
     * @param channels Número de canales
     * @return true si fue exitoso
     */
    public static native boolean applyTemporalDenoise(byte[] output, int width, int height, int channels);
    
    /**
     * Aplicar corrección de color usando una LUT
     * @param input Array de bytes de entrada
     * @param output Array de bytes de salida
     * @param width Ancho de la imagen
     * @param height Alto de la imagen
     * @param channels Número de canales
     * @param lut Array de valores LUT (256 valores entre 0.0 y 1.0)
     * @return true si fue exitoso
     */
    public static native boolean applyColorCorrection(byte[] input, byte[] output, int width, int height, int channels, float[] lut);
    
    /**
     * Aplicar sharpening consciente de bordes
     * @param input Array de bytes de entrada
     * @param output Array de bytes de salida
     * @param width Ancho de la imagen
     * @param height Alto de la imagen
     * @param channels Número de canales
     * @param strength Intensidad del sharpening
     * @return true si fue exitoso
     */
    public static native boolean applySharpeningEdgeAware(byte[] input, byte[] output, int width, int height, int channels, float strength);
    
    /**
     * Aplicar filtro bilateral para denoise espacial
     * @param input Array de bytes de entrada
     * @param output Array de bytes de salida
     * @param width Ancho de la imagen
     * @param height Alto de la imagen
     * @param channels Número de canales
     * @param sigmaSpace Desviación estándar espacial
     * @param sigmaTone Desviación estándar de tono
     * @return true si fue exitoso
     */
    public static native boolean applyBilateralFilter(byte[] input, byte[] output, int width, int height, int channels, float sigmaSpace, float sigmaTone);
    
    /**
     * Aplicar boost de contraste local
     * @param input Array de bytes de entrada
     * @param output Array de bytes de salida
     * @param width Ancho de la imagen
     * @param height Alto de la imagen
     * @param channels Número de canales
     * @param strength Intensidad del boost
     * @return true si fue exitoso
     */
    public static native boolean applyLocalContrastBoost(byte[] input, byte[] output, int width, int height, int channels, float strength);
    
    /**
     * Aplicar corrección de gamma
     * @param input Array de bytes de entrada
     * @param output Array de bytes de salida
     * @param width Ancho de la imagen
     * @param height Alto de la imagen
     * @param channels Número de canales
     * @param gamma Valor de gamma (típicamente 2.2)
     * @return true si fue exitoso
     */
    public static native boolean applyGammaCorrection(byte[] input, byte[] output, int width, int height, int channels, float gamma);
    
    /**
     * Aumentar la saturación de color
     * @param input Array de bytes de entrada
     * @param output Array de bytes de salida
     * @param width Ancho de la imagen
     * @param height Alto de la imagen
     * @param saturation Factor de saturación (1.0 es normal, >1.0 aumenta)
     * @return true si fue exitoso
     */
    public static native boolean increaseSaturation(byte[] input, byte[] output, int width, int height, float saturation);
}

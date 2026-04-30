package com.s21fe.procamera;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.util.Log;
import android.util.Range;

import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.CaptureRequestOptions;

/**
 * Clase para gestionar controles manuales de cámara (ISO, Shutter, Balance de Blancos).
 * Proporciona una interfaz simplificada para aplicar valores reales a través de Camera2 API.
 */
public class ManualCameraControls {
    
    private static final String TAG = "ManualCameraControls";
    
    private Camera2CameraControl camera2Control;
    private CameraCharacteristics characteristics;
    
    // Rangos de valores soportados
    private Range<Integer> isoRange;
    private Range<Long> exposureTimeRange;
    private int[] awbModes;
    
    // Valores actuales
    private int currentISO = 100;
    private long currentExposureTime = 33_333_333; // ~30ms por defecto
    private int currentAWBMode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
    
    public ManualCameraControls(Camera2CameraControl camera2Control, CameraCharacteristics characteristics) {
        this.camera2Control = camera2Control;
        this.characteristics = characteristics;
        
        // Obtener rangos soportados
        this.isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        this.exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        this.awbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        
        logSupportedRanges();
    }
    
    /**
     * Registrar los rangos soportados en el log
     */
    private void logSupportedRanges() {
        if (isoRange != null) {
            Log.i(TAG, "ISO Range: " + isoRange.getLower() + " - " + isoRange.getUpper());
        }
        if (exposureTimeRange != null) {
            Log.i(TAG, "Exposure Time Range: " + exposureTimeRange.getLower() + " - " + exposureTimeRange.getUpper() + " ns");
        }
        if (awbModes != null) {
            Log.i(TAG, "AWB Modes available: " + awbModes.length);
        }
    }
    
    /**
     * Establecer ISO (sensibilidad del sensor)
     * @param iso Valor de ISO (100-3200 típicamente)
     */
    public void setISO(int iso) {
        if (isoRange == null) {
            Log.w(TAG, "ISO no soportado en este dispositivo");
            return;
        }
        
        // Clamping al rango soportado
        int clampedISO = Math.max(isoRange.getLower(), Math.min(iso, isoRange.getUpper()));
        this.currentISO = clampedISO;
        
        applyManualControls();
        Log.d(TAG, "ISO establecido a: " + clampedISO);
    }
    
    /**
     * Establecer tiempo de exposición (Shutter Speed)
     * @param exposureTimeNs Tiempo de exposición en nanosegundos
     */
    public void setExposureTime(long exposureTimeNs) {
        if (exposureTimeRange == null) {
            Log.w(TAG, "Exposure Time no soportado en este dispositivo");
            return;
        }
        
        // Clamping al rango soportado
        long clampedExposureTime = Math.max(exposureTimeRange.getLower(), 
                                            Math.min(exposureTimeNs, exposureTimeRange.getUpper()));
        this.currentExposureTime = clampedExposureTime;
        
        applyManualControls();
        Log.d(TAG, "Exposure Time establecido a: " + clampedExposureTime + " ns");
    }
    
    /**
     * Establecer Balance de Blancos (AWB Mode)
     * @param awbMode Modo de AWB (AUTO, DAYLIGHT, CLOUDY_DAYLIGHT, SHADE, TUNGSTEN, FLUORESCENT, etc.)
     */
    public void setAWBMode(int awbMode) {
        if (awbModes == null || awbModes.length == 0) {
            Log.w(TAG, "AWB no soportado en este dispositivo");
            return;
        }
        
        // Verificar si el modo está soportado
        boolean supported = false;
        for (int mode : awbModes) {
            if (mode == awbMode) {
                supported = true;
                break;
            }
        }
        
        if (!supported) {
            Log.w(TAG, "AWB Mode " + awbMode + " no está soportado. Usando AUTO.");
            this.currentAWBMode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
        } else {
            this.currentAWBMode = awbMode;
        }
        
        applyManualControls();
        Log.d(TAG, "AWB Mode establecido a: " + this.currentAWBMode);
    }
    
    /**
     * Establecer ISO mediante un valor normalizado (0.0 - 1.0)
     * @param normalizedValue Valor entre 0.0 (ISO mínimo) y 1.0 (ISO máximo)
     */
    public void setISONormalized(float normalizedValue) {
        if (isoRange == null) {
            Log.w(TAG, "ISO no soportado en este dispositivo");
            return;
        }
        
        normalizedValue = Math.max(0.0f, Math.min(1.0f, normalizedValue));
        int iso = (int) (isoRange.getLower() + (isoRange.getUpper() - isoRange.getLower()) * normalizedValue);
        setISO(iso);
    }
    
    /**
     * Establecer tiempo de exposición mediante un valor normalizado (0.0 - 1.0)
     * @param normalizedValue Valor entre 0.0 (exposición mínima) y 1.0 (exposición máxima)
     */
    public void setExposureTimeNormalized(float normalizedValue) {
        if (exposureTimeRange == null) {
            Log.w(TAG, "Exposure Time no soportado en este dispositivo");
            return;
        }
        
        normalizedValue = Math.max(0.0f, Math.min(1.0f, normalizedValue));
        long exposureTime = (long) (exposureTimeRange.getLower() + 
                                    (exposureTimeRange.getUpper() - exposureTimeRange.getLower()) * normalizedValue);
        setExposureTime(exposureTime);
    }
    
    /**
     * Aplicar los controles manuales actuales a través de Camera2 API
     */
    private void applyManualControls() {
        if (camera2Control == null) {
            Log.e(TAG, "Camera2CameraControl no inicializado");
            return;
        }
        
        try {
            CaptureRequestOptions.Builder builder = new CaptureRequestOptions.Builder();
            
            // Desactivar AE (Auto Exposure) para aplicar controles manuales
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            
            // Aplicar ISO
            if (isoRange != null) {
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, currentISO);
            }
            
            // Aplicar tiempo de exposición
            if (exposureTimeRange != null) {
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureTime);
            }
            
            // Aplicar AWB Mode
            if (awbModes != null) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, currentAWBMode);
            }
            
            camera2Control.addCaptureRequestOptions(builder.build());
            Log.d(TAG, "Controles manuales aplicados: ISO=" + currentISO + ", ExposureTime=" + currentExposureTime + " ns, AWB=" + currentAWBMode);
            
        } catch (Exception e) {
            Log.e(TAG, "Error al aplicar controles manuales: " + e.getMessage());
        }
    }
    
    /**
     * Restaurar controles automáticos (AE, AF, AWB)
     */
    public void restoreAutoControls() {
        if (camera2Control == null) {
            Log.e(TAG, "Camera2CameraControl no inicializado");
            return;
        }
        
        try {
            CaptureRequestOptions.Builder builder = new CaptureRequestOptions.Builder();
            
            // Restaurar AE automático
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            
            camera2Control.addCaptureRequestOptions(builder.build());
            Log.d(TAG, "Controles automáticos restaurados");
            
        } catch (Exception e) {
            Log.e(TAG, "Error al restaurar controles automáticos: " + e.getMessage());
        }
    }
    
    /**
     * Obtener el ISO actual
     */
    public int getCurrentISO() {
        return currentISO;
    }
    
    /**
     * Obtener el tiempo de exposición actual en nanosegundos
     */
    public long getCurrentExposureTime() {
        return currentExposureTime;
    }
    
    /**
     * Obtener el modo AWB actual
     */
    public int getCurrentAWBMode() {
        return currentAWBMode;
    }
    
    /**
     * Obtener el rango de ISO soportado
     */
    public Range<Integer> getISORange() {
        return isoRange;
    }
    
    /**
     * Obtener el rango de tiempo de exposición soportado
     */
    public Range<Long> getExposureTimeRange() {
        return exposureTimeRange;
    }
    
    /**
     * Obtener los modos AWB soportados
     */
    public int[] getAWBModes() {
        return awbModes;
    }
    
    /**
     * Convertir tiempo de exposición en nanosegundos a milisegundos para visualización
     */
    public static double exposureTimeNsToMs(long exposureTimeNs) {
        return exposureTimeNs / 1_000_000.0;
    }
    
    /**
     * Convertir tiempo de exposición en nanosegundos a fracciones de segundo (1/x)
     */
    public static String exposureTimeNsToFraction(long exposureTimeNs) {
        if (exposureTimeNs == 0) return "0";
        double fraction = 1_000_000_000.0 / exposureTimeNs;
        if (fraction < 1) {
            return String.format("%.2f\"", 1.0 / fraction);
        } else {
            return String.format("1/%.0f", fraction);
        }
    }
    
    /**
     * Obtener nombre legible del modo AWB
     */
    public static String getAWBModeName(int mode) {
        switch (mode) {
            case CaptureRequest.CONTROL_AWB_MODE_OFF:
                return "OFF";
            case CaptureRequest.CONTROL_AWB_MODE_AUTO:
                return "AUTO";
            case CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT:
                return "INCANDESCENT";
            case CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT:
                return "FLUORESCENT";
            case CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT:
                return "WARM_FLUORESCENT";
            case CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT:
                return "DAYLIGHT";
            case CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT:
                return "CLOUDY_DAYLIGHT";
            case CaptureRequest.CONTROL_AWB_MODE_TWILIGHT:
                return "TWILIGHT";
            case CaptureRequest.CONTROL_AWB_MODE_SHADE:
                return "SHADE";
            default:
                return "UNKNOWN";
        }
    }
}

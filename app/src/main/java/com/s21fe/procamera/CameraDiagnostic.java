package com.s21fe.procamera;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.util.Log;
import java.util.Arrays;

public class CameraDiagnostic {
    private static final String TAG = "CameraDiagnostic";

    public static void logCameraStats(Context context) {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                float[] focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                
                Log.d(TAG, "Camera ID: " + cameraId + 
                           " | Facing: " + (facing == CameraCharacteristics.LENS_FACING_BACK ? "BACK" : "FRONT") +
                           " | Focals: " + Arrays.toString(focalLengths));
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    for (String physicalId : chars.getPhysicalCameraIds()) {
                        CameraCharacteristics pChars = manager.getCameraCharacteristics(physicalId);
                        float[] pFocals = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                        Log.d(TAG, "  -> Physical ID: " + physicalId + " | Focals: " + Arrays.toString(pFocals));
                    }
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
}

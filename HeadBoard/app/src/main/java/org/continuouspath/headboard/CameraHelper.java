/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.continuouspath.headboard;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.lifecycle.LifecycleOwner;

/** The camera manager of the HeadBoard app. */
public final class CameraHelper {
  static final String TAG = "CameraHelper";
  public static void bindPreview(
      @NonNull ProcessCameraProvider cameraProvider,
      PreviewView previewView,
      ImageAnalysis imageAnalyzer,
      LifecycleOwner lifecycleOwner,
      boolean includePreview) {

    cameraProvider.unbindAll();

    CameraSelector cameraSelector =
        new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_FRONT).build();

    if (!includePreview) {
      // Nobody is looking (box minimized / sleep cover up): skip the whole Preview use
      // case — a second camera stream + surface composition for invisible pixels.
      cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalyzer);
      return;
    }

    Preview preview =
        new Preview.Builder()
            .setResolutionSelector(
                new ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build())
            .build();

    preview.setSurfaceProvider(previewView.getSurfaceProvider());
    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalyzer);
  }

  /**
   * Pick the lowest-power AE target FPS range the front camera supports, for throttled
   * states (sleep tiers / pause). Returns null when nothing below 30fps exists — in that
   * case software frame-dropping is the only option and rebinding buys nothing.
   */
  public static android.util.Range<Integer> pickLowFpsRange(Context context) {
    CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    try {
      for (String cameraId : cameraManager.getCameraIdList()) {
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
        Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        if (facing == null || facing != CameraCharacteristics.LENS_FACING_FRONT) {
          continue;
        }
        android.util.Range<Integer>[] ranges =
            characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) {
          return null;
        }
        android.util.Range<Integer> best = null;
        for (android.util.Range<Integer> range : ranges) {
          if (best == null
              || range.getUpper() < best.getUpper()
              || (range.getUpper().equals(best.getUpper()) && range.getLower() < best.getLower())) {
            best = range;
          }
        }
        if (best != null && best.getUpper() < 30) {
          Log.i(TAG, "pickLowFpsRange: using " + best);
          return best;
        }
        Log.i(TAG, "pickLowFpsRange: no sub-30fps range available (" + java.util.Arrays.toString(ranges) + ")");
        return null;
      }
    } catch (CameraAccessException e) {
      Log.w(TAG, "pickLowFpsRange failed: " + e.getMessage());
    }
    return null;
  }

  /**
   * Check the orientation of the front camera (usually 270 degrees).
   * @param context Context from main service.
   * @return The orientation degrees of the front camera.
   */
  public static int checkFrontCameraOrientation(Context context) {
    CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

    try {
      String[] cameraList = cameraManager.getCameraIdList();
      for (String availableCameraId : cameraList) {

        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(availableCameraId);
        boolean isFrontCamera = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT;

        if (isFrontCamera) {
          int orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
          Log.i(TAG, "checkFrontCameraOrientation: " + orientation);
          return orientation;
        }

      }
    } catch (CameraAccessException e) {
      throw new RuntimeException(e);
    }

    return 0;
  }

  private CameraHelper() {}
}

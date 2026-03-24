package com.nativephp.camera

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeFunction

/**
 * Functions related to camera operations
 * Namespace: "Camera.*"
 */
object CameraFunctions {

    /**
     * Capture a photo with the device camera
     * Parameters:
     *   - id: (optional) string - Optional ID to track this specific photo capture
     *   - event: (optional) string - Custom event class to fire (defaults to "NativePHP\Camera\Events\PhotoTaken")
     * Returns:
     *   - (empty map - results are returned via events)
     * Events:
     *   - Fires "NativePHP\Camera\Events\PhotoTaken" (or custom event) when photo is captured
     *   - Fires "NativePHP\Camera\Events\PhotoCancelled" (or custom event) when user cancels
     */
    /**
     * Capture a photo with the device camera
     * Parameters:
     *   - id: (optional) string - Optional ID to track this specific photo capture
     *   - event: (optional) string - Custom event class to fire (defaults to "NativePHP\Camera\Events\PhotoTaken")
     *   - watermark: (optional) object - Watermark options to overlay on the captured photo
     *       - text: string - Text to draw as watermark
     *       - position: (optional) string - "bottom-right" (default), "bottom-left", "top-right", "top-left", "center"
     *       - color: (optional) string - Hex color e.g. "#FFFFFF" (default)
     *       - size: (optional) number - Font size in pixels (default: 48)
     *       - opacity: (optional) number - 0.0 to 1.0 (default: 0.7)
     */
    class GetPhoto(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = parameters["id"] as? String
            val event = parameters["event"] as? String
            @Suppress("UNCHECKED_CAST")
            val watermark = parameters["watermark"] as? Map<String, Any>

            Log.d("CameraFunctions.GetPhoto", "📸 Capturing photo with id=$id, event=$event, watermark=${watermark != null}")

            // Launch camera on UI thread
            Handler(Looper.getMainLooper()).post {
                try {
                    val coord = CameraCoordinator.install(activity)
                    coord.launchCamera(id, event, watermark)
                } catch (e: Exception) {
                    Log.e("CameraFunctions.GetPhoto", "❌ Error launching camera: ${e.message}", e)
                }
            }

            return emptyMap()
        }
    }

    /**
     * Record a video with the device camera
     * Parameters:
     *   - maxDuration: (optional) int - Maximum recording duration in seconds
     *   - id: (optional) string - Optional ID to track this specific video recording
     *   - event: (optional) string - Custom event class to fire (defaults to "NativePHP\Camera\Events\VideoRecorded")
     * Returns:
     *   - (empty map - results are returned via events)
     * Events:
     *   - Fires "NativePHP\Camera\Events\VideoRecorded" (or custom event) when video is captured
     *   - Fires "NativePHP\Camera\Events\VideoCancelled" (or custom event) when user cancels
     */
    class RecordVideo(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val maxDuration = (parameters["maxDuration"] as? Number)?.toInt()
            val id = parameters["id"] as? String
            val event = parameters["event"] as? String

            Log.d("CameraFunctions.RecordVideo", "🎥 Recording video with maxDuration=$maxDuration, id=$id, event=$event")

            // Launch video recorder on UI thread
            Handler(Looper.getMainLooper()).post {
                try {
                    val coord = CameraCoordinator.install(activity)
                    coord.launchVideoRecorder(maxDuration, id, event)
                } catch (e: Exception) {
                    Log.e("CameraFunctions.RecordVideo", "❌ Error launching video recorder: ${e.message}", e)
                }
            }

            return emptyMap()
        }
    }
}
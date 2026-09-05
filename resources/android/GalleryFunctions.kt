package com.nativephp.camera

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeFunction

/**
 * Functions related to gallery/media picker operations
 * Namespace: "Camera.*" (gallery operations are part of Camera namespace)
 */
object GalleryFunctions {

    /**
     * Pick media from the device gallery
     * Parameters:
     *   - mediaType: (optional) string - Type of media to pick: "image", "video", or "all" (default: "all")
     *   - multiple: (optional) boolean - Allow multiple selection (default: false)
     *   - maxItems: (optional) int - Maximum number of items when multiple=true (default: 10)
     *   - id: (optional) string - Optional ID to track this operation
     *   - event: (optional) string - Custom event class to fire (defaults to "NativePHP\Camera\Events\MediaSelected")
     *   - includeLocation: (optional) boolean - Recover GPS coordinates of picked images. Requests the
     *     ACCESS_MEDIA_LOCATION runtime permission on API 29+ (default: false, no new prompt)
     * Returns:
     *   - (empty map - results are returned via events)
     * Events:
     *   - Fires "NativePHP\Camera\Events\MediaSelected" (or custom event) when media is selected or cancelled
     */
    class PickMedia(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val mediaType = parameters["mediaType"] as? String ?: "all"
            val multiple = parameters["multiple"] as? Boolean ?: false
            val maxItems = (parameters["maxItems"] as? Number)?.toInt() ?: 10
            val id = parameters["id"] as? String
            val event = parameters["event"] as? String
            val includeLocation = parameters["includeLocation"] as? Boolean ?: false

            Log.d("GalleryFunctions.PickMedia", "🖼️ Picking media with mediaType=$mediaType, multiple=$multiple, maxItems=$maxItems, id=$id, event=$event, includeLocation=$includeLocation")

            // Launch gallery on UI thread
            Handler(Looper.getMainLooper()).post {
                try {
                    val coord = CameraCoordinator.install(activity)
                    coord.launchGallery(mediaType, multiple, maxItems, id, event, includeLocation)
                } catch (e: Exception) {
                    Log.e("GalleryFunctions.PickMedia", "❌ Error launching gallery: ${e.message}", e)
                }
            }

            return emptyMap()
        }
    }
}

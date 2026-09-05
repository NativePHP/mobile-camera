package com.nativephp.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Coordinator for camera, video recording, and gallery operations.
 * Installed as a headless fragment to handle activity results.
 */
class CameraCoordinator : Fragment() {

    companion object {
        private const val TAG = "CameraCoordinator"
        private const val FRAGMENT_TAG = "CameraCoordinator"
        private const val PENDING_CAMERA_URI_KEY = "pending_camera_uri"
        private const val PENDING_VIDEO_URI_KEY = "pending_video_uri"

        fun install(activity: FragmentActivity): CameraCoordinator {
            val fm = activity.supportFragmentManager
            var coordinator = fm.findFragmentByTag(FRAGMENT_TAG) as? CameraCoordinator

            if (coordinator == null) {
                coordinator = CameraCoordinator()
                fm.beginTransaction()
                    .add(coordinator, FRAGMENT_TAG)
                    .commitNow()
            }

            return coordinator
        }
    }

    // Camera state
    private var pendingCameraUri: Uri? = null
    private var pendingPhotoId: String? = null
    private var pendingPhotoEvent: String? = null
    private var pendingCameraOperation: String? = null

    // Video state
    private var pendingVideoUri: Uri? = null
    private var pendingVideoId: String? = null
    private var pendingVideoEvent: String? = null
    private var pendingMaxDuration: Int? = null
    @Volatile
    private var isVideoRecording = false

    // Gallery state
    private var pendingGalleryId: String? = null
    private var pendingGalleryEvent: String? = null
    // Retained gallery launch arguments so the picker can be re-launched after
    // resolving the ACCESS_MEDIA_LOCATION runtime permission.
    private var pendingGalleryMediaType: String? = null
    private var pendingGalleryMultiple: Boolean = false
    private var pendingGalleryMaxItems: Int = 10
    // Opt-in: recover un-redacted GPS for picked media (requires ACCESS_MEDIA_LOCATION).
    // Defaults to false so existing users never see a new permission prompt.
    private var pendingGalleryIncludeLocation: Boolean = false

    // Background processing
    private var fileProcessingExecutor: ExecutorService? = null

    // Activity result launchers
    private lateinit var cameraLauncher: ActivityResultLauncher<Uri>
    private lateinit var videoRecorderLauncher: ActivityResultLauncher<Intent>
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var mediaLocationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var galleryPickerSingle: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var galleryPickerMultiple: ActivityResultLauncher<PickVisualMediaRequest>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize single-threaded executor for file processing
        fileProcessingExecutor = Executors.newSingleThreadExecutor()

        // Restore pending URIs if saved
        savedInstanceState?.let { bundle ->
            bundle.getString(PENDING_CAMERA_URI_KEY)?.let {
                pendingCameraUri = Uri.parse(it)
                Log.d(TAG, "📸 Restored pendingCameraUri: $pendingCameraUri")
            }
            bundle.getString(PENDING_VIDEO_URI_KEY)?.let {
                pendingVideoUri = Uri.parse(it)
                Log.d(TAG, "🎥 Restored pendingVideoUri: $pendingVideoUri")
            }
        }

        // Camera permission launcher
        cameraPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            Log.d(TAG, "📸 Camera permission result: $granted, operation: $pendingCameraOperation")

            if (granted) {
                when (pendingCameraOperation) {
                    "photo" -> proceedWithCameraCapture()
                    "video" -> proceedWithVideoRecording(pendingMaxDuration)
                    else -> {
                        Log.e(TAG, "❌ Unknown camera operation: $pendingCameraOperation")
                        proceedWithCameraCapture()
                    }
                }
                pendingCameraOperation = null
                pendingMaxDuration = null
            } else {
                // Permission denied - dispatch event to let app handle it
                Log.e(TAG, "❌ Camera permission denied")

                val action = if (pendingCameraOperation == "video") "video" else "photo"
                val id = if (pendingCameraOperation == "video") pendingVideoId else pendingPhotoId

                val payload = JSONObject().apply {
                    put("action", action)
                    id?.let { put("id", it) }
                }

                dispatchEvent("Native\\Mobile\\Events\\Camera\\PermissionDenied", payload.toString())

                // Clean up
                if (pendingCameraOperation == "video") {
                    isVideoRecording = false
                    pendingVideoId = null
                    pendingVideoEvent = null
                } else {
                    pendingPhotoId = null
                    pendingPhotoEvent = null
                }

                pendingCameraOperation = null
                pendingMaxDuration = null
            }
        }

        // Media location permission launcher (ACCESS_MEDIA_LOCATION).
        // Whatever the user decides, we proceed to launch the gallery picker; the
        // permission only controls whether un-redacted GPS metadata can be recovered.
        mediaLocationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            Log.d(TAG, "🖼️ Media location permission result: $granted")
            launchGalleryPicker()
        }

        // Camera launcher for photos
        cameraLauncher = registerForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { success ->
            // Guard against fragment detachment
            if (!isAdded || context == null) {
                Log.e(TAG, "Fragment not attached, ignoring camera result")
                context?.let { CameraForegroundService.stop(it) }
                return@registerForActivityResult
            }

            CameraForegroundService.stop(requireContext())

            Log.d(TAG, "📸 cameraLauncher callback triggered. Success: $success")

            val context = requireContext()
            val eventClass = pendingPhotoEvent ?: "Native\\Mobile\\Events\\Camera\\PhotoTaken"
            val cancelEventClass = "Native\\Mobile\\Events\\Camera\\PhotoCancelled"

            if (success && pendingCameraUri != null) {
                // Snapshot pending state before handing off to the background thread so the
                // launcher can clean up immediately without racing the worker.
                val sourceUri = pendingCameraUri!!
                val photoId = pendingPhotoId

                // Copy + EXIF reading is file IO, so run it off the main thread.
                fileProcessingExecutor?.execute {
                    val dst = File(context.cacheDir, "captured_${System.currentTimeMillis()}.jpg")

                    try {
                        context.contentResolver.openInputStream(sourceUri)?.use { input ->
                            dst.outputStream().buffered(64 * 1024).use { output ->
                                input.copyTo(output)
                            }
                        }
                        // Clean up MediaStore entry
                        try {
                            context.contentResolver.delete(sourceUri, null, null)
                        } catch (e: Exception) {
                            Log.w(TAG, "⚠️ Could not delete MediaStore entry: ${e.message}")
                        }

                        val payload = JSONObject().apply {
                            put("path", dst.absolutePath)
                            put("mimeType", "image/jpeg")
                            photoId?.let { put("id", it) }
                        }

                        // Attach capture date + GPS from the embedded EXIF. The date falls
                        // back to the current time so takenAt is essentially always present.
                        attachImageMetadata(payload, dst.absolutePath, fallbackToNow = true)

                        activity?.runOnUiThread {
                            dispatchEvent(eventClass, payload.toString())
                            Log.d(TAG, "✅ Photo captured successfully: ${dst.absolutePath}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error processing camera photo: ${e.message}", e)

                        activity?.runOnUiThread {
                            Toast.makeText(context, "Failed to save photo", Toast.LENGTH_SHORT).show()

                            val payload = JSONObject().apply {
                                put("cancelled", true)
                                photoId?.let { put("id", it) }
                            }
                            dispatchEvent(cancelEventClass, payload.toString())
                        }
                    }
                }
            } else {
                Log.d(TAG, "⚠️ Camera capture was canceled or failed")
                val payload = JSONObject().apply {
                    put("cancelled", true)
                    pendingPhotoId?.let { put("id", it) }
                }
                dispatchEvent(cancelEventClass, payload.toString())
            }

            // Clean up
            pendingCameraUri = null
            pendingPhotoId = null
            pendingPhotoEvent = null
        }

        // Video recorder launcher
        videoRecorderLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            // Guard against fragment detachment
            if (!isAdded || context == null) {
                Log.e(TAG, "Fragment not attached, ignoring video result")
                isVideoRecording = false
                return@registerForActivityResult
            }

            CameraForegroundService.stop(requireContext())

            Log.d(TAG, "🎥 videoRecorderLauncher callback triggered. Result code: ${result.resultCode}")

            val context = requireContext()
            val eventClass = pendingVideoEvent ?: "Native\\Mobile\\Events\\Camera\\VideoRecorded"
            val cancelEventClass = "Native\\Mobile\\Events\\Camera\\VideoCancelled"

            if (result.resultCode == android.app.Activity.RESULT_OK && pendingVideoUri != null) {
                try {
                    val filePath = getVideoPathFromUri(pendingVideoUri!!)

                    if (filePath != null) {
                        val payload = JSONObject().apply {
                            put("path", filePath)
                            put("mimeType", "video/mp4")
                            pendingVideoId?.let { put("id", it) }
                        }

                        dispatchEvent(eventClass, payload.toString())
                        Log.d(TAG, "✅ Video recorded successfully: $filePath")
                    } else {
                        Log.e(TAG, "❌ Failed to get video file path from URI")
                        cleanupVideoUri(context)

                        val payload = JSONObject().apply {
                            put("cancelled", true)
                            pendingVideoId?.let { put("id", it) }
                        }
                        dispatchEvent(cancelEventClass, payload.toString())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error processing video: ${e.message}", e)
                    cleanupVideoUri(context)

                    val payload = JSONObject().apply {
                        put("cancelled", true)
                        pendingVideoId?.let { put("id", it) }
                    }
                    dispatchEvent(cancelEventClass, payload.toString())
                }
            } else {
                Log.d(TAG, "⚠️ Video recording was canceled")
                cleanupVideoUri(context)

                val payload = JSONObject().apply {
                    put("cancelled", true)
                    pendingVideoId?.let { put("id", it) }
                }
                dispatchEvent(cancelEventClass, payload.toString())
            }

            // Clean up
            pendingVideoUri = null
            pendingVideoId = null
            pendingVideoEvent = null
            isVideoRecording = false
        }

        // Single gallery picker
        galleryPickerSingle = registerForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            // Guard against fragment detachment
            if (!isAdded || context == null) {
                Log.e(TAG, "Fragment not attached, ignoring gallery result")
                return@registerForActivityResult
            }

            Log.d(TAG, "📸 Single gallery picker callback triggered")
            Log.d(TAG, "🔍 Received URI: $uri")

            // Use default event if not provided
            val eventClass = pendingGalleryEvent ?: "Native\\Mobile\\Events\\Gallery\\MediaSelected"

            if (uri != null) {
                Log.d(TAG, "✅ Single gallery picker - URI received successfully")
                Log.d(TAG, "📂 URI scheme: ${uri.scheme}")
                Log.d(TAG, "📂 URI authority: ${uri.authority}")
                Log.d(TAG, "📂 URI path: ${uri.path}")

                Log.d(TAG, "📁 Processing single file - moving to background thread")

                // Process file using executor service to prevent unbounded thread creation
                fileProcessingExecutor?.execute {
                    try {
                        val context = requireContext()
                        val timestamp = System.currentTimeMillis()

                        // Use Gallery subfolder in cache directory
                        val galleryDir = File(context.cacheDir, "Gallery")
                        galleryDir.mkdirs()

                        val dst = File(galleryDir, "gallery_selected_$timestamp")

                        Log.d(TAG, "🧵 Background copying file to cache")

                        // Use buffered streams with 64KB buffer for better performance
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            dst.outputStream().buffered(64 * 1024).use { output ->
                                input.copyTo(output)
                            }
                        }

                        Log.d(TAG, "✅ File copied successfully")

                        // Get file metadata
                        val fileMetadata = getFileMetadata(uri, dst.absolutePath)
                        val filesArray = JSONArray()
                        filesArray.put(fileMetadata)

                        val payload = JSONObject().apply {
                            put("success", true)
                            put("files", filesArray)
                            put("count", 1)
                            pendingGalleryId?.let { put("id", it) }
                        }

                        // Dispatch on main thread
                        activity?.runOnUiThread {
                            Log.d(TAG, "📤 Dispatching $eventClass event with payload: ${payload.toString()}")
                            dispatchEvent(eventClass, payload.toString())
                            Log.d(TAG, "✅ Single gallery picker - Event dispatched successfully")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error processing gallery file in background: ${e.message}", e)

                        activity?.runOnUiThread {
                            val payload = JSONObject().apply {
                                put("success", false)
                                put("files", JSONArray())
                                put("count", 0)
                                put("error", "Failed to process file: ${e.message}")
                            }
                            dispatchEvent("Native\\Mobile\\Events\\Gallery\\MediaSelected", payload.toString())
                        }
                    }
                }
            } else {
                Log.d(TAG, "⚠️ Gallery picker was cancelled - URI is null")
                Log.d(TAG, "❌ Single gallery picker - No file selected or operation cancelled")

                val payload = JSONObject().apply {
                    put("success", false)
                    put("files", JSONArray())
                    put("count", 0)
                    put("cancelled", true)
                    pendingGalleryId?.let { put("id", it) }
                }

                Log.d(TAG, "📤 Dispatching $eventClass event (cancelled) with payload: ${payload.toString()}")
                dispatchEvent(eventClass, payload.toString())
                Log.d(TAG, "✅ Single gallery picker - Cancellation event dispatched successfully")
            }

            // Clean up pending state
            pendingGalleryId = null
            pendingGalleryEvent = null
        }

        // Multiple gallery picker
        galleryPickerMultiple = registerForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(10)
        ) { uris ->
            // Guard against fragment detachment
            if (!isAdded || context == null) {
                Log.e(TAG, "Fragment not attached, ignoring gallery result")
                return@registerForActivityResult
            }

            Log.d(TAG, "📸 Multiple gallery picker callback triggered with ${uris.size} items")

            // Use default event if not provided
            val eventClass = pendingGalleryEvent ?: "Native\\Mobile\\Events\\Gallery\\MediaSelected"

            if (uris.isNotEmpty()) {
                Log.d(TAG, "📁 Processing ${uris.size} files - moving to background thread")

                // Process files using executor service to prevent unbounded thread creation
                fileProcessingExecutor?.execute {
                    try {
                        val context = requireContext()
                        val filesArray = JSONArray()
                        val timestamp = System.currentTimeMillis()

                        Log.d(TAG, "🧵 Background processing ${uris.size} files")

                        // Use Gallery subfolder in cache directory
                        val galleryDir = File(context.cacheDir, "Gallery")
                        galleryDir.mkdirs()

                        uris.forEachIndexed { index, uri ->
                            // Only log every few files to reduce output
                            if (index == 0 || (index + 1) % 3 == 0 || index == uris.size - 1) {
                                Log.d(TAG, "📂 Processing file ${index + 1}/${uris.size}")
                            }

                            val dst = File(galleryDir, "gallery_selected_${timestamp}_$index")

                            // Use buffered streams with 64KB buffer for better performance
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                dst.outputStream().buffered(64 * 1024).use { output ->
                                    input.copyTo(output)
                                }
                            }

                            // Get file metadata and add to array
                            val fileMetadata = getFileMetadata(uri, dst.absolutePath)
                            filesArray.put(fileMetadata)
                        }

                        Log.d(TAG, "✅ All ${uris.size} files processed successfully")

                        val payload = JSONObject().apply {
                            put("success", true)
                            put("files", filesArray)
                            put("count", uris.size)
                            pendingGalleryId?.let { put("id", it) }
                        }

                        // Dispatch on main thread
                        activity?.runOnUiThread {
                            Log.d(TAG, "📤 Dispatching $eventClass event with ${uris.size} files")
                            dispatchEvent(eventClass, payload.toString())
                            Log.d(TAG, "✅ Multiple gallery picker - Event dispatched successfully")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error processing gallery files in background: ${e.message}", e)

                        activity?.runOnUiThread {
                            val payload = JSONObject().apply {
                                put("success", false)
                                put("files", JSONArray())
                                put("count", 0)
                                put("error", "Failed to process files: ${e.message}")
                                pendingGalleryId?.let { put("id", it) }
                            }
                            dispatchEvent(eventClass, payload.toString())
                        }
                    }
                }
            } else {
                Log.d(TAG, "⚠️ Gallery picker was cancelled or no files selected")
                val payload = JSONObject().apply {
                    put("success", false)
                    put("files", JSONArray())
                    put("count", 0)
                    put("cancelled", true)
                    pendingGalleryId?.let { put("id", it) }
                }
                dispatchEvent(eventClass, payload.toString())
            }

            // Clean up pending state
            pendingGalleryId = null
            pendingGalleryEvent = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingCameraUri?.let { outState.putString(PENDING_CAMERA_URI_KEY, it.toString()) }
        pendingVideoUri?.let { outState.putString(PENDING_VIDEO_URI_KEY, it.toString()) }
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingCameraUri = null
        pendingVideoUri = null
        fileProcessingExecutor?.shutdown()
        Log.d(TAG, "🧹 Fragment destroyed and resources cleaned up")
    }

    fun launchCamera(id: String? = null, event: String? = null) {
        val context = requireContext()

        Log.d(TAG, "📸 launchCamera called - id=$id, event=$event")

        pendingPhotoId = id
        pendingPhotoEvent = event

        val cameraPermissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!cameraPermissionGranted) {
            Log.d(TAG, "📸 Camera permission not granted, requesting permission")
            pendingCameraOperation = "photo"
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        proceedWithCameraCapture()
    }

    private fun proceedWithCameraCapture() {
        val context = requireContext()
        val resolver = context.contentResolver

        val photoUri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.TITLE, "NativePHP_${System.currentTimeMillis()}")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }
        ) ?: run {
            Log.e(TAG, "❌ Failed to create camera URI")
            Toast.makeText(context, "Failed to prepare camera", Toast.LENGTH_SHORT).show()
            return
        }

        pendingCameraUri = photoUri
        Log.d(TAG, "📸 Camera URI created: $pendingCameraUri")

        CameraForegroundService.start(context)
        Log.d(TAG, "📸 Started foreground service for photo capture")

        cameraLauncher.launch(photoUri)
    }

    fun launchVideoRecorder(maxDuration: Int?, id: String? = null, event: String? = null) {
        val context = requireContext()

        synchronized(this) {
            if (isVideoRecording) {
                Log.w(TAG, "⚠️ Video recording already in progress, ignoring request")
                Toast.makeText(context, "Video recording already in progress", Toast.LENGTH_SHORT).show()
                return
            }
            isVideoRecording = true
        }

        Log.d(TAG, "🎥 launchVideoRecorder called - maxDuration=$maxDuration, id=$id, event=$event")

        pendingVideoId = id
        pendingVideoEvent = event

        val cameraPermissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!cameraPermissionGranted) {
            Log.d(TAG, "🎥 Camera permission not granted, requesting permission")
            pendingCameraOperation = "video"
            pendingMaxDuration = maxDuration
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        proceedWithVideoRecording(maxDuration)
    }

    private fun proceedWithVideoRecording(maxDuration: Int?) {
        val context = requireContext()
        val resolver = context.contentResolver

        Log.d(TAG, "🎥 proceedWithVideoRecording - creating MediaStore URI")

        val videoUri = resolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Video.Media.TITLE, "NativePHP_${System.currentTimeMillis()}")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            }
        ) ?: run {
            Log.e(TAG, "❌ Failed to create video URI")
            Toast.makeText(context, "Failed to prepare video recorder", Toast.LENGTH_SHORT).show()
            isVideoRecording = false
            return
        }

        pendingVideoUri = videoUri
        Log.d(TAG, "🎥 Video URI created: $pendingVideoUri")

        CameraForegroundService.start(context)
        Log.d(TAG, "🎥 Started foreground service")

        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, videoUri)
            maxDuration?.let {
                putExtra(MediaStore.EXTRA_DURATION_LIMIT, it)
                Log.d(TAG, "🎥 Max duration set: $it seconds")
            }
        }

        Log.d(TAG, "🎥 Launching video recorder intent")
        videoRecorderLauncher.launch(intent)
    }

    fun launchGallery(
        mediaType: String,
        multiple: Boolean,
        maxItems: Int,
        id: String? = null,
        event: String? = null,
        includeLocation: Boolean = false
    ) {
        Log.d(TAG, "🖼️ launchGallery: mediaType=$mediaType, multiple=$multiple, maxItems=$maxItems, id=$id, event=$event, includeLocation=$includeLocation")

        pendingGalleryId = id
        pendingGalleryEvent = event
        pendingGalleryMediaType = mediaType
        pendingGalleryMultiple = multiple
        pendingGalleryMaxItems = maxItems
        pendingGalleryIncludeLocation = includeLocation

        // The Photo Picker redacts GPS metadata unless we read the original bytes via
        // MediaStore, which requires ACCESS_MEDIA_LOCATION (a runtime permission on API 29+).
        // Only when the caller opted in (includeLocation=true) do we request it up-front,
        // mirroring the CAMERA permission flow. Whatever the result, we still launch the
        // picker; location recovery just degrades gracefully.
        if (includeLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val context = requireContext()
            val mediaLocationGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!mediaLocationGranted) {
                Log.d(TAG, "🖼️ ACCESS_MEDIA_LOCATION not granted, requesting permission")
                mediaLocationPermissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                return
            }
        }

        launchGalleryPicker()
    }

    private fun launchGalleryPicker() {
        val visualMediaType = when ((pendingGalleryMediaType ?: "all").lowercase()) {
            "image", "images" -> ActivityResultContracts.PickVisualMedia.ImageOnly
            "video", "videos" -> ActivityResultContracts.PickVisualMedia.VideoOnly
            "all", "*" -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
            else -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
        }

        Log.d(TAG, "📂 Using visual media type: $visualMediaType")

        if (pendingGalleryMultiple) {
            Log.d(TAG, "🚀 Launching multiple gallery picker")
            val request = PickVisualMediaRequest.Builder()
                .setMediaType(visualMediaType)
                .build()
            galleryPickerMultiple.launch(request)
        } else {
            Log.d(TAG, "🚀 Launching single gallery picker")
            val request = PickVisualMediaRequest.Builder()
                .setMediaType(visualMediaType)
                .build()
            galleryPickerSingle.launch(request)
        }
    }

    private fun cleanupVideoUri(context: android.content.Context) {
        pendingVideoUri?.let { uri ->
            try {
                context.contentResolver.delete(uri, null, null)
                Log.d(TAG, "🗑️ Deleted video URI")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Could not delete video URI: ${e.message}")
            }
        }
    }

    private fun getVideoPathFromUri(uri: Uri): String? {
        val context = requireContext()

        try {
            val timestamp = System.currentTimeMillis()
            val cacheFile = File(context.cacheDir, "video_$timestamp.mp4")

            context.contentResolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().buffered(64 * 1024).use { output ->
                    input.copyTo(output)
                }
            }

            // Clean up MediaStore entry after copying
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Could not delete MediaStore entry: ${e.message}")
            }

            return cacheFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error copying video from URI: ${e.message}", e)
        }

        return null
    }

    private fun getFileMetadata(uri: Uri, cachePath: String): JSONObject {
        val context = requireContext()
        val metadata = JSONObject()

        try {
            // Get MIME type
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

            // Determine file extension from MIME type
            val extension = when {
                mimeType.startsWith("image/jpeg") -> "jpg"
                mimeType.startsWith("image/png") -> "png"
                mimeType.startsWith("image/gif") -> "gif"
                mimeType.startsWith("image/webp") -> "webp"
                mimeType.startsWith("video/mp4") -> "mp4"
                mimeType.startsWith("video/avi") -> "avi"
                mimeType.startsWith("video/mov") -> "mov"
                mimeType.startsWith("video/3gp") -> "3gp"
                mimeType.startsWith("video/webm") -> "webm"
                else -> {
                    // Try to extract from MIME type
                    val parts = mimeType.split("/")
                    if (parts.size == 2) parts[1] else "bin"
                }
            }

            // Determine file type category
            val type = when {
                mimeType.startsWith("image/") -> "image"
                mimeType.startsWith("video/") -> "video"
                mimeType.startsWith("audio/") -> "audio"
                else -> "other"
            }

            metadata.apply {
                put("path", cachePath)
                put("mimeType", mimeType)
                put("extension", extension)
                put("type", type)
            }

            // For images, attach capture date + GPS from the file's EXIF. The Photo Picker
            // redacts GPS, so when the caller opted in we also attempt to recover the original
            // (un-redacted) bytes via MediaStore.
            if (type == "image") {
                attachImageMetadata(
                    metadata,
                    cachePath,
                    fallbackToNow = false,
                    sourceUri = uri,
                    recoverOriginal = pendingGalleryIncludeLocation
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error getting file metadata", e)
            // Fallback metadata
            metadata.apply {
                put("path", cachePath)
                put("mimeType", "application/octet-stream")
                put("extension", "bin")
                put("type", "other")
            }
        }

        return metadata
    }

    /**
     * Read capture date + GPS metadata from an image and merge it into [payload] using the
     * shared cross-platform payload contract:
     *  - takenAt: ISO-8601 UTC string (yyyy-MM-dd'T'HH:mm:ss'Z'), omitted when unavailable.
     *  - latitude / longitude: Double decimal degrees, omitted when unavailable.
     *
     * The capture date is read from the copied file's EXIF (TAG_DATETIME_ORIGINAL, falling back
     * to TAG_DATETIME), then MediaStore DATE_TAKEN, then optionally the current time.
     *
     * GPS is read from the copied file's EXIF first. The Photo Picker redacts location, so when a
     * [sourceUri] is supplied and [recoverOriginal] is true we additionally try to recover the
     * original (un-redacted) bytes via MediaStore.setRequireOriginal (requires
     * ACCESS_MEDIA_LOCATION on API 29+). This is opt-in (bridge parameter `includeLocation`).
     *
     * All access is best-effort: any failure simply omits the affected key and never throws.
     */
    private fun attachImageMetadata(
        payload: JSONObject,
        cachePath: String,
        fallbackToNow: Boolean,
        sourceUri: Uri? = null,
        recoverOriginal: Boolean = false
    ) {
        val context = context ?: return

        var takenAtMillis: Long? = null
        var latitude: Double? = null
        var longitude: Double? = null

        // 1. Read EXIF from the copied cache file.
        try {
            val exif = ExifInterface(cachePath)

            takenAtMillis = parseExifDate(
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            )

            exif.latLong?.let { latLong ->
                latitude = latLong[0]
                longitude = latLong[1]
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not read EXIF from cache file: ${e.message}")
        }

        // 2. The Photo Picker redacts GPS, so (when opted in) recover it from the original bytes.
        if (recoverOriginal && (latitude == null || longitude == null) && sourceUri != null) {
            recoverOriginalLatLong(context, sourceUri)?.let { latLong ->
                latitude = latLong[0]
                longitude = latLong[1]
            }

            // While we have the original stream open, also try to recover the EXIF date if missing.
            if (takenAtMillis == null) {
                takenAtMillis = recoverOriginalExifDate(context, sourceUri)
            }
        }

        // 3. Fall back to MediaStore DATE_TAKEN for the capture date.
        if (takenAtMillis == null && sourceUri != null) {
            takenAtMillis = queryMediaStoreDateTaken(context, sourceUri)
        }

        // 4. Final fallback to the current time (camera capture only).
        if (takenAtMillis == null && fallbackToNow) {
            takenAtMillis = System.currentTimeMillis()
        }

        takenAtMillis?.let { payload.put("takenAt", formatUtcIso8601(it)) }
        latitude?.let { lat -> longitude?.let { lon ->
            payload.put("latitude", lat)
            payload.put("longitude", lon)
        } }
    }

    /**
     * Attempt to recover un-redacted GPS from the original MediaStore image, returning
     * [latitude, longitude] in decimal degrees or null if unavailable.
     */
    private fun recoverOriginalLatLong(context: Context, uri: Uri): DoubleArray? {
        return try {
            openOriginalStreamExif(context, uri)?.latLong
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not recover original GPS: ${e.message}")
            null
        }
    }

    /**
     * Attempt to recover the EXIF capture date from the original MediaStore image, in millis.
     */
    private fun recoverOriginalExifDate(context: Context, uri: Uri): Long? {
        return try {
            val exif = openOriginalStreamExif(context, uri) ?: return null
            parseExifDate(
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            )
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not recover original EXIF date: ${e.message}")
            null
        }
    }

    /**
     * Open an [ExifInterface] over the original (un-redacted) bytes of a MediaStore-backed uri.
     * Uses MediaStore.setRequireOriginal on API 29+, which needs ACCESS_MEDIA_LOCATION. Returns
     * null when the permission is missing or the uri is not MediaStore-backed.
     */
    private fun openOriginalStreamExif(context: Context, uri: Uri): ExifInterface? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val originalUri = try {
            MediaStore.setRequireOriginal(uri)
        } catch (e: Exception) {
            // Not a MediaStore uri (e.g. provider-backed) or original unavailable.
            return null
        }

        return context.contentResolver.openInputStream(originalUri)?.use { input ->
            ExifInterface(input)
        }
    }

    /**
     * Query MediaStore DATE_TAKEN (epoch millis) for the given uri, or null if unavailable.
     */
    private fun queryMediaStoreDateTaken(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.DATE_TAKEN),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                    if (index >= 0 && !cursor.isNull(index)) {
                        val value = cursor.getLong(index)
                        if (value > 0) value else null
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not query MediaStore DATE_TAKEN: ${e.message}")
            null
        }
    }

    /**
     * Parse an EXIF date string (yyyy:MM:dd HH:mm:ss, device-local) into epoch millis, or null.
     */
    private fun parseExifDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return try {
            val parser = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
            parser.parse(raw)?.time
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Format epoch millis as an ISO-8601 UTC string: yyyy-MM-dd'T'HH:mm:ss'Z'.
     */
    private fun formatUtcIso8601(millis: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return formatter.format(Date(millis))
    }

    private fun dispatchEvent(event: String, payloadJson: String) {
        NativeActionCoordinator.dispatchEvent(requireActivity(), event, payloadJson)
    }
}

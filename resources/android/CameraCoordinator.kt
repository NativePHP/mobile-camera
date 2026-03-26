package com.nativephp.camera

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    private var pendingWatermarkOptions: Map<String, Any>? = null

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

    // Background processing
    private var fileProcessingExecutor: ExecutorService? = null

    // Activity result launchers
    private lateinit var cameraLauncher: ActivityResultLauncher<Uri>
    private lateinit var videoRecorderLauncher: ActivityResultLauncher<Intent>
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
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
                val dst = File(context.cacheDir, "captured_${System.currentTimeMillis()}.jpg")

                try {
                    context.contentResolver.openInputStream(pendingCameraUri!!)?.use { input ->
                        dst.outputStream().buffered(64 * 1024).use { output ->
                            input.copyTo(output)
                        }
                    }
                    // Apply watermark if requested
                    pendingWatermarkOptions?.let { options ->
                        applyWatermarkToFile(dst, options)
                    }

                    val payload = JSONObject().apply {
                        put("path", dst.absolutePath)
                        put("mimeType", "image/jpeg")
                        pendingPhotoId?.let { put("id", it) }
                    }

                    dispatchEvent(eventClass, payload.toString())
                    Log.d(TAG, "✅ Photo captured successfully: ${dst.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error processing camera photo: ${e.message}", e)
                    Toast.makeText(context, "Failed to save photo", Toast.LENGTH_SHORT).show()

                    val payload = JSONObject().apply {
                        put("cancelled", true)
                        pendingPhotoId?.let { put("id", it) }
                    }
                    dispatchEvent(cancelEventClass, payload.toString())
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
            pendingWatermarkOptions = null
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

                        // Resolve extension before creating the destination file so the path
                        // returned to the app includes the correct extension (e.g. .jpg, .mp4).
                        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                        val ext = when {
                            mimeType.startsWith("image/jpeg") -> "jpg"
                            mimeType.startsWith("image/png") -> "png"
                            mimeType.startsWith("image/gif") -> "gif"
                            mimeType.startsWith("image/webp") -> "webp"
                            mimeType.startsWith("video/mp4") -> "mp4"
                            mimeType.startsWith("video/avi") -> "avi"
                            mimeType.startsWith("video/quicktime") -> "mov"
                            mimeType.startsWith("video/3gpp") -> "3gp"
                            mimeType.startsWith("video/webm") -> "webm"
                            else -> mimeType.split("/").getOrNull(1) ?: "bin"
                        }
                        val dst = File(galleryDir, "gallery_selected_${timestamp}.$ext")

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

                            // Resolve extension before creating the destination file so the path
                            // returned to the app includes the correct extension (e.g. .jpg, .mp4).
                            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                            val ext = when {
                                mimeType.startsWith("image/jpeg") -> "jpg"
                                mimeType.startsWith("image/png") -> "png"
                                mimeType.startsWith("image/gif") -> "gif"
                                mimeType.startsWith("image/webp") -> "webp"
                                mimeType.startsWith("video/mp4") -> "mp4"
                                mimeType.startsWith("video/avi") -> "avi"
                                mimeType.startsWith("video/quicktime") -> "mov"
                                mimeType.startsWith("video/3gpp") -> "3gp"
                                mimeType.startsWith("video/webm") -> "webm"
                                else -> mimeType.split("/").getOrNull(1) ?: "bin"
                            }
                            val dst = File(galleryDir, "gallery_selected_${timestamp}_${index}.$ext")

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

    fun launchCamera(id: String? = null, event: String? = null, watermark: Map<String, Any>? = null) {
        val context = requireContext()

        Log.d(TAG, "📸 launchCamera called - id=$id, event=$event, watermark=${watermark != null}")

        pendingPhotoId = id
        pendingPhotoEvent = event
        pendingWatermarkOptions = watermark

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

        val photoContentValues = ContentValues().apply {
            put(MediaStore.Images.Media.TITLE, "NativePHP_${System.currentTimeMillis()}")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            // RELATIVE_PATH places the file in DCIM/Camera (visible in Gallery).
            // IS_PENDING must NOT be set here: the system camera app is a different process
            // and cannot write to a pending entry owned by this app — it would return
            // RESULT_CANCELED, causing PhotoCancelled to fire instead of PhotoTaken.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
            }
        }

        val photoUri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            photoContentValues
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

        val videoContentValues = ContentValues().apply {
            put(MediaStore.Video.Media.TITLE, "NativePHP_${System.currentTimeMillis()}")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            // Same reasoning as photo capture: do not set IS_PENDING — the system camera app
            // (a different process) cannot write to a pending entry owned by this app.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
            }
        }

        val videoUri = resolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            videoContentValues
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

    fun launchGallery(mediaType: String, multiple: Boolean, maxItems: Int, id: String? = null, event: String? = null) {
        Log.d(TAG, "🖼️ launchGallery: mediaType=$mediaType, multiple=$multiple, maxItems=$maxItems, id=$id, event=$event")

        pendingGalleryId = id
        pendingGalleryEvent = event

        val visualMediaType = when (mediaType.lowercase()) {
            "image", "images" -> ActivityResultContracts.PickVisualMedia.ImageOnly
            "video", "videos" -> ActivityResultContracts.PickVisualMedia.VideoOnly
            "all", "*" -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
            else -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
        }

        Log.d(TAG, "📂 Using visual media type: $visualMediaType")

        if (multiple) {
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

    private fun applyWatermarkToFile(file: File, options: Map<String, Any>) {
        val text = options["text"] as? String ?: return
        val position = (options["position"] as? String ?: "bottom-right").lowercase()
        val colorHex = options["color"] as? String ?: "#FFFFFF"
        val fontSize = (options["size"] as? Number)?.toFloat() ?: 48f
        val opacity = (options["opacity"] as? Number)?.toFloat() ?: 0.7f

        try {
            val original = BitmapFactory.decodeFile(file.absolutePath) ?: return
            val mutable = original.copy(Bitmap.Config.ARGB_8888, true)
            original.recycle()

            val canvas = Canvas(mutable)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = parseWatermarkColor(colorHex)
                alpha = (opacity * 255).toInt().coerceIn(0, 255)
                textSize = fontSize
                setShadowLayer(3f, 1f, 1f, Color.BLACK)
            }

            val textWidth = paint.measureText(text)
            val padding = 32f

            val x: Float
            val y: Float
            when (position) {
                "top-left"     -> { x = padding;                             y = padding - paint.ascent() }
                "top-right"    -> { x = mutable.width - textWidth - padding; y = padding - paint.ascent() }
                "bottom-left"  -> { x = padding;                             y = mutable.height - padding }
                "center"       -> { x = (mutable.width - textWidth) / 2f;   y = (mutable.height + (paint.descent() - paint.ascent())) / 2f }
                else           -> { x = mutable.width - textWidth - padding; y = mutable.height - padding } // bottom-right
            }

            canvas.drawText(text, x, y, paint)

            file.outputStream().use { out ->
                mutable.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            mutable.recycle()

            Log.d(TAG, "🖼️ Watermark applied successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error applying watermark: ${e.message}", e)
        }
    }

    private fun parseWatermarkColor(hex: String): Int {
        return try {
            Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
        } catch (e: Exception) {
            Color.WHITE
        }
    }

    private fun dispatchEvent(event: String, payloadJson: String) {
        NativeActionCoordinator.dispatchEvent(requireActivity(), event, payloadJson)
    }
}

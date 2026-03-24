import Foundation
import UIKit
import AVFoundation
import UniformTypeIdentifiers
import PhotosUI

// MARK: - Camera Function Namespace

/// Functions related to camera operations
/// Namespace: "Camera.*"
enum CameraFunctions {

    // MARK: - Camera.GetPhoto

    /// Capture a photo with the device camera
    /// Parameters:
    ///   - id: (optional) string - Optional ID to track this specific photo capture
    ///   - event: (optional) string - Custom event class to fire (defaults to "Native\Mobile\Events\Camera\PhotoTaken")
    ///   - watermark: (optional) object - Watermark options to overlay on the captured photo
    ///       - text: string - Text to draw as watermark
    ///       - position: (optional) string - "bottom-right" (default), "bottom-left", "top-right", "top-left", "center"
    ///       - color: (optional) string - Hex color e.g. "#FFFFFF" (default)
    ///       - size: (optional) number - Font size in points (default: 48)
    ///       - opacity: (optional) number - 0.0 to 1.0 (default: 0.7)
    /// Returns:
    ///   - (empty map - results are returned via events)
    /// Events:
    ///   - Fires "Native\Mobile\Events\Camera\PhotoTaken" (or custom event) when photo is captured
    ///   - Fires "Native\Mobile\Events\Camera\PhotoCancelled" (or custom event) when user cancels
    ///   - Fires "Native\Mobile\Events\Camera\PermissionDenied" when camera permission is denied
    class GetPhoto: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let id = parameters["id"] as? String
            let event = parameters["event"] as? String
            let watermark = parameters["watermark"] as? [String: Any]

            print("📸 Capturing photo with id=\(id ?? "nil"), event=\(event ?? "nil"), watermark=\(watermark != nil)")

            // Helper to fire permission denied event
            func firePermissionDenied() {
                let eventClass = "Native\\Mobile\\Events\\Camera\\PermissionDenied"
                var payload: [String: Any] = ["action": "photo"]
                if let id = id {
                    payload["id"] = id
                }
                LaravelBridge.shared.send?(eventClass, payload)
            }

            // Check camera permission status
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized:
                // Permission granted, proceed to show camera
                presentPhotoPicker(id: id, event: event, watermark: watermark)

            case .notDetermined:
                // Request permission
                AVCaptureDevice.requestAccess(for: .video) { granted in
                    DispatchQueue.main.async {
                        if granted {
                            self.presentPhotoPicker(id: id, event: event, watermark: watermark)
                        } else {
                            print("❌ Camera permission denied by user")
                            firePermissionDenied()
                        }
                    }
                }

            case .denied, .restricted:
                print("❌ Camera permission denied or restricted")
                DispatchQueue.main.async {
                    firePermissionDenied()
                }

            @unknown default:
                print("❌ Unknown camera permission status")
                DispatchQueue.main.async {
                    firePermissionDenied()
                }
            }

            return [:]
        }

        private func presentPhotoPicker(id: String?, event: String?, watermark: [String: Any]?) {
            DispatchQueue.main.async {
                // Set id, event and watermark on delegate before presenting picker
                CameraPhotoDelegate.shared.pendingPhotoId = id
                CameraPhotoDelegate.shared.pendingPhotoEvent = event
                CameraPhotoDelegate.shared.pendingWatermarkOptions = watermark

                guard let windowScene = UIApplication.shared.connectedScenes
                    .compactMap({ $0 as? UIWindowScene })
                    .first(where: { $0.activationState == .foregroundActive }),
                      let rootVC = windowScene.windows
                        .first(where: { $0.isKeyWindow })?
                        .rootViewController else {
                    print("❌ Failed to get root view controller")
                    return
                }

                guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
                    print("❌ Camera not available")
                    return
                }

                let picker = UIImagePickerController()
                picker.sourceType = .camera
                picker.mediaTypes = [UTType.image.identifier]
                picker.cameraCaptureMode = .photo

                picker.delegate = CameraPhotoDelegate.shared
                rootVC.present(picker, animated: true)
            }
        }
    }

    // MARK: - Camera.PickMedia

    /// Pick media from the device gallery
    /// Parameters:
    ///   - mediaType: (optional) string - Type of media to pick: "image", "video", or "all" (default: "all")
    ///   - multiple: (optional) boolean - Allow multiple selection (default: false)
    ///   - maxItems: (optional) int - Maximum number of items when multiple=true (default: 10)
    ///   - id: (optional) string - Optional ID to track this operation
    ///   - event: (optional) string - Custom event class to fire (defaults to "Native\Mobile\Events\Camera\MediaSelected")
    /// Returns:
    ///   - (empty map - results are returned via events)
    /// Events:
    ///   - Fires "Native\Mobile\Events\Camera\MediaSelected" (or custom event) when media is selected or cancelled
    class PickMedia: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let mediaType = parameters["mediaType"] as? String ?? "all"
            let multiple = parameters["multiple"] as? Bool ?? false
            let maxItems = parameters["maxItems"] as? Int ?? 10
            let id = parameters["id"] as? String
            let event = parameters["event"] as? String

            print("🖼️ Picking media with mediaType=\(mediaType), multiple=\(multiple), maxItems=\(maxItems), id=\(id ?? "nil"), event=\(event ?? "nil")")

            DispatchQueue.main.async {
                CameraGalleryManager.shared.openGallery(
                    mediaType: mediaType,
                    multiple: multiple,
                    maxItems: maxItems,
                    id: id,
                    event: event
                )
            }

            return [:]
        }
    }

    // MARK: - Camera.RecordVideo

    /// Record a video with the device camera
    /// Parameters:
    ///   - maxDuration: (optional) int - Maximum recording duration in seconds
    ///   - id: (optional) string - Optional ID to track this specific video recording
    ///   - event: (optional) string - Custom event class to fire (defaults to "Native\Mobile\Events\Camera\VideoRecorded")
    /// Returns:
    ///   - (empty map - results are returned via events)
    /// Events:
    ///   - Fires "Native\Mobile\Events\Camera\VideoRecorded" (or custom event) when video is captured
    ///   - Fires "Native\Mobile\Events\Camera\VideoCancelled" (or custom event) when user cancels
    ///   - Fires "Native\Mobile\Events\Camera\PermissionDenied" when camera permission is denied
    class RecordVideo: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let maxDuration = parameters["maxDuration"] as? Int
            let id = parameters["id"] as? String
            let event = parameters["event"] as? String

            print("🎥 Recording video with maxDuration=\(maxDuration ?? 0), id=\(id ?? "nil"), event=\(event ?? "nil")")

            // Helper to fire permission denied event
            func firePermissionDenied() {
                let eventClass = "Native\\Mobile\\Events\\Camera\\PermissionDenied"
                var payload: [String: Any] = ["action": "video"]
                if let id = id {
                    payload["id"] = id
                }
                LaravelBridge.shared.send?(eventClass, payload)
            }

            // Check camera permission status
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized:
                // Permission granted, proceed to show camera
                presentVideoPicker(maxDuration: maxDuration, id: id, event: event)

            case .notDetermined:
                // Request permission
                AVCaptureDevice.requestAccess(for: .video) { granted in
                    DispatchQueue.main.async {
                        if granted {
                            self.presentVideoPicker(maxDuration: maxDuration, id: id, event: event)
                        } else {
                            print("❌ Camera permission denied by user")
                            firePermissionDenied()
                        }
                    }
                }

            case .denied, .restricted:
                print("❌ Camera permission denied or restricted")
                DispatchQueue.main.async {
                    firePermissionDenied()
                }

            @unknown default:
                print("❌ Unknown camera permission status")
                DispatchQueue.main.async {
                    firePermissionDenied()
                }
            }

            return [:]
        }

        private func presentVideoPicker(maxDuration: Int?, id: String?, event: String?) {
            DispatchQueue.main.async {
                // Set id and event on delegate before presenting picker
                CameraVideoDelegate.shared.pendingVideoId = id
                CameraVideoDelegate.shared.pendingVideoEvent = event

                // Helper to fire cancel event
                func fireCancel() {
                    let cancelEventClass = "Native\\Mobile\\Events\\Camera\\VideoCancelled"
                    var payload: [String: Any] = ["cancelled": true]
                    if let id = id {
                        payload["id"] = id
                    }
                    LaravelBridge.shared.send?(cancelEventClass, payload)
                }

                guard let windowScene = UIApplication.shared.connectedScenes
                    .compactMap({ $0 as? UIWindowScene })
                    .first(where: { $0.activationState == .foregroundActive }),
                      let rootVC = windowScene.windows
                        .first(where: { $0.isKeyWindow })?
                        .rootViewController else {
                    print("❌ Failed to get root view controller")
                    fireCancel()
                    return
                }

                // Check if camera is available and supports video recording
                guard UIImagePickerController.isSourceTypeAvailable(.camera),
                      UIImagePickerController.availableMediaTypes(for: .camera)?.contains(UTType.movie.identifier) == true else {
                    print("❌ Camera or video recording not available")
                    fireCancel()
                    return
                }

                let picker = UIImagePickerController()
                picker.sourceType = .camera
                picker.mediaTypes = [UTType.movie.identifier]
                picker.videoQuality = .typeHigh
                picker.cameraCaptureMode = .video

                if let duration = maxDuration, duration > 0 {
                    picker.videoMaximumDuration = TimeInterval(duration)
                }

                picker.delegate = CameraVideoDelegate.shared
                rootVC.present(picker, animated: true)
            }
        }
    }
}

// MARK: - Video Delegate

final class CameraVideoDelegate: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {

    static let shared = CameraVideoDelegate()

    var pendingVideoId: String?
    var pendingVideoEvent: String?

    // User captured a video
    func imagePickerController(_ picker: UIImagePickerController,
                               didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {

        picker.dismiss(animated: true)

        // Use default events if not provided
        let eventClass = pendingVideoEvent ?? "Native\\Mobile\\Events\\Camera\\VideoRecorded"
        let cancelEventClass = "Native\\Mobile\\Events\\Camera\\VideoCancelled"

        // Get the video URL
        guard let videoURL = info[.mediaURL] as? URL else {
            print("❌ Failed to get video URL")
            var payload: [String: Any] = ["cancelled": true]
            if let id = pendingVideoId {
                payload["id"] = id
            }
            LaravelBridge.shared.send?(cancelEventClass, payload)

            // Clean up
            pendingVideoId = nil
            pendingVideoEvent = nil
            return
        }

        // Save on a background queue
        DispatchQueue.global(qos: .utility).async { [weak self] in
            let fm = FileManager.default

            // Use temporary directory
            let tempDir = fm.temporaryDirectory

            // Generate unique filename
            let timestamp = Int(Date().timeIntervalSince1970 * 1000)
            let fileExtension = videoURL.pathExtension.isEmpty ? "mp4" : videoURL.pathExtension
            let filename = "captured_video_\(timestamp).\(fileExtension)"
            var fileURL = tempDir.appendingPathComponent(filename)

            do {
                // Remove existing file if present
                if fm.fileExists(atPath: fileURL.path) {
                    try fm.removeItem(at: fileURL)
                }

                // Move (faster) instead of copy since temp file will be deleted anyway
                print("📹 Moving video file...")
                try fm.moveItem(at: videoURL, to: fileURL)
                print("📹 Video file moved successfully")

                // Exclude from iCloud / iTunes backup
                var resourceValues = URLResourceValues()
                resourceValues.isExcludedFromBackup = true
                try fileURL.setResourceValues(resourceValues)

                // Fire success event on main thread
                var payload: [String: Any] = [
                    "path": fileURL.path(percentEncoded: false),
                    "mimeType": "video/\(fileExtension)"
                ]
                if let id = self?.pendingVideoId {
                    payload["id"] = id
                }

                // Dispatch event with slight delay to ensure UI is ready
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                    LaravelBridge.shared.send?(eventClass, payload)
                    print("✅ Video recorded successfully: \(fileURL.path)")
                }

            } catch {
                print("❌ Saving video failed: \(error)")
                var payload: [String: Any] = ["cancelled": true]
                if let id = self?.pendingVideoId {
                    payload["id"] = id
                }

                DispatchQueue.main.async {
                    LaravelBridge.shared.send?(cancelEventClass, payload)
                }
            }

            // Clean up
            self?.pendingVideoId = nil
            self?.pendingVideoEvent = nil
        }
    }

    // User hit "Cancel"
    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true)

        print("⚠️ Video recording cancelled")

        // Always use the default cancel event
        let cancelEventClass = "Native\\Mobile\\Events\\Camera\\VideoCancelled"

        var payload: [String: Any] = ["cancelled": true]
        if let id = pendingVideoId {
            payload["id"] = id
        }
        LaravelBridge.shared.send?(cancelEventClass, payload)

        // Clean up
        pendingVideoId = nil
        pendingVideoEvent = nil
    }
}

// MARK: - Photo Delegate

final class CameraPhotoDelegate: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {

    static let shared = CameraPhotoDelegate()

    var pendingPhotoId: String?
    var pendingPhotoEvent: String?
    var pendingWatermarkOptions: [String: Any]?

    // User captured a photo
    func imagePickerController(_ picker: UIImagePickerController,
                               didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {

        picker.dismiss(animated: true)

        // Use default events if not provided
        let eventClass = pendingPhotoEvent ?? "Native\\Mobile\\Events\\Camera\\PhotoTaken"
        let cancelEventClass = "Native\\Mobile\\Events\\Camera\\PhotoCancelled"

        // Get the image
        guard let image = info[.originalImage] as? UIImage else {
            print("❌ Failed to get photo image")
            var payload: [String: Any] = ["cancelled": true]
            if let id = pendingPhotoId {
                payload["id"] = id
            }
            LaravelBridge.shared.send?(cancelEventClass, payload)

            // Clean up
            pendingPhotoId = nil
            pendingPhotoEvent = nil
            return
        }

        let capturedWatermark = pendingWatermarkOptions

        // Save on a background queue
        DispatchQueue.global(qos: .utility).async { [weak self] in
            let fm = FileManager.default

            // Use temporary directory
            let tempDir = fm.temporaryDirectory

            // Generate unique filename
            let timestamp = Int(Date().timeIntervalSince1970 * 1000)
            let filename = "captured_photo_\(timestamp).jpg"
            var fileURL = tempDir.appendingPathComponent(filename)

            do {
                // Remove existing file if present
                if fm.fileExists(atPath: fileURL.path) {
                    try fm.removeItem(at: fileURL)
                }

                // Apply watermark if requested
                let finalImage = capturedWatermark != nil
                    ? CameraPhotoDelegate.applyWatermark(to: image, options: capturedWatermark!)
                    : image

                // Convert to JPEG and save
                guard let jpegData = finalImage.jpegData(compressionQuality: 0.9) else {
                    print("❌ Failed to convert image to JPEG")
                    return
                }

                print("📸 Saving photo file...")
                try jpegData.write(to: fileURL)
                print("📸 Photo file saved successfully")

                // Exclude from iCloud / iTunes backup
                var resourceValues = URLResourceValues()
                resourceValues.isExcludedFromBackup = true
                try fileURL.setResourceValues(resourceValues)

                // Fire success event on main thread
                var payload: [String: Any] = [
                    "path": fileURL.path(percentEncoded: false),
                    "mimeType": "image/jpeg"
                ]
                if let id = self?.pendingPhotoId {
                    payload["id"] = id
                }

                // Dispatch event with slight delay to ensure UI is ready
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                    LaravelBridge.shared.send?(eventClass, payload)
                    print("✅ Photo captured successfully: \(fileURL.path)")
                }

            } catch {
                print("❌ Saving photo failed: \(error)")
                var payload: [String: Any] = ["cancelled": true]
                if let id = self?.pendingPhotoId {
                    payload["id"] = id
                }

                DispatchQueue.main.async {
                    LaravelBridge.shared.send?(cancelEventClass, payload)
                }
            }

            // Clean up
            self?.pendingPhotoId = nil
            self?.pendingPhotoEvent = nil
            self?.pendingWatermarkOptions = nil
        }
    }

    // User hit "Cancel"
    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true)

        print("⚠️ Photo capture cancelled")

        // Always use the default cancel event
        let cancelEventClass = "Native\\Mobile\\Events\\Camera\\PhotoCancelled"

        var payload: [String: Any] = ["cancelled": true]
        if let id = pendingPhotoId {
            payload["id"] = id
        }
        LaravelBridge.shared.send?(cancelEventClass, payload)

        // Clean up
        pendingPhotoId = nil
        pendingPhotoEvent = nil
        pendingWatermarkOptions = nil
    }

    // MARK: - Watermark

    static func applyWatermark(to image: UIImage, options: [String: Any]) -> UIImage {
        guard let text = options["text"] as? String else { return image }

        let position = (options["position"] as? String ?? "bottom-right").lowercased()
        let colorHex = options["color"] as? String ?? "#FFFFFF"
        let fontSize = options["size"] as? CGFloat ?? 48.0
        let opacity = options["opacity"] as? Double ?? 0.7

        let renderer = UIGraphicsImageRenderer(size: image.size)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: image.size))

            let shadow = NSShadow()
            shadow.shadowColor = UIColor.black.withAlphaComponent(0.5)
            shadow.shadowOffset = CGSize(width: 1, height: 1)
            shadow.shadowBlurRadius = 3

            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.boldSystemFont(ofSize: fontSize),
                .foregroundColor: colorFromHex(colorHex).withAlphaComponent(CGFloat(opacity)),
                .shadow: shadow
            ]

            let textSize = text.size(withAttributes: attributes)
            let padding: CGFloat = 32

            let point: CGPoint
            switch position {
            case "top-left":
                point = CGPoint(x: padding, y: padding)
            case "top-right":
                point = CGPoint(x: image.size.width - textSize.width - padding, y: padding)
            case "bottom-left":
                point = CGPoint(x: padding, y: image.size.height - textSize.height - padding)
            case "center":
                point = CGPoint(x: (image.size.width - textSize.width) / 2,
                                y: (image.size.height - textSize.height) / 2)
            default: // bottom-right
                point = CGPoint(x: image.size.width - textSize.width - padding,
                                y: image.size.height - textSize.height - padding)
            }

            text.draw(at: point, withAttributes: attributes)
        }
    }

    private static func colorFromHex(_ hex: String) -> UIColor {
        let cleaned = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        guard cleaned.count == 6, let rgb = UInt64(cleaned, radix: 16) else {
            return .white
        }
        return UIColor(
            red:   CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8)  & 0xFF) / 255,
            blue:  CGFloat(rgb         & 0xFF) / 255,
            alpha: 1
        )
    }
}

// MARK: - Gallery Manager

final class CameraGalleryManager: NSObject {
    static let shared = CameraGalleryManager()

    var pendingGalleryId: String?
    var pendingGalleryEvent: String?

    func openGallery(mediaType: String, multiple: Bool, maxItems: Int, id: String? = nil, event: String? = nil) {
        // Store id and event for callback
        pendingGalleryId = id
        pendingGalleryEvent = event
        guard let windowScene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive }),
              let rootVC = windowScene.windows
            .first(where: { $0.isKeyWindow })?
            .rootViewController else {
            return
        }

        var configuration = PHPickerConfiguration()

        // Set media type filter
        switch mediaType.lowercased() {
        case "image", "images":
            configuration.filter = .images
        case "video", "videos":
            configuration.filter = .videos
        case "all", "*":
            configuration.filter = .any(of: [.images, .videos])
        default:
            configuration.filter = .any(of: [.images, .videos])
        }

        // Set selection limit
        if multiple {
            configuration.selectionLimit = maxItems > 0 ? maxItems : 0 // 0 means no limit
        } else {
            configuration.selectionLimit = 1
        }

        configuration.preferredAssetRepresentationMode = .current

        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = self

        rootVC.present(picker, animated: true)
    }
}

extension CameraGalleryManager: PHPickerViewControllerDelegate {
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)

        // Use default event if not provided
        let eventClass = pendingGalleryEvent ?? "Native\\Mobile\\Events\\Gallery\\MediaSelected"

        guard !results.isEmpty else {
            // User cancelled
            var payload: [String: Any] = [
                "success": false,
                "files": [],
                "count": 0,
                "cancelled": true
            ]
            if let id = pendingGalleryId {
                payload["id"] = id
            }

            LaravelBridge.shared.send?(eventClass, payload)

            // Clean up
            pendingGalleryId = nil
            pendingGalleryEvent = nil
            return
        }

        processPickerResults(results)
    }

    private func processPickerResults(_ results: [PHPickerResult]) {
        let group = DispatchGroup()
        var processedFiles: [[String: Any]] = []

        // Capture event class and id before async processing
        let eventClass = pendingGalleryEvent ?? "Native\\Mobile\\Events\\Gallery\\MediaSelected"
        let capturedId = pendingGalleryId

        for (index, result) in results.enumerated() {
            group.enter()

            // Try to get the file representation
            if result.itemProvider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                result.itemProvider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { url, error in
                    defer { group.leave() }

                    if let url = url {
                        self.copyFileToCache(url: url, index: index, type: "image") { fileInfo in
                            if let fileInfo = fileInfo {
                                processedFiles.append(fileInfo)
                            }
                        }
                    }
                }
            } else if result.itemProvider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
                result.itemProvider.loadFileRepresentation(forTypeIdentifier: UTType.movie.identifier) { url, error in
                    defer { group.leave() }

                    if let url = url {
                        self.copyFileToCache(url: url, index: index, type: "video") { fileInfo in
                            if let fileInfo = fileInfo {
                                processedFiles.append(fileInfo)
                            }
                        }
                    }
                }
            } else {
                group.leave()
            }
        }

        group.notify(queue: .main) { [weak self] in
            var payload: [String: Any] = [
                "success": true,
                "files": processedFiles,
                "count": processedFiles.count
            ]
            if let id = capturedId {
                payload["id"] = id
            }

            LaravelBridge.shared.send?(eventClass, payload)

            // Clean up
            self?.pendingGalleryId = nil
            self?.pendingGalleryEvent = nil
        }
    }

    private func copyFileToCache(url: URL, index: Int, type: String, completion: @escaping ([String: Any]?) -> Void) {
        let fileManager = FileManager.default

        // Use temporary directory with Gallery subfolder
        let tempDir = fileManager.temporaryDirectory
        let galleryDir = tempDir.appendingPathComponent("Gallery", isDirectory: true)

        // Ensure Gallery directory exists
        try? fileManager.createDirectory(at: galleryDir, withIntermediateDirectories: true)

        let timestamp = Int(Date().timeIntervalSince1970 * 1000)
        let fileExtension = url.pathExtension.isEmpty ? (type == "image" ? "jpg" : "mp4") : url.pathExtension
        let fileName = "gallery_selected_\(timestamp)_\(index).\(fileExtension)"
        let destinationURL = galleryDir.appendingPathComponent(fileName)

        do {
            if fileManager.fileExists(atPath: destinationURL.path) {
                try fileManager.removeItem(at: destinationURL)
            }

            try fileManager.copyItem(at: url, to: destinationURL)

            let fileInfo: [String: Any] = [
                "path": destinationURL.path,
                "mimeType": getMimeType(for: fileExtension),
                "extension": fileExtension,
                "type": type
            ]

            completion(fileInfo)
        } catch {
            print("Error copying file: \(error)")
            completion(nil)
        }
    }

    private func getMimeType(for fileExtension: String) -> String {
        switch fileExtension.lowercased() {
        case "jpg", "jpeg":
            return "image/jpeg"
        case "png":
            return "image/png"
        case "gif":
            return "image/gif"
        case "webp":
            return "image/webp"
        case "mp4":
            return "video/mp4"
        case "mov":
            return "video/quicktime"
        case "avi":
            return "video/avi"
        case "webm":
            return "video/webm"
        default:
            return "application/octet-stream"
        }
    }
}
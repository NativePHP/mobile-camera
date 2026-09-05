import Foundation
import UIKit
import AVFoundation
import UniformTypeIdentifiers
import PhotosUI
import Photos
import CoreLocation
import ImageIO

// MARK: - Camera Function Namespace

/// Functions related to camera operations
/// Namespace: "Camera.*"
enum CameraFunctions {

    // MARK: - Camera.GetPhoto

    /// Capture a photo with the device camera
    /// Parameters:
    ///   - id: (optional) string - Optional ID to track this specific photo capture
    ///   - event: (optional) string - Custom event class to fire (defaults to "Native\Mobile\Events\Camera\PhotoTaken")
    ///   - includeLocation: (optional) boolean - Geotag the capture using Core Location. Prompts for
    ///     When-In-Use location authorization the first time (default: false, no new prompt)
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
            let includeLocation = parameters["includeLocation"] as? Bool ?? false

            print("📸 Capturing photo with id=\(id ?? "nil"), event=\(event ?? "nil"), includeLocation=\(includeLocation)")

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
                presentPhotoPicker(id: id, event: event, includeLocation: includeLocation)

            case .notDetermined:
                // Request permission
                AVCaptureDevice.requestAccess(for: .video) { granted in
                    DispatchQueue.main.async {
                        if granted {
                            self.presentPhotoPicker(id: id, event: event, includeLocation: includeLocation)
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

        private func presentPhotoPicker(id: String?, event: String?, includeLocation: Bool) {
            DispatchQueue.main.async {
                // Set id and event on delegate before presenting picker
                CameraPhotoDelegate.shared.pendingPhotoId = id
                CameraPhotoDelegate.shared.pendingPhotoEvent = event
                CameraPhotoDelegate.shared.pendingIncludeLocation = includeLocation

                // Opt-in only: begin gathering a location fix so the capture can be geotagged.
                // This is what triggers the location authorization prompt, so it is never
                // started unless the caller asked for it. Best-effort: never blocks capture.
                if includeLocation {
                    CameraLocationProvider.shared.start()
                }

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
    ///   - includeLocation: (optional) boolean - Recover GPS coordinates of picked images via the
    ///     Photo Library. Prompts for Photo Library access (default: false, no new prompt)
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
            let includeLocation = parameters["includeLocation"] as? Bool ?? false

            print("🖼️ Picking media with mediaType=\(mediaType), multiple=\(multiple), maxItems=\(maxItems), id=\(id ?? "nil"), event=\(event ?? "nil"), includeLocation=\(includeLocation)")

            DispatchQueue.main.async {
                CameraGalleryManager.shared.openGallery(
                    mediaType: mediaType,
                    multiple: multiple,
                    maxItems: maxItems,
                    id: id,
                    event: event,
                    includeLocation: includeLocation
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

// MARK: - Metadata Helpers

/// Shared helpers for formatting capture metadata into the cross-platform payload contract.
/// Keys produced: `takenAt` (ISO-8601 UTC string), `latitude` (Double), `longitude` (Double).
/// All keys are OMITTED when their value is unavailable (never NSNull).
enum CameraMetadata {

    /// ISO-8601 UTC formatter producing `yyyy-MM-dd'T'HH:mm:ss'Z'`.
    static let isoFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'"
        return formatter
    }()

    /// Parser for Exif `DateTimeOriginal` strings, which use the form `yyyy:MM:dd HH:mm:ss`
    /// in the device's local time zone.
    static let exifDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone.current
        formatter.dateFormat = "yyyy:MM:dd HH:mm:ss"
        return formatter
    }()

    /// Format a `Date` as an ISO-8601 UTC string for the `takenAt` payload key.
    static func isoString(from date: Date) -> String {
        return isoFormatter.string(from: date)
    }

    /// Parse an Exif `DateTimeOriginal` value out of an image metadata dictionary.
    /// Returns nil if the dictionary has no usable Exif date.
    static func dateTimeOriginal(from metadata: [String: Any]) -> Date? {
        guard let exif = metadata[kCGImagePropertyExifDictionary as String] as? [String: Any] else {
            return nil
        }
        guard let raw = exif[kCGImagePropertyExifDateTimeOriginal as String] as? String else {
            return nil
        }
        return exifDateFormatter.date(from: raw)
    }

    /// Extract a coordinate from a GPS sub-dictionary in an image metadata dictionary.
    /// Honours the latitude/longitude reference fields (N/S, E/W).
    static func coordinate(from metadata: [String: Any]) -> CLLocationCoordinate2D? {
        guard let gps = metadata[kCGImagePropertyGPSDictionary as String] as? [String: Any],
              var latitude = gps[kCGImagePropertyGPSLatitude as String] as? Double,
              var longitude = gps[kCGImagePropertyGPSLongitude as String] as? Double else {
            return nil
        }

        if let latRef = gps[kCGImagePropertyGPSLatitudeRef as String] as? String,
           latRef.uppercased() == "S" {
            latitude = -latitude
        }
        if let lonRef = gps[kCGImagePropertyGPSLongitudeRef as String] as? String,
           lonRef.uppercased() == "W" {
            longitude = -longitude
        }

        return CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    /// Read `takenAt`/`latitude`/`longitude` straight from an image file's embedded metadata
    /// using ImageIO. Needs no permissions, so it is the default source for picked images when
    /// the caller has not opted into location recovery. Only present keys are returned.
    static func payloadMetadata(fromFileAt url: URL) -> [String: Any]? {
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithURL(url as CFURL, options),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, options) as? [String: Any] else {
            return nil
        }

        var metadata: [String: Any] = [:]

        if let date = dateTimeOriginal(from: properties) {
            metadata["takenAt"] = isoString(from: date)
        }

        if let coordinate = coordinate(from: properties) {
            metadata["latitude"] = coordinate.latitude
            metadata["longitude"] = coordinate.longitude
        }

        return metadata.isEmpty ? nil : metadata
    }

    /// Build a CoreGraphics GPS dictionary from a CLLocation, suitable for embedding via ImageIO.
    static func gpsDictionary(from location: CLLocation) -> [String: Any] {
        let coordinate = location.coordinate
        var gps: [String: Any] = [:]

        gps[kCGImagePropertyGPSLatitude as String] = abs(coordinate.latitude)
        gps[kCGImagePropertyGPSLatitudeRef as String] = coordinate.latitude >= 0 ? "N" : "S"
        gps[kCGImagePropertyGPSLongitude as String] = abs(coordinate.longitude)
        gps[kCGImagePropertyGPSLongitudeRef as String] = coordinate.longitude >= 0 ? "E" : "W"

        if location.verticalAccuracy >= 0 {
            gps[kCGImagePropertyGPSAltitude as String] = abs(location.altitude)
            gps[kCGImagePropertyGPSAltitudeRef as String] = location.altitude >= 0 ? 0 : 1
        }

        return gps
    }
}

// MARK: - Location Provider

/// Lightweight wrapper around CLLocationManager used to tag camera captures with a coordinate.
///
/// `UIImagePickerController` only embeds GPS into the media metadata when the app holds
/// location authorization, so we keep a recent fix on hand and synthesise GPS when needed.
/// All access is best-effort: it never blocks capture and never crashes when location is
/// unavailable or denied.
final class CameraLocationProvider: NSObject, CLLocationManagerDelegate {

    static let shared = CameraLocationProvider()

    private let manager = CLLocationManager()

    private override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
    }

    /// Request When-In-Use authorization (no-op if already determined) and begin
    /// receiving updates so a recent fix is available by capture time.
    func start() {
        let status = manager.authorizationStatus
        if status == .notDetermined {
            manager.requestWhenInUseAuthorization()
        }
        if status == .authorizedWhenInUse || status == .authorizedAlways || status == .notDetermined {
            manager.startUpdatingLocation()
        }
    }

    /// Stop updates to conserve power once a capture flow has finished.
    func stop() {
        manager.stopUpdatingLocation()
    }

    /// The most recent location fix, if one is available and authorization is granted.
    var currentLocation: CLLocation? {
        let status = manager.authorizationStatus
        guard status == .authorizedWhenInUse || status == .authorizedAlways else {
            return nil
        }
        return manager.location
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        if status == .authorizedWhenInUse || status == .authorizedAlways {
            manager.startUpdatingLocation()
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Best-effort only; ignore failures so capture is never blocked.
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

            // Use Application Support directory instead of the temporary directory.
            // NSTemporaryDirectory() can be purged by iOS at any time (low disk space,
            // app relaunch, etc), which risks the same "file went missing before PHP
            // could read it" failure mode as Android's cache dir (see Issue #8).
            // Application Support is private, persistent, and not auto-purged.
            let supportDir = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            let cameraDir = supportDir.appendingPathComponent("Camera", isDirectory: true)
            try? fm.createDirectory(at: cameraDir, withIntermediateDirectories: true)

            // Generate unique filename
            let timestamp = Int(Date().timeIntervalSince1970 * 1000)
            let fileExtension = videoURL.pathExtension.isEmpty ? "mp4" : videoURL.pathExtension
            let filename = "captured_video_\(timestamp).\(fileExtension)"
            var fileURL = cameraDir.appendingPathComponent(filename)

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
    /// Whether the caller opted into geotagging (bridge parameter `includeLocation`).
    var pendingIncludeLocation: Bool = false

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
            CameraLocationProvider.shared.stop()
            return
        }

        // Capture the original media metadata (Exif / TIFF / GPS sub-dictionaries) while it is
        // still available. A UIImage carries no EXIF, so we must read it from the picker info
        // and write it back into the output JPEG ourselves.
        let mediaMetadata = (info[.mediaMetadata] as? [String: Any]) ?? [:]

        // Grab the best location fix we have at capture time (may be nil). Only consulted when
        // the caller opted in; otherwise the provider was never started and no prompt was shown.
        let captureLocation = pendingIncludeLocation ? CameraLocationProvider.shared.currentLocation : nil
        CameraLocationProvider.shared.stop()
        pendingIncludeLocation = false

        // Save on a background queue
        DispatchQueue.global(qos: .utility).async { [weak self] in
            let fm = FileManager.default

            // Use Application Support directory instead of the temporary directory.
            // NSTemporaryDirectory() can be purged by iOS at any time (low disk space,
            // app relaunch, etc), which risks the same "file went missing before PHP
            // could read it" failure mode as Android's cache dir (see Issue #8).
            // Application Support is private, persistent, and not auto-purged.
            let supportDir = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            let cameraDir = supportDir.appendingPathComponent("Camera", isDirectory: true)
            try? fm.createDirectory(at: cameraDir, withIntermediateDirectories: true)

            // Generate unique filename
            let timestamp = Int(Date().timeIntervalSince1970 * 1000)
            let filename = "captured_photo_\(timestamp).jpg"

            var fileURL = cameraDir.appendingPathComponent(filename)

            do {
                // Remove existing file if present
                if fm.fileExists(atPath: fileURL.path) {
                    try fm.removeItem(at: fileURL)
                }

                // Build the metadata dictionary to embed, starting from the original capture
                // metadata so Exif (incl. DateTimeOriginal) and any existing GPS are preserved.
                var imageProperties = mediaMetadata

                // If the capture metadata lacks GPS (e.g. no location authorization at capture
                // time), synthesise one from our CLLocation fix so the file stays geotagged.
                let hasGPS = imageProperties[kCGImagePropertyGPSDictionary as String] != nil
                if !hasGPS, let location = captureLocation {
                    imageProperties[kCGImagePropertyGPSDictionary as String] =
                        CameraMetadata.gpsDictionary(from: location)
                }

                // Preserve JPEG compression behaviour (0.9) while writing metadata.
                imageProperties[kCGImageDestinationLossyCompressionQuality as String] = 0.9

                // Write the image + metadata using ImageIO so EXIF/GPS survive.
                guard let cgImage = image.cgImage,
                      let destination = CGImageDestinationCreateWithURL(
                        fileURL as CFURL,
                        UTType.jpeg.identifier as CFString,
                        1,
                        nil
                      ) else {
                    print("❌ Failed to create image destination, falling back to plain JPEG")
                    guard let jpegData = image.jpegData(compressionQuality: 0.9) else {
                        print("❌ Failed to convert image to JPEG")
                        throw CocoaError(.fileWriteUnknown)
                    }
                    try jpegData.write(to: fileURL)
                    self?.finishPhoto(
                        fileURL: fileURL,
                        eventClass: eventClass,
                        mediaMetadata: mediaMetadata,
                        captureLocation: captureLocation
                    )
                    return
                }

                CGImageDestinationAddImage(destination, cgImage, imageProperties as CFDictionary)

                guard CGImageDestinationFinalize(destination) else {
                    print("❌ Failed to finalize image destination")
                    throw CocoaError(.fileWriteUnknown)
                }

                print("📸 Photo file saved successfully with metadata")

                // Exclude from iCloud / iTunes backup
                var resourceValues = URLResourceValues()
                resourceValues.isExcludedFromBackup = true
                var mutableURL = fileURL
                try mutableURL.setResourceValues(resourceValues)

                self?.finishPhoto(
                    fileURL: fileURL,
                    eventClass: eventClass,
                    mediaMetadata: mediaMetadata,
                    captureLocation: captureLocation
                )

            } catch {
                print("❌ Saving photo failed: \(error)")
                var payload: [String: Any] = ["cancelled": true]
                if let id = self?.pendingPhotoId {
                    payload["id"] = id
                }

                DispatchQueue.main.async {
                    LaravelBridge.shared.send?(cancelEventClass, payload)
                }

                // Clean up
                self?.pendingPhotoId = nil
                self?.pendingPhotoEvent = nil
            }
        }
    }

    /// Build and dispatch the PhotoTaken payload, attaching `takenAt`/`latitude`/`longitude`
    /// derived from the capture metadata and/or location fix. Keys are omitted when unavailable.
    private func finishPhoto(
        fileURL: URL,
        eventClass: String,
        mediaMetadata: [String: Any],
        captureLocation: CLLocation?
    ) {
        var payload: [String: Any] = [
            "path": fileURL.path(percentEncoded: false),
            "mimeType": "image/jpeg"
        ]
        if let id = pendingPhotoId {
            payload["id"] = id
        }

        // takenAt: prefer Exif DateTimeOriginal, otherwise fall back to "now"
        // (a freshly captured photo's capture time is the present moment).
        let takenAtDate = CameraMetadata.dateTimeOriginal(from: mediaMetadata) ?? Date()
        payload["takenAt"] = CameraMetadata.isoString(from: takenAtDate)

        // latitude/longitude: prefer GPS embedded in the capture metadata, then our CLLocation.
        if let coordinate = CameraMetadata.coordinate(from: mediaMetadata) {
            payload["latitude"] = coordinate.latitude
            payload["longitude"] = coordinate.longitude
        } else if let location = captureLocation {
            payload["latitude"] = location.coordinate.latitude
            payload["longitude"] = location.coordinate.longitude
        }

        // Dispatch event with slight delay to ensure UI is ready
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
            LaravelBridge.shared.send?(eventClass, payload)
            print("✅ Photo captured successfully: \(fileURL.path)")
        }

        // Clean up
        pendingPhotoId = nil
        pendingPhotoEvent = nil
    }

    // User hit "Cancel"
    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true)

        print("⚠️ Photo capture cancelled")

        // Stop gathering location since the capture flow is finished.
        CameraLocationProvider.shared.stop()

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
    }
}

// MARK: - Gallery Manager

final class CameraGalleryManager: NSObject {
    static let shared = CameraGalleryManager()

    var pendingGalleryId: String?
    var pendingGalleryEvent: String?

    func openGallery(mediaType: String, multiple: Bool, maxItems: Int, id: String? = nil, event: String? = nil, includeLocation: Bool = false) {
        // Store id and event for callback
        pendingGalleryId = id
        pendingGalleryEvent = event

        guard includeLocation else {
            // Default: the permission-free picker. It strips GPS for privacy, but the capture
            // date is still read from the copied file's EXIF, so no prompt is ever shown.
            presentPicker(mediaType: mediaType, multiple: multiple, maxItems: maxItems, useLibrary: false)
            return
        }

        // Opt-in: request Photo Library access so picker results carry `assetIdentifier`,
        // which we use to recover each asset's creation date and GPS location.
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { [weak self] _ in
            DispatchQueue.main.async {
                self?.presentPicker(mediaType: mediaType, multiple: multiple, maxItems: maxItems, useLibrary: true)
            }
        }
    }

    private func presentPicker(mediaType: String, multiple: Bool, maxItems: Int, useLibrary: Bool) {
        guard let windowScene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive }),
              let rootVC = windowScene.windows
            .first(where: { $0.isKeyWindow })?
            .rootViewController else {
            return
        }

        // Only when opted in: back the picker with the shared photo library so results expose
        // `assetIdentifier`. The plain configuration needs no Photo Library permission.
        var configuration = useLibrary
            ? PHPickerConfiguration(photoLibrary: .shared())
            : PHPickerConfiguration()

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

        // Serialize appends to processedFiles since loadFileRepresentation completions
        // run on arbitrary queues.
        let appendQueue = DispatchQueue(label: "CameraGalleryManager.append")

        for (index, result) in results.enumerated() {
            group.enter()

            // Resolve creation date / location from the backing PHAsset (requires photo
            // library access + an assetIdentifier, i.e. includeLocation=true). Best-effort:
            // nil when unavailable, in which case the copied file's own EXIF is used instead.
            let assetMetadata = self.assetMetadata(for: result.assetIdentifier)

            // Try to get the file representation
            if result.itemProvider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                result.itemProvider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { url, error in
                    defer { group.leave() }

                    if let url = url {
                        self.copyFileToCache(url: url, index: index, type: "image", assetMetadata: assetMetadata) { fileInfo in
                            if let fileInfo = fileInfo {
                                appendQueue.sync { processedFiles.append(fileInfo) }
                            }
                        }
                    }
                }
            } else if result.itemProvider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
                result.itemProvider.loadFileRepresentation(forTypeIdentifier: UTType.movie.identifier) { url, error in
                    defer { group.leave() }

                    if let url = url {
                        // Videos retain their existing handling: no metadata keys attached.
                        self.copyFileToCache(url: url, index: index, type: "video", assetMetadata: nil) { fileInfo in
                            if let fileInfo = fileInfo {
                                appendQueue.sync { processedFiles.append(fileInfo) }
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

    /// Resolve `takenAt`/`latitude`/`longitude` for a picked result using its backing PHAsset.
    /// Returns nil when there is no identifier or the asset can't be fetched (e.g. no access).
    /// The returned dictionary only contains keys whose values are available.
    private func assetMetadata(for identifier: String?) -> [String: Any]? {
        guard let identifier = identifier else {
            return nil
        }

        let fetchResult = PHAsset.fetchAssets(withLocalIdentifiers: [identifier], options: nil)
        guard let asset = fetchResult.firstObject else {
            return nil
        }

        var metadata: [String: Any] = [:]

        if let creationDate = asset.creationDate {
            metadata["takenAt"] = CameraMetadata.isoString(from: creationDate)
        }

        if let location = asset.location {
            metadata["latitude"] = location.coordinate.latitude
            metadata["longitude"] = location.coordinate.longitude
        }

        return metadata.isEmpty ? nil : metadata
    }

    private func copyFileToCache(url: URL, index: Int, type: String, assetMetadata: [String: Any]?, completion: @escaping ([String: Any]?) -> Void) {
        let fileManager = FileManager.default

        // Use Application Support directory with a Gallery subfolder instead of the
        // temporary directory. NSTemporaryDirectory() can be purged by iOS at any
        // time, which risks the same "file went missing before PHP could read it"
        // failure mode as Android's cache dir (see Issue #8). Application Support
        // is private, persistent, and not auto-purged.
        let supportDir = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let galleryDir = supportDir.appendingPathComponent("Gallery", isDirectory: true)

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

            // Permission-free metadata source (used when no PHAsset metadata is available).
            let fileMetadata: [String: Any]? = (type == "image" && assetMetadata == nil)
                ? CameraMetadata.payloadMetadata(fromFileAt: destinationURL)
                : nil

            var finalURL = destinationURL
            var finalExtension = fileExtension

            if type == "image" {
                let lowerExt = fileExtension.lowercased()

                // HEIC/HEIF cannot be shown in WKWebView; normalize to JPEG like camera capture.
                if lowerExt == "heic" || lowerExt == "heif" {
                    if let image = UIImage(contentsOfFile: destinationURL.path),
                       let jpegData = image.jpegData(compressionQuality: 0.9) {
                        let jpegName = "gallery_selected_\(timestamp)_\(index).jpg"
                        let jpegURL = galleryDir.appendingPathComponent(jpegName)

                        if fileManager.fileExists(atPath: jpegURL.path) {
                            try? fileManager.removeItem(at: jpegURL)
                        }

                        try jpegData.write(to: jpegURL)
                        try? fileManager.removeItem(at: destinationURL)
                        finalURL = jpegURL
                        finalExtension = "jpg"
                    }
                }
            }

            var fileInfo: [String: Any] = [
                "path": finalURL.path,
                "mimeType": getMimeType(for: finalExtension),
                "extension": finalExtension,
                "type": type
            ]

            // Attach capture metadata for images (videos keep their existing shape).
            // Prefer the PHAsset (opt-in path); otherwise read the copied file's own EXIF,
            // which needs no permission. Only present keys are merged in, so unavailable
            // values are omitted. Read the EXIF from the original copy, before any HEIC->JPEG
            // re-encode, since UIImage.jpegData drops the metadata.
            if type == "image" {
                if let assetMetadata = assetMetadata {
                    fileInfo.merge(assetMetadata) { _, new in new }
                } else if let fileMetadata = fileMetadata {
                    fileInfo.merge(fileMetadata) { _, new in new }
                }
            }

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
        case "heic":
            return "image/heic"
        case "heif":
            return "image/heif"
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
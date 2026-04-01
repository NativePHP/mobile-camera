# Camera Plugin for NativePHP Mobile

Camera plugin for NativePHP Mobile providing photo capture, video recording, and gallery picker functionality.

## Overview

The Camera API provides access to the device's camera for taking photos, recording videos, and selecting media from the gallery.

## Installation

```shell
composer require nativephp/mobile-camera
```

Don't forget to register the plugin:

```shell
php artisan native:plugin:register nativephp/mobile-camera
```

## Usage

### PHP (Livewire/Blade)

```php
use Native\Mobile\Facades\Camera;

// Take a photo
Camera::getPhoto();

// Record a video
Camera::recordVideo();

// Record with max duration
Camera::recordVideo(['maxDuration' => 30]);

// Using fluent API
Camera::recordVideo()
    ->maxDuration(60)
    ->id('my-video-123')
    ->start();

// Pick images from gallery
Camera::pickImages('images', false);  // Single image
Camera::pickImages('images', true);   // Multiple images
Camera::pickImages('all', true);      // Any media type
```

### JavaScript (Vue/React/Inertia)

```js
import { Camera, On, Off, Events } from '#nativephp';

// Take a photo
await Camera.getPhoto();

// With identifier for tracking
await Camera.getPhoto()
    .id('profile-pic');

// Record video
await Camera.recordVideo()
    .maxDuration(60);

// Pick images
await Camera.pickImages()
    .images()
    .multiple()
    .maxItems(5);
```

## Events

### `PhotoTaken`

Fired when a photo is taken with the camera.

#### PHP

```php
use Native\Mobile\Attributes\OnNative;
use Native\Mobile\Events\Camera\PhotoTaken;

#[OnNative(PhotoTaken::class)]
public function handlePhotoTaken(string $path)
{
    // Process the captured photo
    $this->processPhoto($path);
}
```

#### Vue

```js
import { On, Off, Events } from '#nativephp';
import { ref, onMounted, onUnmounted } from 'vue';

const photoPath = ref('');

const handlePhotoTaken = (payload) => {
    photoPath.value = payload.path;
    processPhoto(payload.path);
};

onMounted(() => {
    On(Events.Camera.PhotoTaken, handlePhotoTaken);
});

onUnmounted(() => {
    Off(Events.Camera.PhotoTaken, handlePhotoTaken);
});
```

### `VideoRecorded`

Fired when a video is successfully recorded.

**Payload:**
- `string $path` - File path to the recorded video
- `string $mimeType` - Video MIME type (default: `'video/mp4'`)
- `?string $id` - Optional identifier if set via `id()` method

### `VideoCancelled`

Fired when video recording is cancelled by the user.

### `MediaSelected`

Fired when media is selected from the gallery.

```php
use Native\Mobile\Attributes\OnNative;
use Native\Mobile\Events\Gallery\MediaSelected;

#[OnNative(MediaSelected::class)]
public function handleMediaSelected($success, $files, $count)
{
    foreach ($files as $file) {
        $this->processMedia($file);
    }
}
```

## PendingVideoRecorder API

### `maxDuration(int $seconds)`

Set the maximum recording duration in seconds.

### `id(string $id)`

Set a unique identifier for this recording to correlate with events.

### `event(string $eventClass)`

Set a custom event class to dispatch when recording completes.

### `remember()`

Store the recorder's ID in the session for later retrieval.

### `start()`

Explicitly start the video recording.

## Storage Locations

**Photos:**
- **Android:** App cache directory at `{cache}/captured.jpg`
- **iOS:** Application Support at `~/Library/Application Support/Photos/captured.jpg`

**Videos:**
- **Android:** App cache directory at `{cache}/video_{timestamp}.mp4`
- **iOS:** Application Support at `~/Library/Application Support/Videos/captured_video_{timestamp}.mp4`

## Notes

- **Permissions:** You must enable the `camera` permission in `config/nativephp.php` to use camera features
- If permission is denied, camera functions will fail silently
- Camera permission is required for photos, videos, AND QR/barcode scanning
- File formats: JPEG for photos, MP4 for videos

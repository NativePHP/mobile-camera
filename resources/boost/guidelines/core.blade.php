## nativephp/camera

Camera plugin for NativePHP Mobile providing photo capture, video recording, and gallery picker functionality.

### PHP Usage (Livewire/Blade)

@verbatim
<code-snippet name="Taking Photos" lang="php">
use Native\Mobile\Facades\Camera;

// Take a photo
Camera::getPhoto();
</code-snippet>
@endverbatim

@verbatim
<code-snippet name="Recording Videos" lang="php">
use Native\Mobile\Facades\Camera;

// Using fluent API
Camera::recordVideo()
    ->maxDuration(60)
    ->id('my-video-123')
    ->start();
</code-snippet>
@endverbatim

@verbatim
<code-snippet name="Picking Media from Gallery" lang="php">
use Native\Mobile\Facades\Camera;

// Pick multiple images
Camera::pickImages('images', true);

// Pick any media type
Camera::pickImages('all', true);
</code-snippet>
@endverbatim

### JavaScript Usage (Vue/React/Inertia)

@verbatim
<code-snippet name="Camera in JavaScript" lang="javascript">
import { camera } from '#nativephp';

// Take a photo with identifier
await camera.getPhoto().id('profile-pic');

// Record video with max duration
await camera.recordVideo()
    .maxDuration(30)
    .id('my-video-123');

// Pick multiple images from gallery
await camera.pickImages()
    .images()
    .multiple()
    .maxItems(5);
</code-snippet>
@endverbatim

### Handling Camera Events

#### PHP

@verbatim
<code-snippet name="Photo Events" lang="php">
use Native\Mobile\Attributes\OnNative;
use Native\Mobile\Events\Camera\PhotoTaken;

#[OnNative(PhotoTaken::class)]
public function handlePhotoTaken(string $path)
{
    $this->processPhoto($path);
}
</code-snippet>
@endverbatim

@verbatim
<code-snippet name="Video Events" lang="php">
use Native\Mobile\Attributes\OnNative;
use Native\Mobile\Events\Camera\VideoRecorded;

#[OnNative(VideoRecorded::class)]
public function handleVideoRecorded(string $path, string $mimeType, ?string $id = null)
{
    $this->processVideo($path);
}
</code-snippet>
@endverbatim

@verbatim
<code-snippet name="Gallery Events" lang="php">
use Native\Mobile\Attributes\OnNative;
use Native\Mobile\Events\Gallery\MediaSelected;

#[OnNative(MediaSelected::class)]
public function handleMediaSelected(array $media)
{
    foreach ($media as $file) {
        $this->processMedia($file);
    }
}
</code-snippet>
@endverbatim

### Events

- `Native\Mobile\Events\Camera\PhotoTaken` - Photo captured (payload: `string $path`)
- `Native\Mobile\Events\Camera\VideoRecorded` - Video recorded (payload: `string $path`, `string $mimeType`, `?string $id`)
- `Native\Mobile\Events\Camera\VideoCancelled` - Recording cancelled
- `Native\Mobile\Events\Gallery\MediaSelected` - Media selected (payload: `array $media`)

### Capture Date and Location

`PhotoTaken` and each file in `MediaSelected` may additionally carry `takenAt` (ISO-8601 UTC string), `latitude` and `longitude` (decimal degrees). Keys are omitted when unavailable. `takenAt` is read from EXIF and needs no permissions. Pass `includeLocation: true` to actively recover GPS coordinates; this prompts for location / photo library access (iOS) or `ACCESS_MEDIA_LOCATION` (Android). Default is `false`, so no new prompts are shown.

@verbatim
<code-snippet name="Opting into location" lang="php">
use Native\Mobile\Facades\Camera;
use Native\Mobile\PendingMediaPicker;

Camera::getPhoto(['includeLocation' => true]);

(new PendingMediaPicker(['includeLocation' => true]))->images()->start();
</code-snippet>
@endverbatim

### Storage Locations

- **Photos (Android):** `{cache}/captured.jpg`
- **Photos (iOS):** `~/Library/Application Support/Photos/captured.jpg`
- **Videos (Android):** `{cache}/video_{timestamp}.mp4`
- **Videos (iOS):** `~/Library/Application Support/Videos/captured_video_{timestamp}.mp4`

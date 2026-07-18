<?php

namespace Native\Mobile\Providers\Testing;

use Native\Mobile\Testing\FakeBridge;

/**
 * Camera test vocabulary for the NativePHP testing suite, registered as
 * FakeBridge macros so app tests read in camera terms instead of raw
 * bridge method strings:
 *
 *     Native::test(ProfileEditor::class)
 *         ->tap('Take photo')
 *         ->assertPhotoRequested();
 *
 * getPhoto(), recordVideo() and pickImages() only *open* the native
 * camera/gallery UI — the result (PhotoTaken, VideoRecorded, MediaSelected,
 * or a Cancelled/PermissionDenied counterpart) arrives later as an async
 * event that the bridge never answers synchronously. So these macros only
 * assert the request was made; there's no with* scripting to preload a
 * captured photo or picked media the way withClipboard() preloads text.
 *
 * Registered by CameraServiceProvider when the app is running unit tests
 * on a core whose FakeBridge supports macros.
 */
class CameraMacros
{
    public static function register(): void
    {
        /** Assert a photo capture was requested (Camera::getPhoto()->start()). */
        FakeBridge::macro('assertPhotoRequested', function () {
            return $this->assertCalled('Camera.GetPhoto');
        });

        /** Assert a video recording was requested (Camera::recordVideo()->start()). */
        FakeBridge::macro('assertVideoRequested', function () {
            return $this->assertCalled('Camera.RecordVideo');
        });

        /**
         * Assert the gallery picker was opened (Camera::pickImages()->start()).
         * Pass a filter to match on the decoded params, e.g.:
         *
         *     $bridge->assertMediaPicked(fn ($p) => $p['mediaType'] === 'image' && $p['multiple'] === true);
         */
        FakeBridge::macro('assertMediaPicked', function (?callable $filter = null) {
            return $this->assertCalled('Camera.PickMedia', $filter);
        });

        /** Assert no photo, video, or media picker request was made. */
        FakeBridge::macro('assertNothingCaptured', function () {
            $this->assertNotCalled('Camera.GetPhoto');
            $this->assertNotCalled('Camera.RecordVideo');
            $this->assertNotCalled('Camera.PickMedia');

            return $this;
        });
    }
}

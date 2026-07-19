<?php

/**
 * Contract tests for the Camera facade (which ships in nativephp/mobile)
 * against THIS plugin's bridge functions, driven through the NativePHP
 * FakeBridge. They pin the PHP → native contract: calling the facade builds
 * a Pending* builder that, once started, fires the right bridge function
 * with the right payload and decodes native responses as callers expect.
 * Requires nativephp/mobile, so this file is bound to the Testbench
 * TestCase.
 *
 * Run with: ./test-plugins.sh --element camera
 */

use Native\Mobile\Camera;
use Native\Mobile\Events\Camera\PermissionDenied;
use Native\Mobile\Events\Camera\PhotoCancelled;
use Native\Mobile\Events\Camera\PhotoTaken;
use Native\Mobile\Events\Camera\VideoCancelled;
use Native\Mobile\Events\Camera\VideoRecorded;
use Native\Mobile\Events\Gallery\MediaSelected;
use Native\Mobile\Testing\Native;
use Tests\TestCase;

uses(TestCase::class);

beforeEach(function () {
    $this->bridge = Native::fakeBridge();
});

describe('getPhoto()', function () {
    it('fires Camera.GetPhoto with an id and the PhotoTaken event by default', function () {
        (new Camera)->getPhoto()->id('photo-1')->start();

        $this->bridge->assertCalled('Camera.GetPhoto', function (array $p) {
            expect($p['id'])->toBe('photo-1');
            expect($p['event'])->toBe(PhotoTaken::class);

            return true;
        });
    });

    it('generates an id when none is supplied', function () {
        (new Camera)->getPhoto()->start();

        $calls = $this->bridge->callsTo('Camera.GetPhoto');
        expect($calls)->toHaveCount(1);
        expect($calls[0]['params']['id'])->not->toBeEmpty();
    });

    it('generates a different id for each capture', function () {
        (new Camera)->getPhoto()->start();
        (new Camera)->getPhoto()->start();

        $calls = $this->bridge->callsTo('Camera.GetPhoto');
        expect($calls)->toHaveCount(2);
        expect($calls[0]['params']['id'])->not->toBe($calls[1]['params']['id']);
    });

    it('merges the constructor options array verbatim into the payload', function () {
        // GetPhoto's native side only reads id/event today; this pins the
        // facade-level contract that array_merge($options, [id, event]) is
        // used, so any option a caller passes reaches the bridge untouched.
        (new Camera)->getPhoto(['customOption' => 'opaque-value'])
            ->id('photo-opts')
            ->start();

        $this->bridge->assertCalled('Camera.GetPhoto', function (array $p) {
            expect($p['customOption'])->toBe('opaque-value');

            return true;
        });
    });

    it('lets a custom event class override the default PhotoTaken event', function () {
        (new Camera)->getPhoto()
            ->id('photo-custom-event')
            ->event(PhotoCancelled::class)
            ->start();

        $this->bridge->assertCalled('Camera.GetPhoto', function (array $p) {
            expect($p['event'])->toBe(PhotoCancelled::class);

            return true;
        });
    });

    it('throws when given a non-existent event class', function () {
        (new Camera)->getPhoto()->event('NotARealEventClass');
    })->throws(InvalidArgumentException::class);

    it('does not fire the bridge again when start() is called twice', function () {
        $capture = (new Camera)->getPhoto()->id('photo-once');

        expect($capture->start())->toBeFalse(); // no response scripted, so decodes to false
        $capture->start();

        $this->bridge->assertCalledTimes('Camera.GetPhoto', 1);
    });

    it('auto-starts on destruction if start() was never called explicitly', function () {
        $capture = (new Camera)->getPhoto()->id('photo-destruct');
        unset($capture);

        $this->bridge->assertCalled('Camera.GetPhoto', function (array $p) {
            expect($p['id'])->toBe('photo-destruct');

            return true;
        });
    });

    it('returns true when the bridge responds with a success status', function () {
        $this->bridge->respondTo('Camera.GetPhoto', ['status' => 'success']);

        $result = (new Camera)->getPhoto()->id('photo-ok')->start();

        expect($result)->toBeTrue();
    });

    it('returns false when the bridge responds with an error status', function () {
        $this->bridge->respondTo('Camera.GetPhoto', ['status' => 'error', 'message' => 'camera unavailable']);

        $result = (new Camera)->getPhoto()->id('photo-fail')->start();

        expect($result)->toBeFalse();
    });

    it('returns false when the bridge returns no response at all', function () {
        $result = (new Camera)->getPhoto()->id('photo-noresp')->start();

        expect($result)->toBeFalse();
    });
});

describe('recordVideo()', function () {
    it('fires Camera.RecordVideo with an id and the VideoRecorded event by default', function () {
        (new Camera)->recordVideo()->id('video-1')->start();

        $this->bridge->assertCalled('Camera.RecordVideo', function (array $p) {
            expect($p['id'])->toBe('video-1');
            expect($p['event'])->toBe(VideoRecorded::class);

            return true;
        });
    });

    it('generates an id when none is supplied', function () {
        (new Camera)->recordVideo()->start();

        $calls = $this->bridge->callsTo('Camera.RecordVideo');
        expect($calls)->toHaveCount(1);
        expect($calls[0]['params']['id'])->not->toBeEmpty();
    });

    it('passes maxDuration through when given in the options array', function () {
        (new Camera)->recordVideo(['maxDuration' => 30])
            ->id('video-maxdur')
            ->start();

        $this->bridge->assertCalled('Camera.RecordVideo', function (array $p) {
            expect($p['maxDuration'])->toBe(30);

            return true;
        });
    });

    it('passes maxDuration through when set via the fluent maxDuration() method', function () {
        (new Camera)->recordVideo()
            ->id('video-fluent-maxdur')
            ->maxDuration(60)
            ->start();

        $this->bridge->assertCalled('Camera.RecordVideo', function (array $p) {
            expect($p['maxDuration'])->toBe(60);

            return true;
        });
    });

    it('lets the fluent maxDuration() override a constructor-supplied value', function () {
        (new Camera)->recordVideo(['maxDuration' => 15])
            ->id('video-override-maxdur')
            ->maxDuration(45)
            ->start();

        $this->bridge->assertCalled('Camera.RecordVideo', function (array $p) {
            expect($p['maxDuration'])->toBe(45);

            return true;
        });
    });

    it('does not include maxDuration when it was never set', function () {
        (new Camera)->recordVideo()->id('video-no-maxdur')->start();

        $this->bridge->assertCalled('Camera.RecordVideo', function (array $p) {
            expect($p)->not->toHaveKey('maxDuration');

            return true;
        });
    });

    it('lets a custom event class override the default VideoRecorded event', function () {
        (new Camera)->recordVideo()
            ->id('video-custom-event')
            ->event(VideoCancelled::class)
            ->start();

        $this->bridge->assertCalled('Camera.RecordVideo', function (array $p) {
            expect($p['event'])->toBe(VideoCancelled::class);

            return true;
        });
    });

    it('throws when given a non-existent event class', function () {
        (new Camera)->recordVideo()->event('NotARealEventClass');
    })->throws(InvalidArgumentException::class);

    it('does not fire the bridge again when start() is called twice', function () {
        $recorder = (new Camera)->recordVideo()->id('video-once');

        $recorder->start();
        $recorder->start();

        $this->bridge->assertCalledTimes('Camera.RecordVideo', 1);
    });

    it('auto-starts on destruction if start() was never called explicitly', function () {
        $recorder = (new Camera)->recordVideo()->id('video-destruct');
        unset($recorder);

        $this->bridge->assertCalled('Camera.RecordVideo', function (array $p) {
            expect($p['id'])->toBe('video-destruct');

            return true;
        });
    });

    it('returns true when the bridge responds with a success status', function () {
        $this->bridge->respondTo('Camera.RecordVideo', ['status' => 'success']);

        $result = (new Camera)->recordVideo()->id('video-ok')->start();

        expect($result)->toBeTrue();
    });

    it('returns false when the bridge responds with an error status', function () {
        $this->bridge->respondTo('Camera.RecordVideo', ['status' => 'error']);

        $result = (new Camera)->recordVideo()->id('video-fail')->start();

        expect($result)->toBeFalse();
    });
});

describe('pickImages()', function () {
    it('fires Camera.PickMedia with defaults: mediaType=all, multiple=false, maxItems=10', function () {
        (new Camera)->pickImages()->id('pick-1')->start();

        $this->bridge->assertCalled('Camera.PickMedia', function (array $p) {
            expect($p['id'])->toBe('pick-1');
            expect($p['event'])->toBe(MediaSelected::class);
            expect($p['mediaType'])->toBe('all');
            expect($p['multiple'])->toBeFalse();
            expect($p['maxItems'])->toBe(10);

            return true;
        });
    });

    it('generates an id when none is supplied', function () {
        (new Camera)->pickImages()->start();

        $calls = $this->bridge->callsTo('Camera.PickMedia');
        expect($calls)->toHaveCount(1);
        expect($calls[0]['params']['id'])->not->toBeEmpty();
    });

    it('passes the media_type argument through as mediaType', function () {
        (new Camera)->pickImages('image')->id('pick-image')->start();

        $this->bridge->assertCalled('Camera.PickMedia', function (array $p) {
            expect($p['mediaType'])->toBe('image');

            return true;
        });
    });

    it('passes the video media_type argument through', function () {
        (new Camera)->pickImages('video')->id('pick-video')->start();

        $this->bridge->assertCalled('Camera.PickMedia', function (array $p) {
            expect($p['mediaType'])->toBe('video');

            return true;
        });
    });

    it('passes multiple and max_items arguments through', function () {
        (new Camera)->pickImages('all', true, 5)->id('pick-multi')->start();

        $this->bridge->assertCalled('Camera.PickMedia', function (array $p) {
            expect($p['multiple'])->toBeTrue();
            expect($p['maxItems'])->toBe(5);

            return true;
        });
    });

    it('lets the fluent images() method restrict media type', function () {
        (new Camera)->pickImages()->images()->id('pick-fluent-image')->start();

        $this->bridge->assertCalled('Camera.PickMedia', function (array $p) {
            expect($p['mediaType'])->toBe('image');

            return true;
        });
    });

    it('lets the fluent videos() method restrict media type', function () {
        (new Camera)->pickImages()->videos()->id('pick-fluent-video')->start();

        $this->bridge->assertCalled('Camera.PickMedia', function (array $p) {
            expect($p['mediaType'])->toBe('video');

            return true;
        });
    });

    it('lets the fluent all() method reset media type to all', function () {
        (new Camera)->pickImages('image')->all()->id('pick-fluent-all')->start();

        $this->bridge->assertCalled('Camera.PickMedia', function (array $p) {
            expect($p['mediaType'])->toBe('all');

            return true;
        });
    });

    it('lets the fluent multiple() method enable multi-selection with a custom max', function () {
        (new Camera)->pickImages()->multiple(true, 20)->id('pick-fluent-multiple')->start();

        $this->bridge->assertCalled('Camera.PickMedia', function (array $p) {
            expect($p['multiple'])->toBeTrue();
            expect($p['maxItems'])->toBe(20);

            return true;
        });
    });

    it('defaults the fluent multiple() max items to 10', function () {
        (new Camera)->pickImages()->multiple()->id('pick-fluent-multiple-default')->start();

        $this->bridge->assertCalled('Camera.PickMedia', function (array $p) {
            expect($p['multiple'])->toBeTrue();
            expect($p['maxItems'])->toBe(10);

            return true;
        });
    });

    it('lets the fluent single() method disable multi-selection', function () {
        (new Camera)->pickImages('all', true, 20)->single()->id('pick-fluent-single')->start();

        $this->bridge->assertCalled('Camera.PickMedia', function (array $p) {
            expect($p['multiple'])->toBeFalse();

            return true;
        });
    });

    it('lets a custom event class override the default MediaSelected event', function () {
        (new Camera)->pickImages()
            ->id('pick-custom-event')
            ->event(PermissionDenied::class)
            ->start();

        $this->bridge->assertCalled('Camera.PickMedia', function (array $p) {
            expect($p['event'])->toBe(PermissionDenied::class);

            return true;
        });
    });

    it('throws when given a non-existent event class', function () {
        (new Camera)->pickImages()->event('NotARealEventClass');
    })->throws(InvalidArgumentException::class);

    it('does not fire the bridge again when start() is called twice', function () {
        $picker = (new Camera)->pickImages()->id('pick-once');

        $picker->start();
        $picker->start();

        $this->bridge->assertCalledTimes('Camera.PickMedia', 1);
    });

    it('auto-starts on destruction if start() was never called explicitly', function () {
        $picker = (new Camera)->pickImages()->id('pick-destruct');
        unset($picker);

        $this->bridge->assertCalled('Camera.PickMedia', function (array $p) {
            expect($p['id'])->toBe('pick-destruct');

            return true;
        });
    });

    it('returns true when the bridge responds with a success status', function () {
        $this->bridge->respondTo('Camera.PickMedia', ['status' => 'success']);

        $result = (new Camera)->pickImages()->id('pick-ok')->start();

        expect($result)->toBeTrue();
    });

    it('returns false when the bridge responds with an error status', function () {
        $this->bridge->respondTo('Camera.PickMedia', ['status' => 'error']);

        $result = (new Camera)->pickImages()->id('pick-fail')->start();

        expect($result)->toBeFalse();
    });
});

describe('bridge isolation', function () {
    it('does not cross-contaminate calls between getPhoto, recordVideo and pickImages', function () {
        (new Camera)->getPhoto()->id('cross-photo')->start();
        (new Camera)->recordVideo()->id('cross-video')->start();
        (new Camera)->pickImages()->id('cross-pick')->start();

        $this->bridge->assertCalledTimes('Camera.GetPhoto', 1);
        $this->bridge->assertCalledTimes('Camera.RecordVideo', 1);
        $this->bridge->assertCalledTimes('Camera.PickMedia', 1);
    });
});

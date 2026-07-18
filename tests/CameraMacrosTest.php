<?php

/**
 * The camera test vocabulary this plugin registers on the FakeBridge
 * (assertPhotoRequested / assertVideoRequested / assertMediaPicked /
 * assertNothingCaptured) — the sugar app developers use instead of raw
 * bridge method strings.
 *
 * Skipped on cores whose FakeBridge predates macro support.
 */

use Native\Mobile\Camera;
use Native\Mobile\Testing\FakeBridge;
use Native\Mobile\Testing\Native;
use PHPUnit\Framework\AssertionFailedError;

uses(Tests\TestCase::class);

beforeEach(function () {
    if (! method_exists(FakeBridge::class, 'macro')) {
        $this->markTestSkipped('This core\'s FakeBridge does not support macros.');
    }

    $this->bridge = Native::fakeBridge();
});

describe('assertPhotoRequested()', function () {
    it('passes when a photo capture was started', function () {
        (new Camera)->getPhoto()->id('photo-1')->start();

        $this->bridge->assertPhotoRequested();
    });

    it('fails when no photo capture was started', function () {
        expect(fn () => $this->bridge->assertPhotoRequested())
            ->toThrow(AssertionFailedError::class);
    });
});

describe('assertVideoRequested()', function () {
    it('passes when a video recording was started', function () {
        (new Camera)->recordVideo()->id('video-1')->start();

        $this->bridge->assertVideoRequested();
    });

    it('fails when no video recording was started', function () {
        expect(fn () => $this->bridge->assertVideoRequested())
            ->toThrow(AssertionFailedError::class);
    });
});

describe('assertMediaPicked()', function () {
    it('passes when the gallery picker was opened, with no filter', function () {
        (new Camera)->pickImages()->id('pick-1')->start();

        $this->bridge->assertMediaPicked();
    });

    it('passes when a filter matches the decoded params', function () {
        (new Camera)->pickImages('image', true, 5)->id('pick-images')->start();

        $this->bridge->assertMediaPicked(
            fn (array $p) => $p['mediaType'] === 'image' && $p['multiple'] === true && $p['maxItems'] === 5
        );
    });

    it('fails when no picker was opened', function () {
        expect(fn () => $this->bridge->assertMediaPicked())
            ->toThrow(AssertionFailedError::class);
    });

    it('fails when the filter matches no call', function () {
        (new Camera)->pickImages('video')->id('pick-video')->start();

        expect(fn () => $this->bridge->assertMediaPicked(fn (array $p) => $p['mediaType'] === 'image'))
            ->toThrow(AssertionFailedError::class);
    });
});

describe('assertNothingCaptured()', function () {
    it('passes when nothing was requested', function () {
        $this->bridge->assertNothingCaptured();
    });

    it('fails after a photo capture was requested', function () {
        (new Camera)->getPhoto()->id('photo-oops')->start();

        expect(fn () => $this->bridge->assertNothingCaptured())
            ->toThrow(AssertionFailedError::class);
    });

    it('fails after a video recording was requested', function () {
        (new Camera)->recordVideo()->id('video-oops')->start();

        expect(fn () => $this->bridge->assertNothingCaptured())
            ->toThrow(AssertionFailedError::class);
    });

    it('fails after the gallery picker was opened', function () {
        (new Camera)->pickImages()->id('pick-oops')->start();

        expect(fn () => $this->bridge->assertNothingCaptured())
            ->toThrow(AssertionFailedError::class);
    });
});

<?php

namespace Native\Mobile\Providers;

use Illuminate\Support\ServiceProvider;
use Native\Mobile\Camera;
use Native\Mobile\Providers\Testing\CameraMacros;
use Native\Mobile\Testing\FakeBridge;

class CameraServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->app->singleton(Camera::class, function () {
            return new Camera;
        });

        // Test sugar (assertPhotoRequested() etc.) — only under a test runner,
        // and only on a core whose FakeBridge is macroable (the method_exists
        // guard keeps older v4 and v3 cores fatal-free).
        if ($this->app->runningUnitTests()
            && class_exists(FakeBridge::class)
            && method_exists(FakeBridge::class, 'macro')) {
            CameraMacros::register();
        }
    }
}

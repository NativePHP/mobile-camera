<?php

namespace Tests;

use Native\Mobile\NativeServiceProvider;
use Native\Mobile\Providers\CameraServiceProvider;
use Orchestra\Testbench\TestCase as BaseTestCase;

/**
 * Bootstraps a minimal Laravel app (Testbench) with the NativePHP core
 * provider — which loads the nativephp_call() polyfill — plus this plugin's
 * provider. The bridge tests assert the PHP → native contract (facade call →
 * bridge function + payload) via the FakeBridge, without a device.
 */
abstract class TestCase extends BaseTestCase
{
    protected function getPackageProviders($app)
    {
        return [
            NativeServiceProvider::class,
            CameraServiceProvider::class,
        ];
    }

    protected function getEnvironmentSetUp($app)
    {
        $app['config']->set('nativephp.app_id', 'com.test.app');
        $app['config']->set('nativephp.version', '1.0.0');
        $app['config']->set('nativephp.version_code', 1);
        $app['config']->set('app.name', 'Test App');
    }
}

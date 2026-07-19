<?php

/**
 * Plugin validation tests for Camera.
 *
 * Run with: ./vendor/bin/pest
 */
beforeEach(function () {
    // PLUGIN_PATH lets CI run this suite from an external Pest harness
    // (the plugin's own composer deps aren't resolvable on public
    // runners); locally the suite sits inside the plugin as usual.
    $this->pluginPath = getenv('PLUGIN_PATH') ?: dirname(__DIR__);
    $this->manifestPath = $this->pluginPath.'/nativephp.json';
});

describe('Plugin Manifest', function () {
    it('has a valid nativephp.json file', function () {
        expect(file_exists($this->manifestPath))->toBeTrue();

        json_decode(file_get_contents($this->manifestPath), true);

        expect(json_last_error())->toBe(JSON_ERROR_NONE);
    });

    it('has required fields', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);

        expect($manifest)->toHaveKeys(['namespace', 'bridge_functions']);
        expect($manifest['namespace'])->toBe('Camera');
    });

    it('declares every bridge function for both platforms', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);

        expect($manifest['bridge_functions'])->toBeArray()->not->toBeEmpty();

        $names = array_column($manifest['bridge_functions'], 'name');
        expect($names)->toContain(
            'Camera.GetPhoto',
            'Camera.RecordVideo',
            'Camera.PickMedia',
        );

        foreach ($manifest['bridge_functions'] as $function) {
            expect($function)->toHaveKeys(['name']);
            expect(isset($function['android']) || isset($function['ios']))->toBeTrue();
        }
    });

    it('declares a non-empty set of valid event class names', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);

        expect($manifest['events'])->toBeArray()->not->toBeEmpty();

        expect($manifest['events'])->toEqualCanonicalizing([
            'Native\\Mobile\\Events\\Camera\\PhotoTaken',
            'Native\\Mobile\\Events\\Camera\\PhotoCancelled',
            'Native\\Mobile\\Events\\Camera\\VideoRecorded',
            'Native\\Mobile\\Events\\Camera\\VideoCancelled',
            'Native\\Mobile\\Events\\Gallery\\MediaSelected',
            'Native\\Mobile\\Events\\Camera\\PermissionDenied',
        ]);

        foreach ($manifest['events'] as $event) {
            expect($event)->toBeString()->not->toBeEmpty();
            // A valid, fully-qualified class name (backslash-separated segments).
            expect($event)->toMatch('/^([A-Za-z_][A-Za-z0-9_]*\\\\)+[A-Za-z_][A-Za-z0-9_]*$/');
            expect(class_exists($event))->toBeTrue("Event class {$event} does not exist");
        }
    });

    it('requests camera, microphone and photo library permissions', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);

        expect($manifest['android']['permissions'])->toBeArray()->not->toBeEmpty();
        expect($manifest['android']['permissions'])->toContain(
            'android.permission.CAMERA',
            'android.permission.RECORD_AUDIO',
        );

        expect($manifest['ios']['info_plist'])->toBeArray()->not->toBeEmpty();
        expect($manifest['ios']['info_plist'])->toHaveKeys([
            'NSCameraUsageDescription',
            'NSMicrophoneUsageDescription',
            'NSPhotoLibraryUsageDescription',
        ]);
    });
});

describe('Native Code', function () {
    it('has matching bridge function classes in native code', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);

        $kotlinContent = implode('', array_map('file_get_contents', glob($this->pluginPath.'/resources/android/*.kt')));
        $swiftContent = implode('', array_map('file_get_contents', glob($this->pluginPath.'/resources/ios/*.swift')));

        foreach ($manifest['bridge_functions'] as $function) {
            if (isset($function['android'])) {
                $parts = explode('.', $function['android']);
                expect($kotlinContent)->toContain('class '.end($parts));
            }

            if (isset($function['ios'])) {
                $parts = explode('.', $function['ios']);
                expect($swiftContent)->toContain('class '.end($parts));
            }
        }
    });
});

describe('Composer Configuration', function () {
    it('has valid composer.json', function () {
        $composer = json_decode(file_get_contents($this->pluginPath.'/composer.json'), true);

        expect(json_last_error())->toBe(JSON_ERROR_NONE);
        expect($composer['type'])->toBe('nativephp-plugin');
        expect($composer['require'])->toHaveKey('php');
        expect($composer['require']['php'])->not->toBeEmpty();
        expect($composer['require'])->toHaveKey('nativephp/mobile');
    });

    it('registers a provider that maps to an existing file', function () {
        $composer = json_decode(file_get_contents($this->pluginPath.'/composer.json'), true);

        $providers = $composer['extra']['laravel']['providers'] ?? [];
        expect($providers)->not->toBeEmpty();

        foreach ($providers as $provider) {
            $matched = false;

            foreach ($composer['autoload']['psr-4'] as $prefix => $path) {
                if (! str_starts_with($provider, $prefix)) {
                    continue;
                }

                $relative = str_replace('\\', '/', substr($provider, strlen($prefix)));
                $file = $this->pluginPath.'/'.rtrim($path, '/').'/'.$relative.'.php';

                if (file_exists($file)) {
                    $matched = true;
                    break;
                }
            }

            expect($matched)->toBeTrue("Provider {$provider} does not map to a file");
        }
    });
});

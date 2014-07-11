Required Tools and Libraries
============================

 * [Android SDK](https://developer.android.com/sdk/installing/index.html?pkg=tools) (r23, platform tools r20)
 * [Android NDK](https://developer.android.com/tools/sdk/ndk/index.html) (r9d)
 * [Twitter4J](http://twitter4j.org/) (4.0.2)
 * [OpenCV4Android](http://opencv.org/) (2.4.9)
 * Perl

Build Procedure
===============

 1. Ensure that the Android SDK and NDK binaries are properly included in PATH.

 2. Generate a `local.properties` file with

	android update project --path <path to project directory>

 3. Link or copy `twitter4j-core-<version>.jar`,
    `twitter4j-media-support-<version>.jar`, and the entire OpenCV4Android
    folder `OpenCV-<version>-android-sdk` into the libs folder of the project.

 4. Twitter requires best-effort hiding of your API keys, so a script is
    included to apply a simple XOR obfuscation sceme to them. From the
    project's directory, run

	./obfuscate_twitter_keys.pl <Twitter Consumer Key> <Twitter Consumer Secret>

 5. From the project directory, build the native portion of the code:

	ndk-build

 6. For an debug build, from the project directory simply run

	ant debug

    The result will be `bin/MobileFace-debug.apk`. For a signed, release build
    see [the Android developer documentation][release-docs].

 7. The apk can be installed to a connected device or emulator with adb:

	adb install -r bin/MobileFace-debug.apk

[release-docs]: https://developer.android.com/tools/building/building-cmdline.html#ReleaseMode


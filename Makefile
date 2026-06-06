export ANDROID_HOME ?= $(HOME)/Library/Android/sdk

APK_DEBUG = app/build/outputs/apk/debug/app-debug.apk
APK_RELEASE = app/build/outputs/apk/release/app-release-unsigned.apk
PACKAGE = dev.screenrecorder

.PHONY: build release install uninstall run clean logs

## Build
build:
	./gradlew assembleDebug

release:
	./gradlew assembleRelease

## Device
install: build
	adb install -r $(APK_DEBUG)

install-release: release
	adb install -r $(APK_RELEASE)

uninstall:
	adb uninstall $(PACKAGE)

run: install
	adb shell am start -n $(PACKAGE)/.MainActivity

## Debug
logs:
	adb logcat -s RecordingService:* MainActivity:* -v time

## Clean
clean:
	./gradlew clean

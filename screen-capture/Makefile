help: ## Prints help for targets with comments
	@awk 'BEGIN {FS = ":.*##"; printf "\nUsage:\n  make \033[36m<target>\033[0m\n"} /^[a-zA-Z_0-9-]+:.*?##/ { printf "  \033[36m%-25s\033[0m %s\n", $$1, $$2 } /^##@/ { printf "\n\033[1m%s\033[0m\n", substr($$0, 5) } ' $(MAKEFILE_LIST)

export ANDROID_HOME ?= $(HOME)/Library/Android/sdk

APK_DEBUG = app/build/outputs/apk/debug/app-debug.apk
APK_RELEASE = app/build/outputs/apk/release/app-release-unsigned.apk
PACKAGE = dev.screenrecorder

.PHONY: build release install uninstall run clean logs

build: ## Build
	./gradlew assembleDebug

release: ## Release
	./gradlew assembleRelease

install: build ## Device
	adb install -r $(APK_DEBUG)

install-release: release ## Install release APK
	adb install -r $(APK_RELEASE)

uninstall: ## Uninstall app from device
	adb uninstall $(PACKAGE)

run: install ## Build, install & launch app
	adb shell am start -n $(PACKAGE)/.MainActivity

##@ Debug
logs: ## Stream logcat for app
	adb logcat -s RecordingService:* MainActivity:* -v time

##@ Clean
clean: ## Clean build artifacts
	./gradlew clean

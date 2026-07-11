SHELL := /bin/bash

-include .env
PHONE_HOST ?= pixel-5
SSH_PORT   ?= 8022
# Distribution flavor for local dev builds/tests (github/storefront -- see the "Distribution
# flavors" section of README.md). github is the default here because it's the sideload/self-update
# build this repo's own tooling (make install, make adb-install, real-device testing) targets;
# override with `make build FLAVOR=storefront` to build the Play/F-Droid variant instead, which
# has no self-update code at all.
FLAVOR     ?= github
APK        := app/build/outputs/apk/$(FLAVOR)/debug/Ramblr-$(shell grep -m1 'versionName = ' app/build.gradle.kts | sed -E 's/.*"(.*)".*/\1/')-$(FLAVOR)-debug.apk

export JAVA_HOME := /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME := $(HOME)/Library/Android/sdk
export PATH := $(JAVA_HOME)/bin:$(PATH)

.PHONY: build test install adb-install push-model clean

# Capitalized flavor name for Gradle's flavor-qualified task names (assembleGithubDebug, not
# assemblegithubDebug). GNU sed's `\U` case-conversion isn't available in BSD sed (macOS's
# default /usr/bin/sed), so this uses Make's own $(subst) instead: swap each known lowercase
# flavor name for its capitalized form directly. Add a line here if a third flavor ever exists.
FLAVOR_CAP = $(subst github,Github,$(subst storefront,Storefront,$(FLAVOR)))

build:
	./gradlew assemble$(FLAVOR_CAP)Debug
	@echo "APK: $(APK)"

test:
	./gradlew test$(FLAVOR_CAP)DebugUnitTest

install: build
	scp -P $(SSH_PORT) $(APK) $(PHONE_HOST):~/storage/downloads/phone-whisper.apk
	ssh -p $(SSH_PORT) $(PHONE_HOST) "termux-open ~/storage/downloads/phone-whisper.apk"
	@echo "APK sent — approve install on phone"

adb-install: build
	$(ANDROID_HOME)/platform-tools/adb install -r $(APK)

## Push a model to the phone's internal storage (usage: make push-model MODEL=/path/to/model-dir)
push-model:
	@test -n "$(MODEL)" || (echo "Usage: make push-model MODEL=/path/to/model-dir" && exit 1)
	$(ANDROID_HOME)/platform-tools/adb push $(MODEL)/ /data/local/tmp/$(notdir $(MODEL))/
	$(ANDROID_HOME)/platform-tools/adb shell "run-as com.kafkasl.phonewhisper mkdir -p files/models/$(notdir $(MODEL))"
	@for f in $$(ls $(MODEL)/*.onnx $(MODEL)/*.ort $(MODEL)/*.txt 2>/dev/null); do \
		echo "  copying $$(basename $$f)..."; \
		$(ANDROID_HOME)/platform-tools/adb shell "run-as com.kafkasl.phonewhisper cp /data/local/tmp/$(notdir $(MODEL))/$$(basename $$f) files/models/$(notdir $(MODEL))/$$(basename $$f)"; \
	done
	@echo "Model pushed: $(notdir $(MODEL))"

clean:
	./gradlew clean

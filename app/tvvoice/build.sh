#!/bin/bash
# Builds app/tvvoice/build/tv-voice.apk (TV Setup Voice, speech heard on the TV; release, signed with TV Setup's key) with Gradle.
# Needs JDK 17+ and the Android SDK; the first build downloads the Vosk model (checked by its SHA-256, build.gradle.kts).
set -e
cd "$(dirname "$0")"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[ -f ../tvsetup/signing.jks ] || { echo "app/tvsetup/signing.jks missing: build TV Setup first (./app/tvsetup/build.sh)"; exit 1; }
../gradle/gradlew -q -p ../gradle :tvvoice:assembleRelease
cp build/outputs/apk/release/tvvoice-release.apk build/tv-voice.apk
ls -l build/tv-voice.apk | awk '{print "built", $9, $5, "bytes"}'

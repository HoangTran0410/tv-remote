#!/bin/bash
# Builds app/tvvoice/build/tv-voice.apk (TV Setup Voice, speech heard on the TV; release, signed with TV Setup's key) with Gradle.
# Needs JDK 17+ and the Android SDK; the first build downloads the Vosk model (checked by its SHA-256, build.gradle.kts).
set -e
cd "$(dirname "$0")"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[ -f ../tvsetup/signing.jks ] || { echo "app/tvsetup/signing.jks missing: build TV Setup first (./app/tvsetup/build.sh)"; exit 1; }
# one APK per language: tv-voice.apk (Vietnamese; the name old TV Setups download) and tv-voice-en.apk (English)
for l in vi en; do
  out=tv-voice.apk; [ $l = vi ] || out=tv-voice-$l.apk
  ../gradle/gradlew -q -p ../gradle :tvvoice:assembleRelease -Pvoicelang=$l
  cp build/outputs/apk/release/tvvoice-release.apk build/$out
  ls -l build/$out | awk '{print "built", $9, $5, "bytes"}'
done

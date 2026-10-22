#!/usr/bin/env bash
set -euo pipefail
: "${ML_RELEASE_BUILD_DIR:?Set the clean git-archive build directory}"
export JAVA_HOME=/var/home/cthiele/.cache/codex-jdks/temurin-17
export ANDROID_HOME=/var/home/cthiele/Documents/Codex/2026-09-17/referenced-chatgpt-conversation-this-is-an/work/android-sdk
export GRADLE_USER_HOME=/var/home/cthiele/.gradle
export ML_ANDROID_KEYSTORE_FILE=/var/home/cthiele/.config/mission-leben-portal/signing/mission-leben-zentral-production.p12
ML_ANDROID_KEYSTORE_PASSWORD=$(tr -d '\r\n' < /var/home/cthiele/.config/mission-leben-portal/signing/keystore-password.txt)
export ML_ANDROID_KEYSTORE_PASSWORD
export ML_ANDROID_KEY_ALIAS=mission-leben-zentral
export ML_ANDROID_KEY_PASSWORD="$ML_ANDROID_KEYSTORE_PASSWORD"
cd "$ML_RELEASE_BUILD_DIR"
exec ./gradlew --no-daemon testDebugUnitTest testReleaseUnitTest lintRelease assembleRelease "$@"

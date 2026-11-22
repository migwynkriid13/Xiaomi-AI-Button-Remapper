#!/usr/bin/env bash
# The Gradle release sources, build inputs, signer and license assets for this project.
set -euo pipefail

before=${1:?expected before revision}
after=${2:?expected after revision}
if [[ "$before" =~ ^0+$ ]]; then
    before=$(git hash-object -t tree -w /dev/null)
elif ! git cat-file -e "$before^{tree}" 2>/dev/null; then
    git fetch --no-tags --depth=1 origin "$before"
fi

inputs=(
    app/build.gradle.kts app/proguard-rules.pro build.gradle.kts settings.gradle.kts
    gradle.properties gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar
    ':(glob)gradle/**/gradle-wrapper.properties' ':(glob)gradle/**/*.toml'
    ':(glob)gradle/**/*.gradle*' ':(glob)gradle/**/*.xml' ':(glob)gradle/**/*.lockfile'
    gradle/release-certificate.sha256 gradle.lockfile app/gradle.lockfile
    buildSrc/ build-logic/ LICENSE THIRD_PARTY.md third-party/ .github/scripts/sign-apk.sh
)
for source in app/src/{main,release}; do
    inputs+=("$source/AndroidManifest.xml" "$source/assets/" "$source/res/" "$source/resources/")
    for language in java kotlin; do
        inputs+=(":(glob)$source/$language/**/*.kt" ":(glob)$source/$language/**/*.java")
    done
done
if git diff --quiet "$before" "$after" -- "${inputs[@]}"; then
    echo false
else
    status=$?
    [[ "$status" -eq 1 ]] || exit "$status"
    echo true
fi

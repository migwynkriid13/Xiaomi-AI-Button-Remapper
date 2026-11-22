#!/usr/bin/env bash
set -euo pipefail
filter="$(cd "$(dirname "$0")" && pwd)/apk-changes.sh"
fixture=$(mktemp -d)
trap 'rm -rf -- "$fixture"' EXIT
cd "$fixture"
git init -q
git config user.name "Release filter test"
git config user.email "fixture@localhost"
git config commit.gpgsign false
git config core.autocrlf false
git commit -qm base --allow-empty
touch_file() { mkdir -p "$(dirname "$1")"; echo "$1" > "$1"; }
save() { git add -A && git commit -qm fixture; }
expect() { [[ "$(bash "$filter" "$1" HEAD)" == "$2" ]] || { echo "Unexpected release decision: $1..HEAD, expected $2" >&2; exit 1; }; }

for path in README.md docs/guide.md .github/workflows/android.yml app/src/test/cpp/input_tests.cpp app/src/debug/res/raw/debug.json app/src/main/java/README.md gradle/README.md; do
    touch_file "$path"; save; expect HEAD^ false
done
for path in app/src/main/AndroidManifest.xml app/src/main/java/p/App.kt app/src/main/aidl/p/IHelper.aidl app/src/main/res/raw/data.json app/src/main/assets/README.md app/src/main/cpp/native.cpp app/src/release/java/Foo.java gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties gradle/libs.versions.toml app/build.gradle.kts LICENSE THIRD_PARTY.md third-party/notice.txt .github/scripts/sign-apk.sh; do
    touch_file "$path"; save; expect HEAD^ true
done
# Path filters must catch deletion/renaming and inspect the whole push, including changes after 300 files.
git mv app/src/main/java/p/App.kt docs/former-app.txt; save; expect HEAD^ true
for i in {1..305}; do touch_file "docs/samples/$i.txt"; done
save; expect HEAD^ false
touch_file app/src/main/assets/start-adb.sh; save; expect HEAD~2 true
git rm -q app/src/main/assets/start-adb.sh; save; expect HEAD~2 false
expect 0000000000000000000000000000000000000000 true
echo "APK release path checks passed."

#!/usr/bin/env bash
# Run only in the trusted release job after tests. Never print key material.
set -euo pipefail
umask 077
unsigned=${1:?expected an unsigned release APK}
signed=${2:?expected output APK}
scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT
tools="$ANDROID_HOME/build-tools/36.0.0"
printf '%s' "${ANDROID_RELEASE_KEYSTORE:?missing signing key}" | base64 --decode > "$scratch/identity.p12"
unset ANDROID_RELEASE_KEYSTORE
"$tools/apksigner" sign --ks "$scratch/identity.p12" --ks-key-alias remapper-release \
    --ks-pass env:ANDROID_RELEASE_PASSWORD --out "$signed" "$unsigned"
unset ANDROID_RELEASE_PASSWORD
"$tools/apksigner" verify --verbose --print-certs "$signed" > "$scratch/certificates.txt"
actual=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$scratch/certificates.txt")
expected=$(cat gradle/release-certificate.sha256)
[[ "$actual" == "$expected" ]] || { echo "Unexpected release signing certificate" >&2; exit 1; }
"$tools/zipalign" -c -P 16 4 "$signed"
sha256sum "$signed"

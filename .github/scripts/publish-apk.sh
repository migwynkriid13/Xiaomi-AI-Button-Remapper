#!/usr/bin/env bash
set -euo pipefail
apk=${1:?expected signed APK}
version="0.2.$GITHUB_RUN_NUMBER"
tag="v$version"
asset=$(basename -- "$apk")
# A rerun of an already-published build must leave its APK and tag intact.
if details=$(gh release view "$tag" --json isDraft,assets 2>/dev/null); then
    if [[ $(jq -r '.isDraft' <<< "$details") == false ]]; then
        [[ $(gh api "repos/$GITHUB_REPOSITORY/commits/$tag" --jq .sha) == "$GITHUB_SHA" ]]
        jq --exit-status --arg asset "$asset" '.assets | any(.name == $asset and .state == "uploaded")' <<< "$details"
        exit 0
    fi
else
    checksum=$(sha256sum "$apk" | cut -d ' ' -f1)
    printf 'Install **%s** on Android 11 or later. Start Shizuku, then open the remapper and tap **Grant Shizuku access**. Press the AI button during the read-only test. When you see DOWN and UP, turn on **Remap button** and allow its notification. Keep Shizuku running while remapping.\n\nUpgrading from v0.1.0-preview.1? Export your settings before uninstalling the debug preview, then install this release and import them.\n\n[Setup and permissions](https://github.com/%s/blob/%s/README.md) · [Verified build](%s/%s/actions/runs/%s)\n\nAPK SHA-256: `%s`\n' \
        "$asset" "$GITHUB_REPOSITORY" "$GITHUB_SHA" "$GITHUB_SERVER_URL" "$GITHUB_REPOSITORY" "$GITHUB_RUN_ID" "$checksum" > "$RUNNER_TEMP/release-notes.md"
    gh release create "$tag" --draft --target "$GITHUB_SHA" --title "Xiaomi AI Button Remapper $version" --notes-file "$RUNNER_TEMP/release-notes.md"
fi
[[ $(gh release view "$tag" --json targetCommitish --jq .targetCommitish) == "$GITHUB_SHA" ]]
gh release upload "$tag" "$apk" --clobber
# A late rerun of an older build should never replace a newer default download.
latest=$(gh api --paginate "repos/$GITHUB_REPOSITORY/releases" --jq '.[] | select(.draft == false and .prerelease == false) | .tag_name' |
    sed -n 's/^v0\.2\.\([0-9][0-9]*\)$/\1/p' | sort -nr | sed -n '1p')
make_latest=true
if [[ -n "$latest" && "$latest" -gt "$GITHUB_RUN_NUMBER" ]]; then make_latest=false; fi
gh release edit "$tag" --draft=false --latest="$make_latest"

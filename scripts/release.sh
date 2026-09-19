#!/usr/bin/env bash
# Cuts a versioned, signed release build and publishes it as a GitHub Release.
#
# Usage: scripts/release.sh <new-version>   e.g. scripts/release.sh 0.2.0
#
# What it does, in order:
#   1. Sanity-checks the working tree is clean and the version is well-formed.
#   2. Bumps lighttool.toml's versionName/versionCode.
#   3. Commits that bump.
#   4. Pulls the release keystore password from macOS Keychain (never
#      hardcoded — see build.gradle.kts's signingConfigs block).
#   5. Syncs this repo's source and lighttool.toml into a light-sdk checkout
#      and builds a signed release APK via ./gradlew :tool:assembleRelease
#      (see SETUP.md for why this repo can't build standalone). The APK's own
#      manifest is then read back, and the release stops here if it doesn't
#      carry the version just bumped to.
#   6. Tags the bump commit vX.Y.Z and pushes the commit + tag.
#   7. Creates a GitHub Release for that tag with the APK attached and
#      auto-generated release notes (commits since the last tag).
#   8. Re-points the rolling "latest" release/tag at this same commit, with
#      the APK re-uploaded under a fixed filename (musicplus-latest.apk) —
#      gives a permanent download link that never needs updating by hand:
#      github.com/<repo>/releases/download/latest/musicplus-latest.apk
#
# Requires LIGHT_SDK_PATH to point at a real light-sdk checkout (JDK 21 is
# used for the build regardless of the active `java` on PATH, matching the
# JDK 26/AndroidJdkImage incompatibility hit during development).

set -euo pipefail

NEW_VERSION="${1:?Usage: scripts/release.sh <new-version>, e.g. scripts/release.sh 0.2.0}"
if ! [[ "$NEW_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Version must be semver MAJOR.MINOR.PATCH (got: $NEW_VERSION)" >&2
  exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIGHT_SDK_PATH="${LIGHT_SDK_PATH:-$HOME/github/light-sdk}"
cd "$REPO_ROOT"

if [[ -n "$(git status --short)" ]]; then
  echo "Working tree isn't clean — commit or stash first." >&2
  git status --short >&2
  exit 1
fi

# Needed after the build, to read the version back out of the APK. Looked up
# now so that a missing tool stops the release before anything is committed.
ANDROID_SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}}"
AAPT2="$(ls -d "$ANDROID_SDK_DIR"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true)"
if [[ -z "$AAPT2" ]]; then
  echo "aapt2 not found under $ANDROID_SDK_DIR/build-tools — set ANDROID_HOME to your Android SDK." >&2
  exit 1
fi

CURRENT_VERSION_NAME=$(grep '^versionName' lighttool.toml | sed -E 's/.*"([^"]+)".*/\1/')
CURRENT_VERSION_CODE=$(grep '^versionCode' lighttool.toml | grep -oE '[0-9]+')
NEW_VERSION_CODE=$((CURRENT_VERSION_CODE + 1))

echo "Bumping $CURRENT_VERSION_NAME (code $CURRENT_VERSION_CODE) -> $NEW_VERSION (code $NEW_VERSION_CODE)"

sed -i '' -E "s/^versionCode( *)=.*/versionCode\1= $NEW_VERSION_CODE/" lighttool.toml
sed -i '' -E "s/^versionName( *)=.*/versionName\1= \"$NEW_VERSION\"/" lighttool.toml

git add lighttool.toml
git commit -m "Release v$NEW_VERSION"

echo "Reading release keystore password from Keychain..."
export MUSICPLUS_RELEASE_KEYSTORE_PATH="$HOME/.android/keystores/musicplus-release.jks"
export MUSICPLUS_RELEASE_KEYSTORE_PASSWORD
MUSICPLUS_RELEASE_KEYSTORE_PASSWORD="$(security find-generic-password -a musicplus-release -s musicplus-release-keystore-password -w)"

echo "Syncing source and manifest into $LIGHT_SDK_PATH/tool..."
rsync -a --delete "$REPO_ROOT/src/" "$LIGHT_SDK_PATH/tool/src/"
# The light-sdk build plugin takes the manifest (id, version, permissions,
# capabilities) from the checkout's own tool/lighttool.toml, not from this
# repo's. That copy was made once, at setup, and never refreshed, so every
# release APK up to v0.5.1 carried versionCode 1 / "0.1.0" whatever this
# repo's toml said.
cp "$REPO_ROOT/lighttool.toml" "$LIGHT_SDK_PATH/tool/lighttool.toml"

echo "Building signed release APK..."
JAVA_HOME="$(/usr/libexec/java_home -v 21)" \
  "$LIGHT_SDK_PATH/gradlew" -p "$LIGHT_SDK_PATH" :tool:assembleRelease

BUILT_APK_PATH="$LIGHT_SDK_PATH/tool/build/outputs/apk/release/tool-release.apk"
if [[ ! -f "$BUILT_APK_PATH" ]]; then
  echo "Expected APK not found at $BUILT_APK_PATH" >&2
  exit 1
fi

# Read the version back out of the APK itself, since that is what the phone
# sees, and stop before tagging or pushing if it isn't the one just bumped to.
BUILT_PACKAGE_LINE="$("$AAPT2" dump badging "$BUILT_APK_PATH" | grep '^package:' || true)"
if [[ "$BUILT_PACKAGE_LINE" != *"versionCode='$NEW_VERSION_CODE'"* || "$BUILT_PACKAGE_LINE" != *"versionName='$NEW_VERSION'"* ]]; then
  echo "Built APK doesn't carry the new version (wanted code $NEW_VERSION_CODE, name $NEW_VERSION):" >&2
  echo "  ${BUILT_PACKAGE_LINE:-<no package line from aapt2>}" >&2
  exit 1
fi
echo "Built APK reports versionCode $NEW_VERSION_CODE / versionName $NEW_VERSION."

# Copy to the real intended filename — `gh release create file#label` only
# labels the asset on the page, it doesn't rename what actually downloads.
APK_PATH="$(dirname "$BUILT_APK_PATH")/musicplus-v$NEW_VERSION.apk"
cp "$BUILT_APK_PATH" "$APK_PATH"

unset MUSICPLUS_RELEASE_KEYSTORE_PASSWORD

echo "Tagging and pushing..."
git tag -a "v$NEW_VERSION" -m "Release v$NEW_VERSION"
CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
for remote in $(git remote); do
  git push "$remote" "$CURRENT_BRANCH"
  git push "$remote" "v$NEW_VERSION"
done

echo "Creating GitHub Release..."
gh release create "v$NEW_VERSION" "$APK_PATH" \
  --title "v$NEW_VERSION" \
  --generate-notes

echo "Re-pointing rolling 'latest' release at v$NEW_VERSION..."
LATEST_APK_PATH="$(dirname "$BUILT_APK_PATH")/musicplus-latest.apk"
cp "$BUILT_APK_PATH" "$LATEST_APK_PATH"
# Delete-and-recreate rather than `gh release edit` + `upload --clobber` —
# the tag itself needs to move to this commit too, and `gh release delete
# --cleanup-tag` is the simplest way to do both the release and the tag in
# one step. Ignore failure the first time this ever runs (nothing to delete
# yet).
gh release delete latest --yes --cleanup-tag 2>/dev/null || true
gh release create latest "$LATEST_APK_PATH" \
  --title "Latest build ($NEW_VERSION)" \
  --notes "Always points at the most recently released build — currently v$NEW_VERSION. See the versioned release (v$NEW_VERSION) for real release notes; this one exists only so github.com/queueingqt/musicplus/releases/download/latest/musicplus-latest.apk never needs updating by hand." \
  --target "$(git rev-parse HEAD)"

echo "Done: v$NEW_VERSION released, 'latest' repointed at it."

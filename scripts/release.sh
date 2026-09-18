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
#   5. Syncs this repo's source into a light-sdk checkout and builds a signed
#      release APK via ./gradlew :tool:assembleRelease (see SETUP.md for why
#      this repo can't build standalone).
#   6. Tags the bump commit vX.Y.Z and pushes the commit + tag.
#   7. Creates a GitHub Release for that tag with the APK attached and
#      auto-generated release notes (commits since the last tag).
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

echo "Syncing source into $LIGHT_SDK_PATH/tool..."
rsync -a --delete "$REPO_ROOT/src/" "$LIGHT_SDK_PATH/tool/src/"

echo "Building signed release APK..."
JAVA_HOME="$(/usr/libexec/java_home -v 21)" \
  "$LIGHT_SDK_PATH/gradlew" -p "$LIGHT_SDK_PATH" :tool:assembleRelease

APK_PATH="$LIGHT_SDK_PATH/tool/build/outputs/apk/release/tool-release.apk"
if [[ ! -f "$APK_PATH" ]]; then
  echo "Expected APK not found at $APK_PATH" >&2
  exit 1
fi

unset MUSICPLUS_RELEASE_KEYSTORE_PASSWORD

echo "Tagging and pushing..."
git tag -a "v$NEW_VERSION" -m "Release v$NEW_VERSION"
CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
for remote in $(git remote); do
  git push "$remote" "$CURRENT_BRANCH"
  git push "$remote" "v$NEW_VERSION"
done

echo "Creating GitHub Release..."
gh release create "v$NEW_VERSION" "$APK_PATH#musicplus-v$NEW_VERSION.apk" \
  --title "v$NEW_VERSION" \
  --generate-notes

echo "Done: v$NEW_VERSION released."

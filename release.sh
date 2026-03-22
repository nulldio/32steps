#!/bin/bash
set -e

# Read current version
CURRENT_CODE=$(grep "versionCode" app/build.gradle.kts | grep -o '[0-9]*')
CURRENT_NAME=$(grep "versionName" app/build.gradle.kts | grep -o '"[^"]*"' | tr -d '"')

# Bump
NEW_CODE=$((CURRENT_CODE + 1))
MAJOR=$(echo "$CURRENT_NAME" | cut -d. -f1)
MINOR=$(echo "$CURRENT_NAME" | cut -d. -f2)
NEW_NAME="$MAJOR.$((MINOR + 1))"

echo "Bumping v$CURRENT_NAME (code $CURRENT_CODE) → v$NEW_NAME (code $NEW_CODE)"

# Prompt for changelog
read -p "Changelog: " CHANGELOG
if [ -z "$CHANGELOG" ]; then
    echo "Changelog required."
    exit 1
fi

# Update build.gradle.kts
sed -i '' "s/versionCode = $CURRENT_CODE/versionCode = $NEW_CODE/" app/build.gradle.kts
sed -i '' "s/versionName = \"$CURRENT_NAME\"/versionName = \"$NEW_NAME\"/" app/build.gradle.kts

# Create changelog
echo "$CHANGELOG" > "fastlane/metadata/android/en-US/changelogs/$NEW_CODE.txt"

# Commit, tag, push
git add -A
git commit -m "v$NEW_NAME - $CHANGELOG"
git tag "v$NEW_NAME"
git push origin main
git push origin "v$NEW_NAME"

echo ""
echo "Done. Now:"
echo "1. Clean build in Android Studio"
echo "2. Upload app/build/outputs/apk/release/app-release.apk to:"
echo "   https://github.com/nulldio/32steps/releases/new?tag=v$NEW_NAME"

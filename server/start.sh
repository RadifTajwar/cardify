#!/bin/sh
# Starts the Cardify server with the token the app is built with: cardify.token from
# ~/.gradle/gradle.properties (falls back to android/gradle.properties).
cd "$(dirname "$0")" || exit 1
TOKEN=$(sed -n 's/^cardify\.token=//p' "$HOME/.gradle/gradle.properties" 2>/dev/null)
[ -n "$TOKEN" ] || TOKEN=$(sed -n 's/^cardify\.token=//p' ../android/gradle.properties)
CARDIFY_TOKEN=$TOKEN exec go run .

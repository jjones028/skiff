#!/bin/sh
# Builds a native installer with jlink + jpackage: packaging/jpackage.sh <msi|dmg> <skiff-all.jar> <version> <output-dir>
# The runtime is a compressed jlink image of every JDK module, because skiff runs the Kotlin compiler (and javac for
# generated Java) in a child JVM on its own java.home, so a minimal runtime would break builds.
set -eu

TYPE=$1
JAR=$(cd "$(dirname "$2")" && pwd)/$(basename "$2")
VERSION=$3
OUT=$4

WORK=$(mktemp -d)
mkdir -p "$WORK/input" "$OUT"
cp "$JAR" "$WORK/input/skiff.jar"

MODULES=$(java --list-modules | sed 's/@.*//' | tr -d '\r' | paste -sd, -)
jlink --add-modules "$MODULES" --strip-debug --no-man-pages --no-header-files --compress zip-6 --output "$WORK/runtime"

# macOS rejects a major version of 0, so 0.1.0 is packaged as 1.1.0 there.
case "$TYPE" in
    dmg) [ "${VERSION%%.*}" = 0 ] && VERSION="1.${VERSION#*.}" ;;
esac

# The launcher is named after the app, so the app is called `skf`, the command this installs.
set -- --type "$TYPE" --name skf --app-version "$VERSION" --vendor Skiff \
    --description "Skiff: a package manager and build tool for Kotlin" \
    --input "$WORK/input" --main-jar skiff.jar --main-class skiff.MainKt \
    --java-options "-XX:TieredStopAtLevel=1 -XX:+UseSerialGC" \
    --runtime-image "$WORK/runtime" --dest "$OUT"
case "$TYPE" in
    msi) set -- "$@" --win-console --win-dir-chooser --win-upgrade-uuid 6f0f7a3e-3c1b-4a8e-9a53-5d2f6a1b7c42 ;;
esac
jpackage "$@"

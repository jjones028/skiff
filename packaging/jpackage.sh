#!/bin/sh
# Builds a native installer with jlink + jpackage: packaging/jpackage.sh <msi|pkg> <skiff-all.jar> <version> <output-dir>
# Both put the `skf` command on PATH: the MSI through an Environment entry (packaging/windows/main.wxs), the pkg through
# a postinstall script that links it into /usr/local/bin (packaging/macos/scripts).
# For pkg, set SKF_MAC_APP_IDENTITY ("Developer ID Application: ...") and SKF_MAC_INSTALLER_IDENTITY ("Developer ID
# Installer: ...") to sign it (the certificates must be in the default keychain search list); without them it is unsigned.
# Notarizing is a separate step, because it needs the finished pkg (see package.yml).
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
    pkg) [ "${VERSION%%.*}" = 0 ] && VERSION="1.${VERSION#*.}" ;;
esac

HERE=$(cd "$(dirname "$0")" && pwd)

# The launcher is named after the app, so the app is called `skf`, the command this installs.
set -- --name skf --app-version "$VERSION" --vendor Skiff     --description "Skiff: a package manager and build tool for Kotlin"     --input "$WORK/input" --main-jar skiff.jar --main-class skiff.MainKt     --java-options "-XX:TieredStopAtLevel=1 -XX:+UseSerialGC"     --runtime-image "$WORK/runtime"
case "$TYPE" in
    msi)
        jpackage "$@" --type msi --dest "$OUT" --resource-dir "$HERE/windows"             --win-console --win-dir-chooser --win-upgrade-uuid 6f0f7a3e-3c1b-4a8e-9a53-5d2f6a1b7c42
        ;;
    pkg)
        # jpackage's own pkg has no hook for running a script, so build the app image and wrap it with pkgbuild.
        # Signing the app image makes jpackage sign every binary in the runtime with the hardened runtime and its
        # default entitlements, which notarization requires.
        if [ -n "${SKF_MAC_APP_IDENTITY:-}" ]; then
            jpackage "$@" --type app-image --dest "$WORK/app" --mac-sign --mac-app-image-sign-identity "$SKF_MAC_APP_IDENTITY"
        else
            jpackage "$@" --type app-image --dest "$WORK/app"
        fi
        if [ -n "${SKF_MAC_INSTALLER_IDENTITY:-}" ]; then
            set -- --sign "$SKF_MAC_INSTALLER_IDENTITY"
        else
            set --
        fi
        pkgbuild "$@" --component "$WORK/app/skf.app" --install-location /Applications --scripts "$HERE/macos/scripts"             --identifier io.github.jjones028.skiff --version "$VERSION" "$OUT/skf-$VERSION.pkg"
        ;;
    *) echo "unknown installer type: $TYPE" >&2; exit 1 ;;
esac

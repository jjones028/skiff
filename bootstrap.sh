#!/bin/sh
# Builds bin/skiff.jar without Gradle or a local kotlinc: downloads the Kotlin compiler jars from
# Maven Central into skiff's cache, compiles src/, and bundles the Kotlin stdlib into the jar.
# After this, skiff can rebuild itself with `bin/skf build --fat`.
set -eu

KOTLIN_VERSION=2.4.21
JVM_TARGET=25
ROOT="$(cd "$(dirname "$0")" && pwd)"
CACHE="${SKIFF_HOME:-$HOME/.skiff}/cache/maven"
CENTRAL=https://repo.maven.apache.org/maven2
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
case "$(uname -s)" in CYGWIN*|MINGW*|MSYS*) SEP=';' ;; *) SEP=':' ;; esac

# Downloads group:artifact:version into the cache (verifying its SHA-1) and prints the jar path.
get_jar() {
    group=$(echo "$1" | cut -d: -f1); artifact=$(echo "$1" | cut -d: -f2); version=$(echo "$1" | cut -d: -f3)
    relative="$(echo "$group" | tr . /)/$artifact/$version/$artifact-$version.jar"
    file="$CACHE/$relative"
    if [ ! -f "$file" ]; then
        mkdir -p "$(dirname "$file")"
        echo "  Downloading $artifact-$version.jar" >&2
        curl -fsSL "$CENTRAL/$relative" -o "$file.part"
        expected=$(curl -fsSL "$CENTRAL/$relative.sha1" | cut -d' ' -f1)
        actual=$(sha1sum "$file.part" | cut -d' ' -f1)
        [ "$expected" = "$actual" ] || { rm -f "$file.part"; echo "checksum mismatch for $relative" >&2; exit 1; }
        mv "$file.part" "$file"
    fi
    echo "$file"
}

# The runtime classpath of org.jetbrains.kotlin:kotlin-compiler-embeddable (see its POM).
COMPILER_CP=""
for coordinate in \
    "org.jetbrains.kotlin:kotlin-compiler-embeddable:$KOTLIN_VERSION" \
    "org.jetbrains.kotlin:kotlin-build-tools-api:$KOTLIN_VERSION" \
    "org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN_VERSION" \
    "org.jetbrains.kotlin:kotlin-script-runtime:$KOTLIN_VERSION" \
    "org.jetbrains.kotlin:kotlin-daemon-embeddable:$KOTLIN_VERSION" \
    "org.jetbrains.kotlin:kotlin-reflect:1.6.10" \
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0" \
    "org.jetbrains:annotations:13.0"
do
    COMPILER_CP="$COMPILER_CP${COMPILER_CP:+$SEP}$(get_jar "$coordinate")"
done
STDLIB=$(get_jar "org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN_VERSION")

OUT="$ROOT/build/bootstrap"
CLASSES="$OUT/classes"
rm -rf "$OUT"
mkdir -p "$CLASSES"

echo "  Compiling skiff"
find "$ROOT/src" -name '*.kt' > "$OUT/sources.txt"
"${JAVA_BIN}java" --sun-misc-unsafe-memory-access=allow -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -d "$CLASSES" -classpath "$STDLIB" -no-stdlib -no-reflect -jvm-target "$JVM_TARGET" "-Xjdk-release=$JVM_TARGET" \
    -module-name skiff "@$OUT/sources.txt"

# Bundle the Kotlin stdlib so `java -jar skiff.jar` works on its own.
(cd "$CLASSES" && "${JAVA_BIN}jar" xf "$STDLIB" && rm -rf META-INF/MANIFEST.MF META-INF/versions)
"${JAVA_BIN}jar" --create --file "$ROOT/bin/skiff.jar" --main-class skiff.MainKt -C "$CLASSES" .
echo "  Built $ROOT/bin/skiff.jar"

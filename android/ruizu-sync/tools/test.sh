#!/usr/bin/env bash
# Runs the JVM test suites without Android or Gradle: pure logic (core), the transfer pipeline
# simulation, RSS feed scanning and the original TransferUtils tests.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/java/au/com/parristech/ruizusync"
TEST="$ROOT/app/src/test/java"
BUILD="$ROOT/.build/test"
DEPS="$ROOT/.build/test-deps"
JUNIT="$DEPS/junit-4.13.2.jar"
HAMCREST="$DEPS/hamcrest-core-1.3.jar"

mkdir -p "$BUILD/classes" "$BUILD/test-classes" "$DEPS"
if [ ! -s "$JUNIT" ]; then
  echo "Downloading JUnit 4.13.2..."
  curl -fsSL -o "$JUNIT" https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar
fi
if [ ! -s "$HAMCREST" ]; then
  echo "Downloading Hamcrest 1.3..."
  curl -fsSL -o "$HAMCREST" https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar
fi

# The JVM-testable subset: no android.* imports.
LIB_CP=""
for jar in "$ROOT"/app/libs/*.jar; do
  [ -f "$jar" ] && LIB_CP="$LIB_CP:$jar"
done
find "$SRC/core" "$SRC/net/FeedScanner.java" "$SRC/audio/Mp3Encoder.java" "$SRC/TransferUtils.java" \
  -name '*.java' | sort > "$BUILD/main-sources.txt"
echo "Compiling $(wc -l < "$BUILD/main-sources.txt") main source files..."
javac --release 17 -encoding UTF-8 -classpath ".$LIB_CP" -d "$BUILD/classes" @"$BUILD/main-sources.txt"

find "$TEST" -name '*.java' | sort > "$BUILD/test-sources.txt"
echo "Compiling $(wc -l < "$BUILD/test-sources.txt") test source files..."
javac --release 17 -encoding UTF-8 -classpath "$BUILD/classes:$JUNIT:$HAMCREST$LIB_CP" \
  -d "$BUILD/test-classes" @"$BUILD/test-sources.txt"

echo "Running JUnit suites..."
java -cp "$BUILD/classes:$BUILD/test-classes:$JUNIT:$HAMCREST$LIB_CP" org.junit.runner.JUnitCore \
  au.com.parristech.ruizusync.core.LogicGradleTests \
  au.com.parristech.ruizusync.core.OpmlTest \
  au.com.parristech.ruizusync.net.FeedScannerTest \
  au.com.parristech.ruizusync.audio.Mp3EncoderTest \
  au.com.parristech.ruizusync.core.WavPcmReaderTest \
  au.com.parristech.ruizusync.TransferUtilsTest

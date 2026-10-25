#!/usr/bin/env bash
# Builds both APKs without Gradle or the SDK, for a slow connection: a JDK 17 plus ~50 MB of single files in
# $TOOLS/mini — aapt2 and apksig from Google's Maven, r8 (d8), an android.jar to compile against — and zxing's
# core jar for the launcher's QR code. tools/Sign.java signs with apksig (v1+v2+v3, aligned).
# Signed with the local debug key, so the TV's release-signed apps must be uninstalled first.
#   TOOLS=~/.urtv-tools android/build-local.sh      → android/out/urtv-screensaver.apk, urtv-home.apk
set -euo pipefail
cd "$(dirname "$0")"
TOOLS=${TOOLS:-$HOME/.urtv-tools}
export JAVA_HOME=${JAVA_HOME:-$TOOLS/jdk/Contents/Home}
M=$TOOLS/mini
JAR=$M/android.jar
AAPT2=$M/aapt2
[ -x "$AAPT2" ] || { (cd "$M" && unzip -q -o aapt2.jar aapt2 && chmod +x aapt2); }
SIGN=$M/sign
if [ ! -f "$SIGN/Sign.class" ]; then
  mkdir -p "$SIGN" && "$JAVA_HOME/bin/javac" -nowarn -cp "$M/apksig.jar" -d "$SIGN" tools/Sign.java
fi
ZXING=$TOOLS/zxing-core-3.5.3.jar
OUT=$PWD/out
VERSION_CODE=${VERSION_CODE:-$(date +%s | cut -c1-9)}
[ -f "$ZXING" ] || curl -fsSL -o "$ZXING" https://repo1.maven.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar

python3 ../src/build.py >/dev/null
KEY=$TOOLS/debug.keystore
[ -f "$KEY" ] || "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEY" -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1

# build <module> <package> <minSdk> <targetSdk> <extra classpath jar or ""> <apk name> <asset dirs...>
build() {
  local mod=$1 pkg=$2 min=$3 target=$4 lib=$5 apk=$6; shift 6
  local w=$OUT/work-$mod
  rm -rf "$w"; mkdir -p "$w/gen" "$w/classes" "$w/dex"
  sed "s|<manifest |<manifest package=\"$pkg\" |" "$mod/src/main/AndroidManifest.xml" > "$w/AndroidManifest.xml"
  "$AAPT2" compile --dir "$mod/src/main/res" -o "$w/res.zip"
  local assets=()
  for a in "$@"; do [ -d "$a" ] && assets+=(-A "$a"); done
  "$AAPT2" link -I "$JAR" --manifest "$w/AndroidManifest.xml" "$w/res.zip" --java "$w/gen" \
    --min-sdk-version "$min" --target-sdk-version "$target" --version-code "$VERSION_CODE" --version-name "3.local" \
    "${assets[@]}" -o "$w/unsigned.apk"
  local cp="$JAR"; [ -n "$lib" ] && cp="$cp:$lib"
  find "$mod/src/main/java" "$w/gen" -name '*.java' > "$w/sources.txt"
  # java.* from the JDK's Java 8 API (it has the lambda bootstrap android.jar lacks), android.* from android.jar
  "$JAVA_HOME/bin/javac" -nowarn --release 8 -cp "$cp" -d "$w/classes" @"$w/sources.txt" \
    || { echo "!! javac failed for $mod"; exit 1; }
  "$JAVA_HOME/bin/java" -cp "$M/r8.jar" com.android.tools.r8.D8 --release --min-api "$min" --lib "$JAR" \
    --output "$w/dex" $(find "$w/classes" -name '*.class') ${lib:+"$lib"}
  (cd "$w/dex" && zip -q -j "$w/unsigned.apk" classes*.dex)
  "$JAVA_HOME/bin/java" -cp "$SIGN:$M/apksig.jar" Sign "$KEY" "$w/unsigned.apk" "$OUT/$apk" "$min"
  echo "built $OUT/$apk"
}

# the launcher's generated assets, as its build.gradle copies them
G=$OUT/launcher-assets; rm -rf "$G"; mkdir -p "$G/wallpapers" "$G/web" "$G/fonts"
cp ../assets/backgrounds/*.jpg "$G/wallpapers/"
cp ../dist/tv.html "$G/web/"
cp ../assets/fonts/*.woff2 "$G/fonts/" 2>/dev/null || true

mkdir -p "$OUT"
build app sa.urtv.screensaver 21 34 "" urtv-screensaver.apk ../dist ../assets/shared launcher/src/main/assets ../assets
build launcher sa.urtv.home 26 30 "$ZXING" urtv-home.apk launcher/src/main/assets "$G" ../assets/shared

#!/bin/bash
set -e
export JAVA_HOME=/workspace/tools/jdk17
export PATH="$JAVA_HOME/bin:$PATH"
T=/workspace/tools
AAPT2=$T/bt/android-14/aapt2
D8=$T/bt/android-14/d8
ZIPALIGN=$T/bt/android-14/zipalign
APKSIGNER=$T/bt/android-14/apksigner
ANDROID_JAR=$T/platform/android-34/android.jar
KOTLINC=$T/kotlinc/bin/kotlinc
STDLIB=$T/kotlinc/lib/kotlin-stdlib.jar
JSON=$T/dl/json.jar
OKHTTP=$T/dl/okhttp-4.12.0.jar
OKIO=$T/dl/okio-jvm-3.6.0.jar
cd /workspace/lightchat
rm -rf build/classes build/gen build/app.jar build/res.zip build/classes.dex build/base.apk build/aligned.apk build/lightchat.apk
mkdir -p build/classes

echo "== aapt2 compile =="
$AAPT2 compile --dir res -o build/res.zip

echo "== aapt2 link =="
$AAPT2 link -o build/base.apk -I $ANDROID_JAR \
  --manifest AndroidManifest.xml build/res.zip \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 10 --version-name 0.4.0 \
  --java build/gen

echo "== kotlinc =="
$KOTLINC -jvm-target 17 \
  -cp "$ANDROID_JAR:$JSON:$OKHTTP:$OKIO" \
  -d build/classes \
  src/bd/desertfox/lightchat/*.kt build/gen/bd/desertfox/lightchat/R.java > build/kotlinc.log 2>&1
if grep -q "error:" build/kotlinc.log; then
  echo "KOTLINC ERRORS:"; cat build/kotlinc.log; exit 1
fi
cat build/kotlinc.log

echo "== jar =="
jar cf build/app.jar -C build/classes .

echo "== d8 =="
$D8 --min-api 24 --lib $ANDROID_JAR --output build build/app.jar $STDLIB $OKHTTP $OKIO

echo "== package =="
cd build
python3 - <<'PY2'
import zipfile
z = zipfile.ZipFile('base.apk', 'a')
z.write('classes.dex', 'classes.dex')
z.close()
print('dex added')
PY2
$ZIPALIGN -f 4 base.apk aligned.apk
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -alias lightchat -keyalg RSA \
    -keysize 2048 -validity 10000 -storepass lightchat -keypass lightchat \
    -dname "CN=LightChat, OU=LightChat, O=DesertFox, L=Dhaka, C=BD"
fi
$APKSIGNER sign --ks debug.keystore --ks-pass pass:lightchat --key-pass pass:lightchat \
  --out lightchat.apk aligned.apk
$APKSIGNER verify lightchat.apk
ls -la lightchat.apk
md5sum lightchat.apk

#!/usr/bin/env bash
# Build the Cipher Android wrapper APK without Gradle.
#
# Requirements:
#   - JDK 11+ (javac, keytool)
#   - Android SDK with platforms;android-34 and build-tools;35.0.1+
#     (d8 from build-tools 34 crashes on classfiles emitted by JDK 21)
#   - A signing keystore (see KEYSTORE below; NOT committed to the repo)
#
# Usage:
#   SDK=/path/to/android-sdk KEYSTORE=/path/to/cipher.keystore \
#   KS_PASS=yourpass ./build.sh
set -euo pipefail
cd "$(dirname "$0")"

SDK="${SDK:-/tmp/android-sdk}"
BT="$SDK/build-tools/35.0.1"
AJ="$SDK/platforms/android-34/android.jar"
KEYSTORE="${KEYSTORE:-/tmp/cipher-apk/cipher.keystore}"
KS_PASS="${KS_PASS:-cipher123}"

rm -rf gen classes compiled.flata base.apk aligned.apk Cipher.apk classes.dex
mkdir -p gen classes

"$BT/aapt2" compile --dir res -o compiled.flata
"$BT/aapt2" link -o base.apk --manifest AndroidManifest.xml -I "$AJ" --java gen compiled.flata
# -source/-target 8: javac 21's nestmate classfiles crash older d8, and
# android.jar lacks LambdaMetafactory so lambdas can't compile anyway.
javac -source 8 -target 8 -Xlint:-options,-deprecation -bootclasspath "$AJ" \
  -d classes gen/ae/cipher/chat/R.java src/ae/cipher/chat/*.java
"$BT/d8" --lib "$AJ" --release --output . $(find classes -name '*.class')
zip -q -j base.apk classes.dex
"$BT/zipalign" -f 4 base.apk aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out Cipher.apk aligned.apk
"$BT/apksigner" verify Cipher.apk
echo "Built: $(pwd)/Cipher.apk"

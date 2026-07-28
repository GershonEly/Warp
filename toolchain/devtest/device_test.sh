#!/system/bin/sh
#
# Warp toolchain smoke test — runs ON THE PHONE, as the app's own user.
#
# Invoked with:
#   adb shell run-as dev.ely.warp sh /data/local/tmp/device_test.sh
#
# Running under `run-as` matters: it puts us in the app's UID and its real
# data directory, which is exactly where Warp will run the toolchain. A test
# in /data/local/tmp would run as the shell user and prove much less.
#
# It deliberately mirrors the command lines in BuildEngine.kt, so a failure
# here is a failure there.

PKG=dev.ely.warp
FILES=/data/data/$PKG/files
TC=$FILES/toolchain
WORK=$FILES/devtest
SRC=/data/local/tmp/warptest
BUNDLE=/data/local/tmp/warp-toolchain.zip

pass=0
fail=0

say()  { echo ""; echo "=== $* ==="; }
ok()   { echo "  PASS  $*"; pass=$((pass+1)); }
bad()  { echo "  FAIL  $*"; fail=$((fail+1)); }

# Run a command, report pass/fail, keep its output for diagnosis.
try() {
    label=$1; shift
    t0=$(date +%s)
    out=$("$@" 2>&1)
    rc=$?
    t1=$(date +%s)
    if [ $rc -eq 0 ]; then
        ok "$label  (${t1}s-${t0}s = $((t1-t0))s)"
    else
        bad "$label  (exit $rc)"
        echo "  ---- output ----"
        echo "$out" | head -30
        echo "  ----------------"
    fi
    return $rc
}

say "0. Environment"
echo "  uid      : $(id -u)"
echo "  files    : $FILES"
echo "  abi      : $(getprop ro.product.cpu.abi)"
echo "  android  : $(getprop ro.build.version.release)"
echo "  free ram : $(awk '/MemAvailable/ {print int($2/1024)" MB"}' /proc/meminfo)"

# ── unpack the toolchain ────────────────────────────────────────────────
say "1. Unpacking the toolchain"
if [ ! -f "$BUNDLE" ]; then
    bad "bundle not found at $BUNDLE"
    exit 1
fi
rm -rf "$TC"
mkdir -p "$TC"
if unzip -o -q "$BUNDLE" -d "$TC"; then
    ok "unzipped $(du -sm "$TC" 2>/dev/null | cut -f1) MB"
else
    bad "unzip failed"
    exit 1
fi

# The zip carries permissions, but not every unzip honours them.
chmod 755 "$TC"/bin/* 2>/dev/null
chmod 755 "$TC"/jvm/bin/* 2>/dev/null
ok "set the executable bit"

# ── the environment the toolchain needs ─────────────────────────────────
# LD_LIBRARY_PATH is the important one: the JDK is a Termux build with
# /data/data/com.termux/... baked into some RUNPATHs, and the linker searches
# LD_LIBRARY_PATH first.
export LD_LIBRARY_PATH=$TC/jvm/lib:$TC/jvm/lib/server:$TC/lib
export JAVA_HOME=$TC/jvm
export TMPDIR=$FILES/build-tmp
export HOME=$FILES
export PATH=$TC/bin:$TC/jvm/bin:/system/bin
mkdir -p "$TMPDIR"

# ── the native tools ────────────────────────────────────────────────────
say "2. Native tools (static binaries)"
try "aapt2 version" "$TC/bin/aapt2" version
# zipalign exits non-zero when given no arguments, so prove it loads and runs
# rather than checking its exit code. Match anywhere in the output: the first
# line is its banner ("Zip alignment utility"), not the usage line.
# -E because Android's toybox grep does not accept \| alternation in BRE.
if "$TC/bin/zipalign" 2>&1 | grep -qiE "zip alignment|usage"; then
    ok "zipalign runs"
else
    bad "zipalign did not run"
fi

say "3. THE BIG ONE — does the JVM start?"
if try "java -version" "$TC/jvm/bin/java" -version; then
    echo ""
    echo "  >>> The Termux JDK runs from Warp's own directory. <<<"
else
    echo ""
    echo "  >>> JVM did not start. Everything below will fail. <<<"
    echo "  Check the output above for a missing .so file."
    exit 1
fi

# ── build the test project ──────────────────────────────────────────────
say "4. Building the test app"
rm -rf "$WORK"
mkdir -p "$WORK/gen" "$WORK/classes" "$WORK/dex"

if [ ! -d "$SRC" ]; then
    bad "test project not found at $SRC"
    exit 1
fi

ANDROID_JAR=$TC/platform/android.jar
KOTLIN_CP=$(ls "$TC"/kotlinc/lib/*.jar | tr '\n' ':')
STDLIB=$TC/kotlinc/lib/kotlin-stdlib.jar

try "aapt2 compile" \
    "$TC/bin/aapt2" compile --dir "$SRC/res" -o "$WORK/res.zip"

try "aapt2 link" \
    "$TC/bin/aapt2" link \
        -I "$ANDROID_JAR" \
        --manifest "$SRC/AndroidManifest.xml" \
        --java "$WORK/gen" \
        --min-sdk-version 28 \
        --target-sdk-version 28 \
        --auto-add-overlay \
        -o "$WORK/base.apk" \
        "$WORK/res.zip"

R_JAVA=$(find "$WORK/gen" -name '*.java' 2>/dev/null)
if [ -n "$R_JAVA" ]; then
    try "javac (R class)" \
        "$TC/jvm/bin/javac" -nowarn -cp "$ANDROID_JAR" -d "$WORK/classes" $R_JAVA
else
    bad "aapt2 generated no R.java"
fi

KT=$(find "$SRC/src" -name '*.kt')
say "5. Kotlin compiler — the slow one"
echo "  compiling: $KT"
try "kotlinc" \
    "$TC/jvm/bin/java" -Xmx1024m -XX:+UseSerialGC \
        "-Djava.io.tmpdir=$TMPDIR" \
        -cp "$KOTLIN_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
        -no-reflect -nowarn -jvm-target 17 \
        -classpath "$ANDROID_JAR:$WORK/classes:$STDLIB" \
        -d "$WORK/classes" \
        $KT

CLASSES=$(find "$WORK/classes" -name '*.class' 2>/dev/null | wc -l)
echo "  .class files produced: $CLASSES"

say "6. d8 — classes to dex"
CLASS_FILES=$(find "$WORK/classes" -name '*.class' 2>/dev/null)
if [ -n "$CLASS_FILES" ]; then
    try "d8" \
        "$TC/jvm/bin/java" -Xmx1024m -XX:+UseSerialGC \
            "-Djava.io.tmpdir=$TMPDIR" \
            -cp "$TC/d8/r8.jar" com.android.tools.r8.D8 \
            --lib "$ANDROID_JAR" \
            --min-api 28 \
            --output "$WORK/dex" \
            "$STDLIB" $CLASS_FILES
    ls -la "$WORK/dex" 2>/dev/null
else
    bad "no .class files to dex"
fi

say "RESULT"
echo "  passed: $pass"
echo "  failed: $fail"
echo ""
if [ $fail -eq 0 ]; then
    echo "  ALL STAGES WORKED — the on-device toolchain is real."
else
    echo "  $fail stage(s) failed. See the output above."
fi

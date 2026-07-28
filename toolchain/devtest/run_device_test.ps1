<#
  Pushes the toolchain and the test project to the phone, then runs the smoke
  test as the app's own user.

  Usage:
      .\toolchain\devtest\run_device_test.ps1
      .\toolchain\devtest\run_device_test.ps1 -SkipPush     (toolchain already there)

  Everything lands in /data/local/tmp first, because adb cannot write into an
  app's private directory. The script on the phone then works under `run-as`,
  which gives us the app's UID and its real data folder.

  NOTE: ASCII only. Windows PowerShell 5.1 reads .ps1 as ANSI, so non-ASCII
  characters here become mojibake and break the parser.
#>

param(
    [string]$Adb = "C:\Users\ely\Android\Sdk\platform-tools\adb.exe",
    [string]$Package = "dev.ely.warp",
    [switch]$SkipInstall,
    [switch]$SkipPush
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$here = $PSScriptRoot

function Section($t) { Write-Host ""; Write-Host "=== $t ===" -ForegroundColor Cyan }

# ---- device ------------------------------------------------------------
Section "Device"
$devices = & $Adb devices | Select-String -Pattern "\tdevice$"
if (-not $devices) {
    Write-Host "No device. Connect the phone, enable USB debugging, accept the prompt." -ForegroundColor Red
    exit 1
}
& $Adb shell getprop ro.product.model
& $Adb shell getprop ro.build.version.release

# ---- the app must be installed and debuggable, or run-as will not work --
if (-not $SkipInstall) {
    Section "Installing Warp"
    $apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path $apk)) {
        Write-Host "APK not found. Run: .\gradlew.bat assembleDebug" -ForegroundColor Red
        exit 1
    }
    # --bypass-low-target-sdk-block is required because Warp targets API 28
    # on purpose; modern Android refuses such installs without it.
    & $Adb install -r --bypass-low-target-sdk-block $apk
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Install failed. If it mentions signatures, uninstall Warp first." -ForegroundColor Red
        exit 1
    }
}

# ---- push the pieces ---------------------------------------------------
if (-not $SkipPush) {
    Section "Pushing the toolchain (about 171 MB, takes a minute)"
    $bundle = Get-ChildItem (Join-Path $root "toolchain\build") -Filter "warp-toolchain-arm64-*.zip" -ErrorAction SilentlyContinue |
              Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $bundle) {
        Write-Host "No bundle found. Run the builder first:" -ForegroundColor Red
        Write-Host "  py toolchain\build_toolchain.py --sdk C:\Users\ely\Android\Sdk" -ForegroundColor Red
        exit 1
    }
    Write-Host ("  {0} ({1:N1} MB)" -f $bundle.Name, ($bundle.Length / 1MB))
    & $Adb push $bundle.FullName /data/local/tmp/warp-toolchain.zip
}

Section "Pushing the test project and script"
& $Adb shell rm -rf /data/local/tmp/warptest
& $Adb push (Join-Path $here "testproject") /data/local/tmp/warptest
& $Adb push (Join-Path $here "device_test.sh") /data/local/tmp/device_test.sh

# The app runs as a different user than adb, so these must be readable by it.
& $Adb shell chmod 644 /data/local/tmp/warp-toolchain.zip
& $Adb shell chmod 644 /data/local/tmp/device_test.sh
& $Adb shell chmod -R 755 /data/local/tmp/warptest

# ---- run ---------------------------------------------------------------
Section "Running the smoke test on the phone"
Write-Host "(kotlinc may take a while on first run)" -ForegroundColor DarkGray
Write-Host ""
& $Adb shell run-as $Package sh /data/local/tmp/device_test.sh

Section "Done"

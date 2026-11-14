param(
    [ValidateSet("release", "debug")]
    [string]$Variant = "release",
    [string]$Device = "",
    [switch]$NoClean,
    [switch]$NoBuild,
    [switch]$KeepData
)

# Native adb writes progress/errors to stderr; with Stop PowerShell turns that
# into a terminating error before the exit code can be checked.
$ErrorActionPreference = "Continue"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $root

$jdk = $env:JAVA_HOME
if (-not $jdk -or -not (Test-Path (Join-Path $jdk "bin\java.exe"))) {
    $jdk = Join-Path $env:USERPROFILE "dev\jdk17"
}
$sdk = $env:ANDROID_HOME
if (-not $sdk -or -not (Test-Path (Join-Path $sdk "platform-tools\adb.exe"))) {
    $sdk = Join-Path $env:USERPROFILE "Android\Sdk"
}
$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:Path = "$jdk\bin;$sdk\platform-tools;$env:Path"

$adb = Join-Path $sdk "platform-tools\adb.exe"
$package = "com.brent.nova"

function Resolve-Device {
    param([string]$Requested)
    if ($Requested) { return $Requested }
    $lines = & $adb devices 2>&1
    $serial = $lines |
        Select-String -Pattern "^\S+\s+device$" |
        ForEach-Object { ($_.Line -split "\s+")[0] } |
        Select-Object -First 1
    if (-not $serial) {
        throw "ADB: no authorized device. Connect the phone and allow USB debugging."
    }
    return $serial
}

$serial = Resolve-Device $Device
Write-Host "== Device: $serial =="

if (-not $NoBuild) {
    $gradle = Join-Path $root "gradlew.bat"
    $task = if ($Variant -eq "release") { "assembleGithubRelease" } else { "assembleGithubDebug" }
    $gradleArgs = @()
    if (-not $NoClean) {
        $gradleArgs += "clean"
    }
    $gradleArgs += $task
    if ($Variant -eq "release") {
        $env:NOVA_ALLOW_UNPUBLISHABLE_BUILD = "1"
        $env:NOVA_ALLOW_UNSIGNED_RELEASE = "1"
        $gradleArgs += "-PnovaCfProxySlot=true"
    }
    $gradleArgs += "--no-daemon"
    Write-Host "== Gradle: $($gradleArgs -join ' ') =="
    & $gradle @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with code $LASTEXITCODE" }
}

$apkDir = Join-Path $root "app\build\outputs\apk\github\$Variant"
$apk = Get-ChildItem $apkDir -Filter "*-arm64-v8a.apk" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $apk) { throw "APK not found in $apkDir (build $Variant first)." }
Write-Host "== APK: $($apk.FullName) =="

& $adb -s $serial shell am force-stop $package 2>&1 | Out-Null
& $adb -s $serial install -r $apk.FullName
if ($LASTEXITCODE -ne 0) { throw "adb install failed with code $LASTEXITCODE" }

if (-not $KeepData) {
    & $adb -s $serial shell pm clear $package 2>&1 | Out-Null
    Write-Host "== App data cleared: $package (split_mode back to default) =="
}

& $adb -s $serial shell monkey -p $package -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
Write-Host "== Installed and launched: $($apk.Name) =="

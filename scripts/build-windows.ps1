# Builds through a temporary ASCII drive so Glasmio's CMake works when the checkout path has non-ASCII characters.
param(
    [string[]]$Tasks = @(':app:assembleDeviceTest'),
    [string]$Sdk = 'C:\Android\Sdk',
    [string]$Device
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

if (-not (Test-Path (Join-Path $root '.reference\Glasmio\settings.gradle.kts'))) {
    python (Join-Path $root 'scripts\prepare-ui.py') --fonts
    if ($LASTEXITCODE) { exit $LASTEXITCODE }
}

$used = (Get-PSDrive -PSProvider FileSystem).Name
$drive = [char[]]'PQRSTUVWXYZ' | Where-Object { $used -notcontains "$_" } | Select-Object -First 1
if (-not $drive) { throw 'No free drive letter for subst' }
subst "${drive}:" $root
try {
    $env:ANDROID_HOME = $Sdk
    $env:ANDROID_SDK_ROOT = $Sdk
    Push-Location "${drive}:\"
    try {
        # The daemon may still hold the non-ASCII project path from an earlier run.
        & .\gradlew.bat --stop | Out-Null
        & .\gradlew.bat @Tasks '-Pandroid.overridePathCheck=true'
        if ($LASTEXITCODE) { exit $LASTEXITCODE }
    } finally {
        & .\gradlew.bat --stop | Out-Null
        Pop-Location
    }
} finally {
    subst "${drive}:" /D
}

if ($Device) {
    $apk = Join-Path $root 'app\build\outputs\apk\deviceTest\app-deviceTest.apk'
    if ($Device -match ':') { adb connect $Device | Out-Null }
    adb -s $Device install -r $apk
    if ($LASTEXITCODE) { exit $LASTEXITCODE }
}

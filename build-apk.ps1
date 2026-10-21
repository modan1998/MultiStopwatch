$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$GradleWrapper = Join-Path $ProjectRoot "gradlew.bat"

if (-not (Test-Path $GradleWrapper)) {
    throw "Gradle Wrapper not found. Run this script from a complete project checkout."
}

# Prefer standard Android SDK environment variables. Fall back to the usual
# Windows SDK location when it exists, without storing a machine-specific path.
if (-not $env:ANDROID_SDK_ROOT -and -not $env:ANDROID_HOME) {
    $DefaultSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $DefaultSdk) {
        $env:ANDROID_SDK_ROOT = $DefaultSdk
        $env:ANDROID_HOME = $DefaultSdk
    }
}

# A stale JAVA_HOME can prevent Gradle from using a valid Java installation on PATH.
if ($env:JAVA_HOME -and -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    Write-Warning "JAVA_HOME points to a missing Java installation; using Java from PATH instead."
    Remove-Item Env:JAVA_HOME
}

Push-Location $ProjectRoot
try {
    & $GradleWrapper :app:assembleDebug
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "APK output: app/build/outputs/apk/debug/app-debug.apk"

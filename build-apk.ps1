# Build Lightning Bridge from Windows PowerShell. Gradle is cached between projects.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$version = '8.9'
$cache = Join-Path $env:LOCALAPPDATA 'LightningBridgeBuildTools'
$gradleExe = Join-Path $cache "gradle-$version\bin\gradle.bat"

if (-not (Test-Path $gradleExe)) {
    New-Item -ItemType Directory -Path $cache -Force | Out-Null
    $archive = Join-Path $cache "gradle-$version-bin.zip"
    if (Test-Path $archive) { Remove-Item $archive -Force }
    Write-Host "Downloading Gradle $version (one-time cache)..." -ForegroundColor Cyan
    Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-$version-bin.zip" -OutFile $archive
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $test = [System.IO.Compression.ZipFile]::OpenRead($archive)
        $test.Dispose()
        Expand-Archive -LiteralPath $archive -DestinationPath $cache -Force
    } catch {
        Remove-Item $archive -Force -ErrorAction SilentlyContinue
        throw "Gradle ZIP was incomplete/corrupt; please retry. $($_.Exception.Message)"
    }
    if (-not (Test-Path $gradleExe)) { throw 'Gradle extraction failed.' }
}

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
       elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
       else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path $sdk)) {
    throw "Android SDK not found: $sdk. Install SDK Platform 35 in Android Studio or set ANDROID_HOME."
}
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
Write-Host "Using Android SDK: $sdk"
Write-Host "Building Lightning Bridge..." -ForegroundColor Cyan
& $gradleExe --project-dir $root ':app:assembleDebug' --no-daemon
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit $LASTEXITCODE)" }
Write-Host "APK: $(Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk')" -ForegroundColor Green

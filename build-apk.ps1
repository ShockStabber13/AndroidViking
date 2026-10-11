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

# Prefer an explicit SDK environment variable, then try common Windows SDK locations.
# PowerShell environment variables use $env:ANDROID_HOME (not $ANDROID_HOME).
$sdkCandidates = @(
    @{ Path = $env:ANDROID_HOME; Label = 'ANDROID_HOME' }
    @{ Path = $env:ANDROID_SDK_ROOT; Label = 'ANDROID_SDK_ROOT' }
    @{ Path = 'C:\Android\SDK'; Label = 'C:\Android\SDK' }
    @{ Path = (Join-Path $env:LOCALAPPDATA 'Android\Sdk'); Label = 'Android Studio default' }
)
$sdk = $null
$missingPlatform = @()
foreach ($candidate in $sdkCandidates) {
    $candidatePath = $candidate.Path
    if ([string]::IsNullOrWhiteSpace($candidatePath)) { continue }
    if (-not (Test-Path -LiteralPath $candidatePath -PathType Container)) {
        if ($candidate.Label -in @('ANDROID_HOME', 'ANDROID_SDK_ROOT')) {
            Write-Warning "$($candidate.Label) points to a missing folder: $candidatePath. Checking other locations..."
        }
        continue
    }
    $platformJar = Join-Path $candidatePath 'platforms\android-35\android.jar'
    if (-not (Test-Path -LiteralPath $platformJar -PathType Leaf)) {
        $missingPlatform += "$($candidate.Label): $candidatePath"
        continue
    }
    $sdk = (Resolve-Path -LiteralPath $candidatePath).Path
    break
}
if (-not $sdk) {
    if ($missingPlatform.Count -gt 0) {
        throw "An Android SDK was found, but Android SDK Platform 35 is missing. Install Platform 35 using Android Studio > SDK Manager. SDKs checked: $($missingPlatform -join '; ')"
    }
    throw 'Android SDK not found. Set $env:ANDROID_HOME = "C:\Android\SDK" in PowerShell, or install the SDK in Android Studio.'
}
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
Write-Host "Using Android SDK: $sdk"
Write-Host "Building Lightning Bridge..." -ForegroundColor Cyan
& $gradleExe --project-dir $root ':app:assembleDebug' --no-daemon
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit $LASTEXITCODE)" }
Write-Host "APK: $(Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk')" -ForegroundColor Green

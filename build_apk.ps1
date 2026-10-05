# ============================================================
#  TVBoxOS-Mobile APK Builder (PowerShell)
#  Usage:
#    .\build_apk.ps1            -> prompt to choose debug / release
#    .\build_apk.ps1 debug      -> build debug APK
#    .\build_apk.ps1 release    -> build signed release APK
#  The produced APK is copied to the project root directory.
# ============================================================

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $ScriptDir

$env:JAVA_HOME    = 'C:\Java\jdk-17'
$env:ANDROID_HOME = 'C:\Android\Sdk'

Write-Host '============================================'
Write-Host '   TVBoxOS-Mobile APK Builder'
Write-Host '============================================'

# --- prerequisite checks ---
if (-not (Test-Path 'gradlew.bat')) {
    Write-Host '[ERROR] gradlew.bat not found. Run this script from the project root.'
    Read-Host 'Press Enter to exit'; exit 1
}
if (-not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    Write-Host "[ERROR] JDK 17 not found at $env:JAVA_HOME"
    Read-Host 'Press Enter to exit'; exit 1
}

# --- read version name from app/build.gradle ---
$ver = 'unknown'
$bg = Get-Content 'app\build.gradle' -Raw
if ($bg -match "versionName\s+'([^']+)'") { $ver = $Matches[1] }
Write-Host "  Project version: $ver"

# --- pick build type ---
$buildType = $args[0]
if (($buildType -ne 'debug') -and ($buildType -ne 'release')) {
    Write-Host ''
    Write-Host '  Select build type:'
    Write-Host '    [1] Debug   - for daily testing'
    Write-Host '    [2] Release - signed, for install and share'
    $choice = Read-Host '  Enter 1 or 2'
    if ($choice -eq '2') { $buildType = 'release' } else { $buildType = 'debug' }
}

# --- stop any leftover gradle daemon (clears stale kotlin config cache) ---
& .\gradlew.bat --stop 2>$null

Write-Host ''
Write-Host '============================================'
Write-Host "  Building $buildType APK, please wait..."
Write-Host '  First build may take about 25 minutes.'
Write-Host '============================================'
Write-Host ''

if ($buildType -eq 'release') {
    & .\gradlew.bat assembleRelease --console=plain
} else {
    & .\gradlew.bat assembleDebug --console=plain
}

if ($LASTEXITCODE -ne 0) {
    Write-Host ''
    Write-Host '============================================'
    Write-Host '  BUILD FAILED - check errors above'
    Write-Host '============================================'
    & .\gradlew.bat --stop 2>$null
    Read-Host 'Press Enter to exit'; exit 1
}

# --- locate produced apk ---
$outDir = Join-Path 'app\build\outputs\apk' $buildType
$apk = Get-ChildItem -Path "$outDir\*.apk" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) {
    Write-Host "  [WARN] APK not found in $outDir"
    & .\gradlew.bat --stop 2>$null
    Read-Host 'Press Enter to exit'; exit 1
}

# --- copy apk into project root with versioned name ---
$destName = "TVBox-Mobile-v$ver-$buildType.apk"
$destPath = Join-Path $ScriptDir $destName
Copy-Item -Path $apk.FullName -Destination $destPath -Force

Write-Host ''
Write-Host '============================================'
Write-Host '  BUILD SUCCESS'
Write-Host '============================================'
Write-Host "  APK       : $($apk.FullName)"
Write-Host "  Size      : $($apk.Length) bytes"
Write-Host "  Copied to : $destPath"
$hash = (Get-FileHash -Path $destPath -Algorithm SHA256).Hash
Write-Host "  SHA256    : $hash"
Write-Host ''
Write-Host "  Install: adb install -r `"$destPath`""

# --- release: verify signature ---
if ($buildType -eq 'release') {
    $signer = Get-ChildItem -Path "$env:ANDROID_HOME\build-tools\*\apksigner.bat" | Select-Object -Last 1
    if ($signer) {
        & $signer.FullName verify --print-certs "$destPath"
        if ($LASTEXITCODE -eq 0) {
            Write-Host '  [OK] apksigner verify passed'
        } else {
            Write-Host '  [WARN] apksigner verify failed'
        }
    } else {
        Write-Host '  [WARN] apksigner not found, skipped signature check'
    }
}

# --- clean up gradle daemon ---
& .\gradlew.bat --stop 2>$null

Write-Host ''
Read-Host 'Press Enter to exit'
exit 0

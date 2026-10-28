# ============================================================
#  TVBoxOS-Mobile APK Builder (PowerShell)
#  Usage:
#    .\build_apk.ps1            -> prompt to choose debug / release
#    .\build_apk.ps1 debug      -> build debug APK
#    .\build_apk.ps1 release    -> build signed release APK
#
#  One APK is produced per CPU architecture flavor
#  (arm64v8 -> arm64-v8a, armv7a -> armeabi-v7a).
#  Every produced APK is copied to the project root directory.
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
Write-Host "  Building $buildType APK(s), please wait..."
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

# --- locate produced apks: <outputs>\apk\<flavor>\<buildType>\*.apk ---
$outRoot = Join-Path 'app\build\outputs\apk' ''
$apks = @(Get-ChildItem -Path $outRoot -Recurse -Filter *.apk -ErrorAction SilentlyContinue |
          Where-Object { $_.Directory.Name -eq $buildType } |
          Sort-Object FullName)

if ($apks.Count -eq 0) {
    Write-Host "  [WARN] no APK found under $outRoot (looked for $buildType folders)"
    & .\gradlew.bat --stop 2>$null
    Read-Host 'Press Enter to exit'; exit 1
}

# --- copy each apk into project root with versioned name ---
$destPaths = @()
$apkInfo = @()
foreach ($apk in $apks) {
    $destName = $apk.BaseName + "-$buildType.apk"
    $destPath = Join-Path $ScriptDir $destName
    Copy-Item -Path $apk.FullName -Destination $destPath -Force
    $destPaths += $destPath
    $apkInfo += [pscustomobject]@{
        Source = $apk.FullName
        Dest   = $destPath
        Size   = $apk.Length
    }
}

Write-Host ''
Write-Host '============================================'
Write-Host '  BUILD SUCCESS'
Write-Host "  $($apkInfo.Count) APK(s) produced"
Write-Host '============================================'
foreach ($info in $apkInfo) {
    Write-Host ''
    Write-Host "  APK       : $($info.Source)"
    Write-Host "  Size      : $($info.Size) bytes"
    Write-Host "  Copied to : $($info.Dest)"
    $hash = (Get-FileHash -Path $info.Dest -Algorithm SHA256).Hash
    Write-Host "  SHA256    : $hash"
}
Write-Host ''
foreach ($dp in $destPaths) {
    Write-Host "  Install: adb install -r `"$dp`""
}

# --- release: verify signature of every apk ---
if ($buildType -eq 'release') {
    $signer = Get-ChildItem -Path "$env:ANDROID_HOME\build-tools\*\apksigner.bat" | Select-Object -Last 1
    if ($signer) {
        foreach ($dp in $destPaths) {
            & $signer.FullName verify --print-certs "$dp"
            if ($LASTEXITCODE -eq 0) {
                Write-Host "  [OK] apksigner verify passed: $(Split-Path -Leaf $dp)"
            } else {
                Write-Host "  [WARN] apksigner verify failed: $(Split-Path -Leaf $dp)"
            }
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

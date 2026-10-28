# ============================================================
#  TVBoxOS-Mobile APK Builder (PowerShell)
#  Double click / no argument   -> build signed RELEASE apk(s)
#  .\build_apk.ps1 debug        -> build debug apk(s)
#
#  One APK is produced per CPU architecture flavor
#  (flavor arm64v8 gives arm64-v8a, flavor armv7a gives armeabi-v7a).
#  Every produced APK is copied to the project root directory.
#  No prompt, no keyboard input required.
# ============================================================

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $ScriptDir

$env:JAVA_HOME    = 'C:\Java\jdk-17'
$env:ANDROID_HOME = 'C:\Android\Sdk'

# launched by double click from Explorer -> keep the console window open at the end
function Test-LaunchedFromExplorer {
    try {
        $me = Get-CimInstance Win32_Process -Filter "ProcessId=$PID" -ErrorAction Stop
        if ($me -and $me.ParentProcessId) {
            $parent = Get-Process -Id $me.ParentProcessId -ErrorAction Stop
            return ($parent.ProcessName -eq 'explorer')
        }
    } catch { }
    return $false
}

$script:IsExplorerLaunch = Test-LaunchedFromExplorer

function Exit-Script([int]$code) {
    if ($script:IsExplorerLaunch) {
        Write-Host ''
        Write-Host '  this window closes in 45 seconds - close it manually any time'
        Start-Sleep -Seconds 45
    }
    exit $code
}

Write-Host '============================================'
Write-Host '   TVBoxOS-Mobile APK Builder'
Write-Host '============================================'

# --- prerequisite checks ---
if (-not (Test-Path 'gradlew.bat')) {
    Write-Host '[ERROR] gradlew.bat not found. Run this script from the project root.'
    Exit-Script 1
}
if (-not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    Write-Host "[ERROR] JDK 17 not found at $env:JAVA_HOME"
    Exit-Script 1
}

# --- read version name from app/build.gradle ---
$ver = 'unknown'
$bg = Get-Content 'app\build.gradle' -Raw
if ($bg -match "versionName\s+'([^']+)'") { $ver = $Matches[1] }
Write-Host "  Project version: $ver"

# --- build type: default release, argument can override, never prompts ---
$buildType = 'release'
if ($args.Count -gt 0) {
    $arg = [string]$args[0]
    if ($arg -eq 'debug') { $buildType = 'debug' }
    elseif ($arg -ne 'release') { Write-Host "  [WARN] unknown build type '$arg', using release" }
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
    Exit-Script 1
}

# --- locate produced apks: app\build\outputs\apk\<flavor>\<buildType>\*.apk ---
$outRoot = Join-Path 'app\build\outputs\apk' ''
# apk lives in apk\<flavor>\<buildType>\ (flavors) or apk\<buildType>\ (no flavor),
# so match the folder itself or its parent
$apks = @(Get-ChildItem -Path $outRoot -Recurse -Filter *.apk -ErrorAction SilentlyContinue |
          Where-Object { $_.Directory.Name -eq $buildType -or $_.Directory.Parent.Name -eq $buildType } |
          Sort-Object FullName)

if ($apks.Count -eq 0) {
    Write-Host ''
    Write-Host "  [WARN] no APK found under $outRoot (looked for $buildType folders)"
    & .\gradlew.bat --stop 2>$null
    Exit-Script 1
}

# --- copy each apk into the project root with a build type suffix ---
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
    $signer = Get-ChildItem -Path "$env:ANDROID_HOME\build-tools\*\apksigner.bat" -ErrorAction SilentlyContinue | Select-Object -Last 1
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

Exit-Script 0

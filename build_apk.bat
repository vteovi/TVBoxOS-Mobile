@echo off
REM ============================================================
REM  TVBoxOS-Mobile APK Builder  -  double click to run
REM  Double click builds the signed RELEASE apk (no prompt, no input needed).
REM  Usage: build_apk.bat debug   (builds a debug apk instead)
REM
REM  One APK is produced per CPU architecture flavor
REM  flavor arm64v8 gives arm64-v8a, flavor armv7a gives armeabi-v7a.
REM  Every produced APK is copied to the project root directory.
REM ============================================================
setlocal enabledelayedexpansion
title TVBoxOS-Mobile APK Builder

set "JAVA_HOME=C:/Java/jdk-17"
set "ANDROID_HOME=C:/Android/Sdk"
cd /d "%~dp0"

echo ============================================
echo    TVBoxOS-Mobile APK Builder
echo ============================================

if not exist "gradlew.bat" (
    echo [ERROR] gradlew.bat not found. Run this from project root.
    goto :prereq_fail
)

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JDK 17 not found at %JAVA_HOME%
    goto :prereq_fail
)

set "APPVER="
for /f "tokens=2" %%V in ('findstr /r /c:"^ *versionName *'" app\build.gradle') do set "APPVER=%%V"
if defined APPVER set "APPVER=%APPVER:'=%"
if not defined APPVER set "APPVER=unknown"
echo   Project version: %APPVER%

REM no interactive prompt: double click builds the signed release build
set "BUILDTYPE=%~1"
if /i "%BUILDTYPE%"=="debug" goto :run_build
if /i "%BUILDTYPE%"=="release" goto :run_build
if not "%BUILDTYPE%"=="" echo   [WARN] unknown build type "%BUILDTYPE%", using release
set "BUILDTYPE=release"

:run_build
REM stop leftover gradle daemons (stale kotlin config cache can break the build)
call gradlew.bat --stop >nul 2>&1
echo.
echo ============================================
echo   Building %BUILDTYPE% APK, please wait...
echo   First build may take about 25 minutes.
echo ============================================
echo.

if /i "%BUILDTYPE%"=="release" goto :build_release

call gradlew.bat assembleDebug --console=plain
if errorlevel 1 goto :build_failed
set "OUTDIR=app\build\outputs\apk\debug"
goto :show_result

:build_release
call gradlew.bat assembleRelease --console=plain
if errorlevel 1 goto :build_failed
set "OUTDIR=app\build\outputs\apk\release"

:show_result
taskkill /f /im java.exe >nul 2>&1
echo.
echo ============================================
echo   BUILD SUCCESS
echo ============================================
REM one APK per ABI flavor, under %OUTDIR%\arm64v8\ and ...\armv7a\
set "APKFOUND="
set "APKCOUNT=0"
for %%D in (arm64v8 armv7a) do (
    for /f "delims=" %%F in ('dir /b /a-d /o-d "%OUTDIR%\%%D\*.apk" 2^>nul') do (
        set "APKFOUND=1"
        set /a APKCOUNT+=1
        set "DESTNAME=%%~nF-%BUILDTYPE%.apk"
        copy /y "%OUTDIR%\%%D\%%F" "%CD%\!DESTNAME!" >nul
        if exist "%CD%\!DESTNAME!" (
            set "HASH="
            for /f "skip=1 delims=" %%H in ('certutil -hashfile "%CD%\!DESTNAME!" SHA256 2^>nul') do if not defined HASH set "HASH=%%H"
            echo.
            echo   APK    : %CD%\!DESTNAME!
            for %%A in ("%CD%\!DESTNAME!") do echo   Size   : %%~zA bytes
            echo   Source : %OUTDIR%\%%D\%%F
            if defined HASH echo   SHA256 : !HASH!
            echo   Install: adb install -r "%CD%\!DESTNAME!"
        ) else (
            echo.
            echo   [WARN] copy failed: %OUTDIR%\%%D\%%F
        )
    )
)
if not defined APKFOUND (
    echo   [WARN] no APK found under %OUTDIR%
    goto :end
)
echo.
echo   ------------------------------------------------------------
echo   !APKCOUNT! APK(s) copied to project root:
echo   %CD%
echo   ------------------------------------------------------------
goto :end

:build_failed
taskkill /f /im java.exe >nul 2>&1
echo.
echo ============================================
echo   BUILD FAILED - check errors above
echo ============================================

:end
echo.
echo   this window closes in 45 seconds - close it manually any time
ping -n 46 127.0.0.1 >nul
endlocal
exit /b 0

:prereq_fail
echo.
echo   this window closes in 45 seconds - close it manually any time
ping -n 46 127.0.0.1 >nul
exit /b 1

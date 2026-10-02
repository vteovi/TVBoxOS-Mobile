@echo off
REM ============================================================
REM  TVBoxOS-Mobile APK Builder  -  double click to run
REM  Usage: build_apk.bat debug   or   build_apk.bat release
REM ============================================================
setlocal
title TVBoxOS-Mobile APK Builder

set "JAVA_HOME=C:\Java\jdk-17"
set "ANDROID_HOME=C:\Android\Sdk"
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

set "BUILDTYPE=%~1"
if /i "%BUILDTYPE%"=="debug" goto :run_build
if /i "%BUILDTYPE%"=="release" goto :run_build

echo.
echo   Select build type:
echo     [1] Debug   - for daily testing
echo     [2] Release - signed, for install and share
choice /c 12 /n /m "  Enter 1 or 2: "
if errorlevel 2 goto :pick_release
set "BUILDTYPE=debug"
goto :run_build

:pick_release
set "BUILDTYPE=release"

:run_build
REM 停掉可能残留的旧 Gradle 守护进程，避免其缓存了旧的 kotlin 配置导致构建失败
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
taskkill /f /im java.exe
echo.
echo ============================================
echo   BUILD SUCCESS
echo ============================================
set "APKPATH="
for /f "delims=" %%F in ('dir /b /o-d "%OUTDIR%\*.apk" 2^>nul') do set "APKPATH=%OUTDIR%\%%F"
if not defined APKPATH (
    echo   [WARN] APK file not found in %OUTDIR%
    goto :end
)
set "APKSIZE="
for %%A in ("%APKPATH%") do set "APKSIZE=%%~zA"
echo   APK   : %CD%\%APKPATH%
echo   Size  : %APKSIZE% bytes
echo.
echo   Install: adb install -r "%CD%\%APKPATH%"
goto :end

:build_failed
taskkill /f /im java.exe
echo.
echo ============================================
echo   BUILD FAILED - check errors above
echo ============================================

:end
echo.
pause >nul
endlocal
exit /b 0

:prereq_fail
echo.
pause >nul
exit /b 1

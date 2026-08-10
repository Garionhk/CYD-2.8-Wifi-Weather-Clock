@echo off
setlocal EnableDelayedExpansion

rem ===========================================================================
rem  run.bat -- build, install and launch the CYD Clock
rem ===========================================================================
rem  Picks a target automatically: a plugged-in phone wins, otherwise the
rem  CYD_Tablet emulator is started. Nothing here needs Android Studio open.
rem ===========================================================================

set "PKG=ca.garionhk.cydclock"
set "ACT=%PKG%/.MainActivity"
set "AVD=CYD_Tablet"
set "PROJ=%~dp0"
set "APK=%PROJ%app\build\outputs\apk\debug\app-debug.apk"

rem ---- toolchain ------------------------------------------------------------
if not defined ANDROID_SDK_ROOT set "ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"
set "ADB=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
set "EMU=%ANDROID_SDK_ROOT%\emulator\emulator.exe"
if not exist "%ADB%" goto :err_adb
rem Gradle needs a JDK; Android Studio ships one and it is rarely on PATH.
if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

rem ---- arguments ------------------------------------------------------------
set "TARGET=auto"
set "DOBUILD=1"
set "DOLOG=0"

:parse
if "%~1"=="" goto parsed
if /i "%~1"=="emu"   set "TARGET=emu"
if /i "%~1"=="phone" set "TARGET=phone"
if /i "%~1"=="fast"  set "DOBUILD=0"
if /i "%~1"=="log"   set "DOLOG=1"
if /i "%~1"=="stop"  goto do_stop
if /i "%~1"=="help"  goto usage
if /i "%~1"=="-h"    goto usage
if /i "%~1"=="/?"    goto usage
shift
goto parse
:parsed

echo.
echo === CYD Clock ===

rem ---- build ----------------------------------------------------------------
if "%DOBUILD%"=="1" (
    echo [1/4] Building debug APK...
    if not exist "%PROJ%gradlew.bat" goto err_nowrapper
    rem Called by full path: cmd's current-directory search can be switched off,
    rem and the project path contains spaces.
    pushd "%PROJ%"
    call "%PROJ%gradlew.bat" :app:assembleDebug -q
    set "RC=!errorlevel!"
    popd
    if not "!RC!"=="0" goto err_build
) else (
    echo [1/4] Skipping build ^(fast^).
)
if not exist "%APK%" goto err_noapk

rem ---- pick a device --------------------------------------------------------
echo [2/4] Looking for a device...
call :find_device
if not defined SERIAL (
    if /i "%TARGET%"=="phone" goto err_nophone
    call :start_emulator
    if not "!ERRLVL!"=="0" goto :eof
    call :find_device
)
if not defined SERIAL goto err_nodevice
echo       using !SERIAL!

rem ---- install and launch ---------------------------------------------------
echo [3/4] Installing...
"%ADB%" -s !SERIAL! install -r "%APK%" >nul 2>&1
if errorlevel 1 (
    echo       reinstall failed, trying a clean install...
    "%ADB%" -s !SERIAL! uninstall %PKG% >nul 2>&1
    "%ADB%" -s !SERIAL! install "%APK%"
    if errorlevel 1 goto err_install
)

echo [4/4] Launching...
"%ADB%" -s !SERIAL! shell am start -n %ACT% >nul 2>&1
if errorlevel 1 goto err_launch

echo.
echo Running on !SERIAL!.
echo.
echo   Tap anywhere        next scene, and hold rotation for 45s
echo   Hold 0.8s - 4s      pin / unpin the scene
echo   Hold over 4s        open setup
echo   Gear, top right     open setup
echo.
echo   Note: taps shorter than 40ms are ignored as ghost touches, so
echo         "adb shell input tap" does nothing. Script one with:
echo         adb shell input swipe X Y X Y 120
echo.

if "%DOLOG%"=="1" (
    echo Attaching logcat -- Ctrl+C to detach, the app keeps running.
    echo.
    set "APPPID="
    for /f "usebackq delims=" %%P in (`"%ADB%" -s !SERIAL! shell pidof %PKG% 2^>nul`) do set "APPPID=%%P"
    if defined APPPID (
        "%ADB%" -s !SERIAL! logcat --pid=!APPPID!
    ) else (
        "%ADB%" -s !SERIAL! logcat -s AndroidRuntime:E System.err:W
    )
)
goto :eof


rem ===========================================================================
rem  subroutines
rem ===========================================================================

:find_device
rem Sets SERIAL to the chosen device, or leaves it undefined.
set "SERIAL="
set "PHYS="
set "EMUL="
for /f "skip=1 usebackq tokens=1,2" %%A in (`"%ADB%" devices 2^>nul`) do (
    if /i "%%B"=="device" (
        set "S=%%A"
        if "!S:~0,9!"=="emulator-" (
            if not defined EMUL set "EMUL=%%A"
        ) else (
            if not defined PHYS set "PHYS=%%A"
        )
    )
)
if /i "%TARGET%"=="emu"   if defined EMUL set "SERIAL=!EMUL!"
if /i "%TARGET%"=="phone" if defined PHYS set "SERIAL=!PHYS!"
if /i "%TARGET%"=="auto" (
    rem A real device beats the emulator when both are attached.
    if defined PHYS (
        set "SERIAL=!PHYS!"
    ) else (
        if defined EMUL set "SERIAL=!EMUL!"
    )
)
exit /b


:start_emulator
set "ERRLVL=1"
if not exist "%EMU%" goto err_noemu
"%EMU%" -list-avds 2>nul | findstr /x /c:"%AVD%" >nul
if errorlevel 1 goto err_noavd

echo       no device attached, starting emulator %AVD%...
start "Android Emulator - %AVD%" "%EMU%" -avd %AVD% -no-boot-anim -no-snapshot-save
"%ADB%" wait-for-device

echo       waiting for it to boot ^(this takes a minute^)...
set /a TRIES=0
:bootwait
set "BOOT="
for /f "usebackq delims=" %%B in (`"%ADB%" shell getprop sys.boot_completed 2^>nul`) do set "BOOT=%%B"
if "!BOOT:~0,1!"=="1" goto booted
set /a TRIES+=1
if !TRIES! gtr 100 goto err_boottimeout
call :sleep 3
goto bootwait
:booted
echo       emulator ready.
set "ERRLVL=0"
exit /b


:sleep
rem Sleep %1 seconds. Deliberately NOT "timeout /t": that reads the console and
rem dies with "Input redirection is not supported" whenever this script is run
rem with stdin redirected -- from another script, a CI job, or an IDE's run
rem button. ping has no such dependency.
set /a "_PINGS=%~1 + 1"
ping -n !_PINGS! 127.0.0.1 >nul 2>&1
exit /b


:do_stop
call :find_device
if not defined SERIAL (
    echo No device attached.
    goto :eof
)
echo Stopping %PKG% on !SERIAL!...
"%ADB%" -s !SERIAL! shell am force-stop %PKG%
set "S=!SERIAL!"
if "!S:~0,9!"=="emulator-" (
    echo Shutting down the emulator...
    "%ADB%" -s !SERIAL! emu kill
)
echo Done.
goto :eof


:usage
echo.
echo   run.bat [emu^|phone] [fast] [log]
echo   run.bat stop
echo.
echo     (no args)  build, then install and launch on a phone if one is
echo                plugged in, otherwise on the %AVD% emulator
echo     emu        force the emulator even if a phone is attached
echo     phone      force a physical device, fail if none is attached
echo     fast       skip the Gradle build and install the existing APK
echo     log        attach logcat for the app after launching
echo     stop       force-stop the app, and shut the emulator down
echo.
echo   Examples:
echo     run.bat                 build and run wherever it can
echo     run.bat fast log        reinstall the last build and watch the log
echo     run.bat emu             run on the tablet emulator
echo.
goto :eof


rem ===========================================================================
rem  failures -- each says what to do about it
rem ===========================================================================

:err_adb
echo.
echo ERROR: adb not found at
echo   %ADB%
echo Set ANDROID_SDK_ROOT to your SDK location, or install
echo "Android SDK Platform-Tools" from Android Studio's SDK Manager.
exit /b 1

:err_nowrapper
echo.
echo ERROR: no gradlew.bat at
echo   %PROJ%
echo Run this script from inside the Android project folder.
exit /b 1

:err_build
echo.
echo ERROR: the Gradle build failed. Run this for the full output:
echo   gradlew.bat :app:assembleDebug
exit /b 1

:err_noapk
echo.
echo ERROR: no APK at
echo   %APK%
echo Run without "fast" to build one.
exit /b 1

:err_nophone
echo.
echo ERROR: no physical device attached.
echo Enable USB debugging on the phone and accept the pairing prompt,
echo then check it appears in:  adb devices
exit /b 1

:err_nodevice
echo.
echo ERROR: no usable device. Check:  adb devices
echo A device listed as "unauthorized" needs the pairing prompt accepted
echo on the phone itself.
exit /b 1

:err_noemu
echo.
echo ERROR: the emulator is not installed at
echo   %EMU%
echo Install "Android Emulator" from Android Studio's SDK Manager,
echo or plug in a phone and use:  run.bat phone
exit /b 1

:err_noavd
echo.
echo ERROR: no AVD named %AVD%.
echo Available:
"%EMU%" -list-avds
echo.
echo Create one in Android Studio's Device Manager, or edit AVD at the
echo top of this script to use one of the above.
exit /b 1

:err_boottimeout
echo.
echo ERROR: the emulator did not finish booting in four minutes.
echo Try starting it from Android Studio's Device Manager to see why.
exit /b 1

:err_install
echo.
echo ERROR: install failed. If the device already has a build signed with
echo a different key, remove it first:
echo   adb uninstall %PKG%
exit /b 1

:err_launch
echo.
echo ERROR: the app installed but would not start. Check:
echo   adb logcat -s AndroidRuntime:E
exit /b 1

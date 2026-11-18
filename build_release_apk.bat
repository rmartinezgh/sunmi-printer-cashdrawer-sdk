@echo off
setlocal
echo ======================================================================
echo Compiling and Packaging Sunmi Cash Drawer Simulator (Android 13 Release)
echo ======================================================================

set SDK_DIR=C:\Users\Lenovo\AppData\Local\Android\Sdk
set BUILD_TOOLS=%SDK_DIR%\build-tools\34.0.0
set ANDROID_JAR=%SDK_DIR%\platforms\android-33\android.jar
set JDK_BIN=C:\Program Files\RedHat\java-1.8.0-openjdk-1.8.0.504-1\bin

if not exist build mkdir build
if not exist build\compiled_res mkdir build\compiled_res
if not exist build\gen mkdir build\gen
if not exist build\classes mkdir build\classes

echo [1/6] Compiling resources with aapt2...
"%BUILD_TOOLS%\aapt2.exe" compile --dir app\src\main\res -o build\compiled_res\res.zip
"%BUILD_TOOLS%\aapt2.exe" link -I "%ANDROID_JAR%" --manifest app\src\main\AndroidManifest.xml --java build\gen --min-sdk-version 21 --target-sdk-version 33 --version-code 1 --version-name "1.0.0" -o build\app-release-unsigned.apk build\compiled_res\res.zip --auto-add-overlay

echo [2/6] Compiling Java classes with javac against Android 13 (API 33)...
set JAVA_FILES=app\src\main\java\com\sunmi\cashdrawer\simulator\*.java build\gen\com\sunmi\cashdrawer\simulator\R.java
"%JDK_BIN%\javac.exe" -encoding UTF-8 -cp "%ANDROID_JAR%" -d build\classes %JAVA_FILES%

echo [3/6] Dexing with d8 (--release)...
"%BUILD_TOOLS%\d8.bat" --release --min-api 21 --lib "%ANDROID_JAR%" --output build build\classes\com\sunmi\cashdrawer\simulator\*.class

echo [4/6] Packaging classes.dex into APK...
cd build
"%JDK_BIN%\jar.exe" -uf app-release-unsigned.apk classes.dex
cd ..

echo [5/6] Aligning release APK (zipalign)...
"%BUILD_TOOLS%\zipalign.exe" -p -f 4 build\app-release-unsigned.apk build\SunmiCashDrawerSimulator-Android13-release.apk

echo [6/6] Signing release APK with Release Keystore (apksigner v1/v2/v3)...
"%BUILD_TOOLS%\apksigner.bat" sign --ks release.keystore --ks-pass pass:sunmi123456 --ks-key-alias sunmi_release_key --key-pass pass:sunmi123456 build\SunmiCashDrawerSimulator-Android13-release.apk

if not exist app\build\outputs\apk\release mkdir app\build\outputs\apk\release
copy /Y build\SunmiCashDrawerSimulator-Android13-release.apk app\build\outputs\apk\release\app-release.apk

echo.
echo Verification:
"%BUILD_TOOLS%\apksigner.bat" verify -v build\SunmiCashDrawerSimulator-Android13-release.apk

echo.
echo ======================================================================
echo SUCCESS: Android 13 Release APK generated at:
echo  1. build\SunmiCashDrawerSimulator-Android13-release.apk
echo  2. app\build\outputs\apk\release\app-release.apk
echo ======================================================================

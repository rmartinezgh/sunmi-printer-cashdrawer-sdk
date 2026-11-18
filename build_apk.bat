@echo off
setlocal
echo ========================================================
echo Compiling and Packaging Sunmi Cash Drawer Simulator APK
echo ========================================================

set SDK_DIR=C:\Users\Lenovo\AppData\Local\Android\Sdk
set BUILD_TOOLS=%SDK_DIR%\build-tools\34.0.0
set ANDROID_JAR=%SDK_DIR%\platforms\android-34\android.jar
set JDK_BIN=C:\Program Files\RedHat\java-1.8.0-openjdk-1.8.0.504-1\bin

if not exist build mkdir build
if not exist build\compiled_res mkdir build\compiled_res
if not exist build\gen mkdir build\gen
if not exist build\classes mkdir build\classes

echo [1/5] Compiling resources with aapt2...
"%BUILD_TOOLS%\aapt2.exe" compile --dir app\src\main\res -o build\compiled_res\res.zip
"%BUILD_TOOLS%\aapt2.exe" link -I "%ANDROID_JAR%" --manifest app\src\main\AndroidManifest.xml --java build\gen -o build\app.apk build\compiled_res\res.zip --auto-add-overlay

echo [2/5] Compiling Java classes with javac...
set JAVA_FILES=app\src\main\java\com\sunmi\cashdrawer\simulator\*.java build\gen\com\sunmi\cashdrawer\simulator\R.java
"%JDK_BIN%\javac.exe" -encoding UTF-8 -cp "%ANDROID_JAR%" -d build\classes %JAVA_FILES%

echo [3/5] Dexing with d8...
"%BUILD_TOOLS%\d8.bat" --lib "%ANDROID_JAR%" --output build build\classes\com\sunmi\cashdrawer\simulator\*.class

echo [4/5] Packaging classes.dex into APK...
cd build
"%JDK_BIN%\jar.exe" -uf app.apk classes.dex
cd ..

echo [5/5] Zipalign and signing APK...
"%BUILD_TOOLS%\zipalign.exe" -p -f 4 build\app.apk build\SunmiCashDrawerSimulator.apk
"%BUILD_TOOLS%\apksigner.bat" sign --ks build\debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android build\SunmiCashDrawerSimulator.apk

if not exist app\build\outputs\apk\debug mkdir app\build\outputs\apk\debug
copy /Y build\SunmiCashDrawerSimulator.apk app\build\outputs\apk\debug\app-debug.apk

echo ========================================================
echo SUCCESS: APK generated at build\SunmiCashDrawerSimulator.apk
echo ========================================================

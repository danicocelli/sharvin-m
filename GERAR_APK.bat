@echo off
setlocal
cd /d "%~dp0"
echo ========================================
echo         SHARVIN MOBILE - GERAR APK
 echo ========================================
where java >nul 2>&1
if errorlevel 1 (
  echo ERRO: instale o JDK 17 e o Android Studio antes de compilar.
  pause
  exit /b 1
)
if not defined ANDROID_HOME if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_HOME if exist "%LOCALAPPDATA%\Android\sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\sdk"
if not defined ANDROID_HOME (
  echo ERRO: Android SDK nao encontrado. Instale o Android Studio e SDK Platform 34.
  pause
  exit /b 1
)
echo Usando Android SDK: %ANDROID_HOME%
where gradle >nul 2>&1
if not errorlevel 1 (
  call gradle --no-daemon assembleDebug
) else (
  echo Gradle nao encontrado: baixando Gradle 8.7 na primeira execucao...
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build-windows.ps1"
)
if errorlevel 1 (
  echo FALHA NA COMPILACAO. Confira a mensagem acima.
  pause
  exit /b 1
)
set "APK=app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" (
  echo ERRO: APK nao foi criado.
  pause
  exit /b 1
)
copy /Y "%APK%" "%~dp0Sharvin-Mobile.apk" >nul
 echo APK criado: %~dp0Sharvin-Mobile.apk
pause

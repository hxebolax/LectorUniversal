@echo off
setlocal EnableDelayedExpansion
chcp 65001 >nul
title Lector Universal - Compilar e instalar
cd /d "%~dp0"

set "RAIZ=%~dp0"
set "RAIZ=%RAIZ:~0,-1%"
set "JAVA_HOME=%RAIZ%\entorno\jdk"
set "ANDROID_HOME=%RAIZ%\entorno\android-sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "ANDROID_AVD_HOME=%RAIZ%\entorno\avd"
set "GRADLE_USER_HOME=%RAIZ%\entorno\gradle-home"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%"
set "GRADLE=%RAIZ%\entorno\gradle\bin\gradle.bat"
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
set "EMULADOR=%ANDROID_HOME%\emulator\emulator.exe"
set "APK=%RAIZ%\LectorUniversal.apk"
set "PAQUETE=com.xebolax.lector"

rem Uso sin menu: compilar.bat compilar ^| instalar ^| todo ^| emulador ^| probar ^| publicar ^| pruebas
if /i "%~1"=="compilar" goto :compilar_solo
if /i "%~1"=="instalar" goto :instalar_solo
if /i "%~1"=="todo" goto :todo
if /i "%~1"=="emulador" goto :emulador_solo
if /i "%~1"=="probar" goto :probar_solo
if /i "%~1"=="publicar" goto :publicar_solo
if /i "%~1"=="pruebas" goto :pruebas_solo

:menu
echo.
echo ============================================
echo   Lector Universal para Android
echo ============================================
echo   1. Compilar el APK
echo   2. Compilar e instalar en el movil conectado
echo   3. Instalar el APK ya compilado
echo   4. Ver moviles conectados
echo   5. Abrir la maquina virtual de pruebas (emulador)
echo   6. Compilar, abrir el emulador e instalar en el
echo   7. Pasar las pruebas automaticas (en el PC y en el emulador o movil)
echo   8. Compilar y publicar la actualizacion (servidor y GitHub)
echo   9. Preparar o reparar el entorno (JDK, SDK, Gradle, emulador)
echo   0. Salir
echo.
set "OPCION="
set /p "OPCION=Elige una opcion (0-9): "
if "%OPCION%"=="1" call :compilar & goto :menu
if "%OPCION%"=="2" goto :todo_menu
if "%OPCION%"=="3" call :instalar & goto :menu
if "%OPCION%"=="4" call :dispositivos & goto :menu
if "%OPCION%"=="5" call :emulador & goto :menu
if "%OPCION%"=="6" call :probar & goto :menu
if "%OPCION%"=="7" call :pruebas & goto :menu
if "%OPCION%"=="8" call :publicar & goto :menu
if "%OPCION%"=="9" call :preparar & goto :menu
if "%OPCION%"=="0" exit /b 0
echo Opcion no valida.
goto :menu

:todo_menu
call :compilar
if errorlevel 1 goto :menu
call :instalar
goto :menu

:compilar_solo
call :compilar
exit /b %errorlevel%

:instalar_solo
call :instalar
exit /b %errorlevel%

:emulador_solo
call :emulador
exit /b %errorlevel%

:probar_solo
call :probar
exit /b %errorlevel%

:publicar_solo
call :publicar
exit /b %errorlevel%

:pruebas_solo
call :pruebas
exit /b %errorlevel%

:todo
call :compilar
if errorlevel 1 exit /b 1
call :instalar
exit /b %errorlevel%

rem ---------------------------------------------------------------------------
:entorno
if exist "%JAVA_HOME%\bin\java.exe" if exist "%GRADLE%" if exist "%ANDROID_HOME%\platforms\android-35" exit /b 0
echo.
echo [Entorno] Falta el entorno de compilacion. Se descargara en la carpeta "entorno":
echo           JDK 17, Android SDK, Gradle y el emulador (unos 3 GB).
echo           Al instalar el Android SDK se aceptan sus licencias de Google.
call :preparar
exit /b %errorlevel%

:preparar
powershell -NoProfile -ExecutionPolicy Bypass -File "%RAIZ%\herramientas\preparar_entorno.ps1"
if errorlevel 1 (
	echo [ERROR] No se pudo preparar el entorno.
	exit /b 1
)
exit /b 0

rem ---------------------------------------------------------------------------
:firma
if exist "%RAIZ%\firma\firma.properties" exit /b 0
echo.
echo [Firma] Creando la firma propia de la aplicacion (firma\lector.jks)...
echo         Guardala: sin ella no podras actualizar la app sin desinstalarla.
if not exist "%RAIZ%\firma" mkdir "%RAIZ%\firma"
for /f %%c in ('powershell -NoProfile -Command "-join ((48..57)+(65..90)+(97..122) | Get-Random -Count 24 | ForEach-Object {[char]$_})"') do set "CLAVE=%%c"
"%JAVA_HOME%\bin\keytool.exe" -genkeypair -v -keystore "%RAIZ%\firma\lector.jks" -alias lector -keyalg RSA -keysize 2048 -validity 10000 -storepass !CLAVE! -keypass !CLAVE! -dname "CN=Lector Universal, O=XeBoLaX, C=ES" >nul 2>&1
if errorlevel 1 (
	echo [ERROR] No se pudo crear la firma.
	exit /b 1
)
(
	echo archivo=firma/lector.jks
	echo alias=lector
	echo clave=!CLAVE!
) > "%RAIZ%\firma\firma.properties"
exit /b 0

rem ---------------------------------------------------------------------------
:compilar
call :entorno || exit /b 1
call :firma || exit /b 1
echo.
echo [Compilar] Compilando el APK. La primera vez tarda varios minutos...
call "%GRADLE%" --no-daemon -p "%RAIZ%" assembleRelease
if errorlevel 1 (
	echo.
	echo [ERROR] La compilacion ha fallado. Revisa los mensajes de arriba.
	exit /b 1
)
copy /y "%RAIZ%\app\build\outputs\apk\release\app-release.apk" "%APK%" >nul
echo.
echo [OK] APK listo: %APK%
exit /b 0

rem ---------------------------------------------------------------------------
:pruebas
call :entorno || exit /b 1
echo.
echo [Pruebas] Pruebas en el PC (modelo de documento, frases, navegacion)...
call "%GRADLE%" --no-daemon -p "%RAIZ%" testDebugUnitTest
if errorlevel 1 (
	echo [ERROR] Alguna prueba ha fallado. El informe esta en app\build\reports\tests.
	exit /b 1
)
echo [OK] Pruebas en el PC superadas.
rem Las pruebas de formatos abren libros de muestra de verdad: necesitan un emulador o un movil
"%ADB%" start-server >nul 2>&1
set "HAY="
for /f "skip=1 tokens=2" %%b in ('call "%ADB%" devices') do if "%%b"=="device" set "HAY=1"
if not defined HAY (
	echo [AVISO] Sin emulador ni movil conectado no se pasan las pruebas de formatos ^(opcion 5 abre el emulador^).
	exit /b 0
)
echo.
echo [Pruebas] Pruebas de formatos en el dispositivo (EPUB, PDF, Word, DAISY, M4B...)...
call "%GRADLE%" --no-daemon -p "%RAIZ%" connectedDebugAndroidTest
if errorlevel 1 (
	echo [ERROR] Alguna prueba de formatos ha fallado. Informe: app\build\reports\androidTests\connected
	exit /b 1
)
echo [OK] Todas las pruebas han pasado.
exit /b 0

rem ---------------------------------------------------------------------------
:dispositivos
if not exist "%ADB%" call :entorno || exit /b 1
"%ADB%" start-server >nul 2>&1
echo.
"%ADB%" devices -l
exit /b 0

rem ---------------------------------------------------------------------------
:emulador
if not exist "%EMULADOR%" call :preparar || exit /b 1
if not exist "%ANDROID_AVD_HOME%\pruebas.ini" call :preparar || exit /b 1
"%ADB%" start-server >nul 2>&1
for /f "tokens=1" %%a in ('call "%ADB%" devices ^| findstr /b "emulator-"') do (
	echo [Emulador] La maquina virtual ya esta abierta ^(%%a^).
	exit /b 0
)
echo.
echo [Emulador] Abriendo la maquina virtual "pruebas" (Android 15)...
start "Emulador Lector Universal" "%EMULADOR%" -avd pruebas -no-snapshot-save -no-boot-anim
echo [Emulador] Esperando a que arranque (puede tardar uno o dos minutos)...
"%ADB%" wait-for-device
:esperar_arranque
for /f %%b in ('call "%ADB%" -e shell getprop sys.boot_completed 2^>nul') do if "%%b"=="1" goto :arrancado
timeout /t 3 /nobreak >nul
goto :esperar_arranque
:arrancado
echo [OK] Emulador listo.
exit /b 0

:probar
call :compilar || exit /b 1
call :emulador || exit /b 1
echo.
echo [Instalar] Instalando en el emulador...
"%ADB%" -e install -r "%APK%"
if errorlevel 1 (
	echo [ERROR] No se pudo instalar en el emulador.
	exit /b 1
)
"%ADB%" -e shell monkey -p %PAQUETE% -c android.intent.category.LAUNCHER 1 >nul 2>&1
rem Libros de ejemplo en la carpeta Descargas del emulador
if exist "%RAIZ%\pruebas\libros" (
	"%ADB%" -e push "%RAIZ%\pruebas\libros\." /sdcard/Download/ >nul 2>&1
	echo [OK] Libros de ejemplo copiados a Descargas del emulador.
)
echo [OK] Lector Universal abierto en el emulador.
exit /b 0

rem ---------------------------------------------------------------------------
:instalar
if not exist "%APK%" (
	echo [ERROR] No existe %APK%. Compila primero.
	exit /b 1
)
if not exist "%ADB%" call :entorno || exit /b 1
"%ADB%" start-server >nul 2>&1
set "DISPOSITIVOS=0"
set "SERIE="
for /f "skip=1 tokens=1,2" %%a in ('call "%ADB%" devices') do (
	if "%%b"=="device" (
		set /a DISPOSITIVOS+=1
		set "SERIE=%%a"
	)
	if "%%b"=="unauthorized" echo [AVISO] El movil %%a no ha autorizado la depuracion USB: acepta el aviso en su pantalla.
)
if "%DISPOSITIVOS%"=="0" (
	echo.
	echo [AVISO] No hay ningun movil conectado y autorizado.
	echo         1. Activa las Opciones de desarrollador y la Depuracion USB.
	echo         2. Conecta el movil por USB y acepta el aviso de depuracion.
	echo         Tambien puedes copiar LectorUniversal.apk al movil e instalarlo a mano.
	exit /b 1
)
if not "%DISPOSITIVOS%"=="1" (
	echo.
	"%ADB%" devices -l
	set /p "SERIE=Hay varios dispositivos. Escribe el numero de serie del que quieres usar: "
)
echo.
echo [Instalar] Instalando Lector Universal en !SERIE!...
"%ADB%" -s !SERIE! install -r "%APK%" > "%TEMP%\lector_adb.txt" 2>&1
type "%TEMP%\lector_adb.txt"
findstr /c:"Success" "%TEMP%\lector_adb.txt" >nul
if not errorlevel 1 (
	echo [OK] Lector Universal instalado. Abriendolo...
	"%ADB%" -s !SERIE! shell monkey -p %PAQUETE% -c android.intent.category.LAUNCHER 1 >nul 2>&1
	exit /b 0
)
findstr /c:"INSTALL_FAILED_UPDATE_INCOMPATIBLE" "%TEMP%\lector_adb.txt" >nul
if not errorlevel 1 (
	echo.
	echo [AVISO] Hay otro Lector Universal instalado con una firma distinta.
	set "RESPUESTA="
	set /p "RESPUESTA=Desinstalarlo (se borran su biblioteca y marcadores) e instalar este? (s/n): "
	if /i "!RESPUESTA!"=="s" (
		"%ADB%" -s !SERIE! uninstall %PAQUETE%
		"%ADB%" -s !SERIE! install "%APK%"
		exit /b !errorlevel!
	)
)
echo [ERROR] No se pudo instalar.
exit /b 1

rem ---------------------------------------------------------------------------
rem Compila y deja en dist el APK con su version y version.json (SHA-256 y
rem novedades de NOVEDADES.txt) para subirlos a la carpeta del servidor.
:publicar
call :compilar || exit /b 1
powershell -NoProfile -ExecutionPolicy Bypass -File "%RAIZ%\herramientas\publicar_actualizacion.ps1"
exit /b %errorlevel%

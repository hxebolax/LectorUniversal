# -*- coding: utf-8 -*-
# Lector Accesible - Prepara el entorno portatil de compilacion y pruebas.
#
# Todo queda dentro de la carpeta "entorno" (no se instala nada en el sistema):
#   - JDK 17 (Eclipse Temurin)
#   - Gradle
#   - Android SDK: cmdline-tools, platform-tools (adb), plataforma, build-tools
#   - Emulador + imagen de sistema x86_64 y una maquina virtual "pruebas"
#     (en entorno\avd) para probar la app con TalkBack.
#
# Uso:
#   preparar_entorno.ps1                 descarga lo que falte
#   preparar_entorno.ps1 -SinEmulador    solo lo necesario para compilar
#   preparar_entorno.ps1 -Origen <ruta>  copia lo que falte de otro "entorno"
#                                        ya descargado (mas rapido)
#
# Al instalar el SDK se ACEPTAN las licencias de Android SDK de Google.
# Es idempotente: lo que ya esta no se vuelve a descargar.

param(
	[switch]$SinEmulador,
	[string]$Origen = ""
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Raiz = Split-Path -Parent $PSScriptRoot
$Entorno = Join-Path $Raiz "entorno"
$Descargas = Join-Path $Entorno "descargas"
New-Item -ItemType Directory -Force $Entorno, $Descargas | Out-Null

$JdkUrl = "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip"
$SdkToolsUrl = "https://dl.google.com/android/repository/commandlinetools-win-16111833_latest.zip"
$GradleVersion = "8.11.1"
$GradleUrl = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"
$Imagen = "system-images;android-35;google_apis;x86_64"
$PaquetesSdk = @("platform-tools", "platforms;android-35", "build-tools;35.0.0")
if (-not $SinEmulador) { $PaquetesSdk += @("emulator", $Imagen) }

function Paso($texto) { Write-Host ""; Write-Host "==> $texto" -ForegroundColor Cyan }

function Descargar($url, $destino) {
	if (Test-Path $destino) { Write-Host "    Ya descargado: $(Split-Path -Leaf $destino)"; return }
	Write-Host "    Descargando $(Split-Path -Leaf $destino)..."
	$temporal = "$destino.parcial"
	for ($intento = 1; $intento -le 3; $intento++) {
		try {
			Invoke-WebRequest -Uri $url -OutFile $temporal -UseBasicParsing
			Move-Item -Force $temporal $destino
			return
		} catch {
			if ($intento -eq 3) { throw "No se pudo descargar $url : $_" }
			Start-Sleep -Seconds (5 * $intento)
		}
	}
}

function Extraer($zip, $destino) {
	$temporal = "$destino.tmp"
	if (Test-Path $temporal) { Remove-Item -Recurse -Force $temporal }
	Expand-Archive -Path $zip -DestinationPath $temporal -Force
	# Si el zip contiene una unica carpeta, se usa su contenido
	$hijos = @(Get-ChildItem $temporal)
	if ($hijos.Count -eq 1 -and $hijos[0].PSIsContainer) {
		Move-Item $hijos[0].FullName $destino
		Remove-Item -Recurse -Force $temporal
	} else {
		Move-Item $temporal $destino
	}
}

# Copia una carpeta de otro entorno (si se indico -Origen y existe alli)
function CopiarDeOrigen($nombre) {
	if (-not $Origen) { return $false }
	$desde = Join-Path $Origen $nombre
	if (-not (Test-Path $desde)) { return $false }
	Write-Host "    Copiando $nombre desde $Origen..."
	robocopy $desde (Join-Path $Entorno $nombre) /E /NFL /NDL /NJH /NJS /NP /MT:16 | Out-Null
	return $true
}

# --- JDK -------------------------------------------------------------------
$Jdk = Join-Path $Entorno "jdk"
if (-not (Test-Path (Join-Path $Jdk "bin\java.exe"))) {
	Paso "JDK 17"
	if (-not (CopiarDeOrigen "jdk")) {
		$zip = Join-Path $Descargas "jdk17.zip"
		Descargar $JdkUrl $zip
		Extraer $zip $Jdk
	}
} else { Paso "JDK 17 ya instalado" }

# --- Gradle ----------------------------------------------------------------
$Gradle = Join-Path $Entorno "gradle"
if (-not (Test-Path (Join-Path $Gradle "bin\gradle.bat"))) {
	Paso "Gradle $GradleVersion"
	if (-not (CopiarDeOrigen "gradle")) {
		$zip = Join-Path $Descargas "gradle-$GradleVersion-bin.zip"
		Descargar $GradleUrl $zip
		Extraer $zip $Gradle
	}
} else { Paso "Gradle ya instalado" }

# --- Android SDK -------------------------------------------------------------
$Sdk = Join-Path $Entorno "android-sdk"
$SdkManager = Join-Path $Sdk "cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $SdkManager)) {
	Paso "Android SDK"
	if (-not (CopiarDeOrigen "android-sdk")) {
		$zip = Join-Path $Descargas "commandlinetools.zip"
		Descargar $SdkToolsUrl $zip
		$destino = Join-Path $Sdk "cmdline-tools"
		New-Item -ItemType Directory -Force $destino | Out-Null
		$temporal = Join-Path $destino "tmp"
		if (Test-Path $temporal) { Remove-Item -Recurse -Force $temporal }
		Expand-Archive -Path $zip -DestinationPath $temporal -Force
		Move-Item (Join-Path $temporal "cmdline-tools") (Join-Path $destino "latest")
		Remove-Item -Recurse -Force $temporal
	}
}

$env:JAVA_HOME = $Jdk
$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
$env:Path = "$Jdk\bin;$env:Path"

$faltan = @($PaquetesSdk | Where-Object {
	-not (Test-Path (Join-Path $Sdk ($_ -replace ";", "\")))
})
if ($faltan.Count -gt 0) {
	Paso "Aceptando licencias del Android SDK"
	$respuestas = ("y`n" * 30)
	$respuestas | & $SdkManager --sdk_root="$Sdk" --licenses | Out-Null
	Paso "Instalando paquetes del SDK: $($faltan -join ', ')"
	# Entre comillas: cmd.exe trata ";" como separador de argumentos
	$argumentos = @($faltan | ForEach-Object { '"' + $_ + '"' })
	$respuestas | & $SdkManager --sdk_root="$Sdk" @argumentos
	if ($LASTEXITCODE -ne 0) { throw "sdkmanager fallo con codigo $LASTEXITCODE" }
} else { Paso "Paquetes del Android SDK ya instalados" }

# --- Maquina virtual de pruebas ---------------------------------------------
if (-not $SinEmulador) {
	$Avd = Join-Path $Entorno "avd"
	$env:ANDROID_AVD_HOME = $Avd
	New-Item -ItemType Directory -Force $Avd | Out-Null
	if (-not (Test-Path (Join-Path $Avd "pruebas.ini"))) {
		Paso "Creando la maquina virtual 'pruebas' (Pixel 6, Android 15)"
		$AvdManager = Join-Path $Sdk "cmdline-tools\latest\bin\avdmanager.bat"
		"no" | & $AvdManager create avd --force --name pruebas --package $Imagen --device pixel_6 --path (Join-Path $Avd "pruebas.avd")
		if ($LASTEXITCODE -ne 0) { throw "avdmanager fallo con codigo $LASTEXITCODE" }
		# Teclado fisico y mas memoria para que sea comodo
		$config = Join-Path $Avd "pruebas.avd\config.ini"
		Add-Content -Encoding ascii $config "hw.keyboard=yes`r`nhw.ramSize=4096`r`nhw.gpu.enabled=yes`r`nhw.gpu.mode=auto"
	} else { Paso "Maquina virtual 'pruebas' ya creada" }
}

Paso "Entorno listo en $Entorno"

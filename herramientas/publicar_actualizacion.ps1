# -*- coding: utf-8 -*-
# Lector Universal - Prepara una actualizacion para el servidor.
#
# Copia LectorUniversal.apk a dist\LectorUniversal_v<version>.apk y crea
# dist\version.json con la version, el tamano, el SHA-256 y las novedades
# (seccion "== <version>" de NOVEDADES.txt). Despues los copia a la carpeta
# sincronizada con el servidor (si existe) y, si esta la herramienta gh de
# GitHub con la sesion iniciada, crea la release v<version> en GitHub con los
# dos archivos (la app puede actualizarse desde el servidor, GitHub o ambos).
#
# Uso: publicar_actualizacion.ps1 [-Apk otro.apk] [-Dist otra_carpeta] [-Repo usuario/repositorio] [-SinGitHub]

param(
	[string]$Apk = "",
	[string]$Dist = "",
	# Carpeta sincronizada con el servidor web (https://xebolax.synology.me/LectorUniversal).
	# Si existe, el APK y version.json se copian alli directamente.
	[string]$Servidor = "$env:USERPROFILE\SynologyDrive\web\LectorUniversal",
	# Repositorio de GitHub donde se publica la release (el mismo que REPO_GITHUB de build.gradle.kts)
	[string]$Repo = "hxebolax/LectorUniversal",
	[switch]$SinGitHub
)

$ErrorActionPreference = "Stop"
$Raiz = Split-Path -Parent $PSScriptRoot
if (-not $Apk) { $Apk = Join-Path $Raiz "LectorUniversal.apk" }
if (-not $Dist) { $Dist = Join-Path $Raiz "dist" }
$Aapt2 = Join-Path $Raiz "entorno\android-sdk\build-tools\35.0.0\aapt2.exe"
$Utf8 = New-Object System.Text.UTF8Encoding($false)

function Error($texto) { Write-Host "[ERROR] $texto" -ForegroundColor Red; exit 1 }

if (-not (Test-Path $Apk)) { Error "No existe $Apk. Compila primero." }

# Version del proyecto
$gradle = [IO.File]::ReadAllText((Join-Path $Raiz "app\build.gradle.kts"), $Utf8)
$nombre = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$codigo = [regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value
if (-not $nombre -or -not $codigo) { Error "No se encuentran versionName y versionCode en app\build.gradle.kts" }

# El APK tiene que ser de esa version (evita publicar uno antiguo)
if (Test-Path $Aapt2) {
	$datos = & $Aapt2 dump badging $Apk 2>$null | Select-String "versionCode='(\d+)' versionName='([^']*)'"
	if ($datos) {
		$c = $datos.Matches[0].Groups[1].Value
		$v = $datos.Matches[0].Groups[2].Value
		if ($c -ne $codigo -or $v -ne $nombre) { Error "El APK es la version $v ($c), no la $nombre ($codigo). Compila primero." }
	}
} else {
	Write-Host "[AVISO] Sin aapt2 no se puede comprobar la version del APK."
}

# Novedades de NOVEDADES.txt (seccion "== 1.2.0")
$notas = ""
$fichero = Join-Path $Raiz "NOVEDADES.txt"
if (Test-Path $fichero) {
	$texto = [IO.File]::ReadAllText($fichero, $Utf8)
	$partes = [regex]::Split($texto, '(?m)^==\s*(\S+)\s*$')
	for ($i = 1; $i -lt $partes.Length - 1; $i += 2) {
		if ($partes[$i] -eq $nombre) { $notas = $partes[$i + 1].Trim() }
	}
	if (-not $notas) { Write-Host "[AVISO] NOVEDADES.txt no tiene la seccion '== $nombre': se publica sin novedades." }
}

New-Item -ItemType Directory -Force $Dist | Out-Null
$archivo = "LectorUniversal_v$nombre.apk"
$destino = Join-Path $Dist $archivo
Copy-Item -Force $Apk $destino
$sha = (Get-FileHash -Algorithm SHA256 $destino).Hash.ToLower()
$tamano = (Get-Item $destino).Length

$manifiesto = [ordered]@{
	version = $nombre
	versionCode = [int]$codigo
	file = $archivo
	sha256 = $sha
	size = $tamano
	date = (Get-Date -Format "yyyy-MM-dd")
	notas = $notas
}
$json = $manifiesto | ConvertTo-Json -Depth 3
[IO.File]::WriteAllText((Join-Path $Dist "version.json"), $json + "`n", $Utf8)

Write-Host "[OK] Actualizacion $nombre lista en $Dist :" -ForegroundColor Green
Write-Host "     $archivo ($tamano bytes)"
Write-Host "     version.json"
if ($Servidor -and (Test-Path (Split-Path -Parent $Servidor))) {
	New-Item -ItemType Directory -Force $Servidor | Out-Null
	Copy-Item -Force $destino (Join-Path $Servidor $archivo)
	# version.json al final: asi nadie ve una version cuyo APK aun no esta subido
	Copy-Item -Force (Join-Path $Dist "version.json") (Join-Path $Servidor "version.json")
	Write-Host "[OK] Copiados a $Servidor (Synology Drive los sube al servidor)." -ForegroundColor Green
} else {
	Write-Host "     Sube los dos archivos a la carpeta del servidor de actualizaciones."
}

# GitHub: release v<version> con el APK y version.json (las notas, las de NOVEDADES.txt)
if (-not $SinGitHub) {
	$gh = Get-Command gh -ErrorAction SilentlyContinue
	if (-not $gh) {
		Write-Host "[AVISO] Sin la herramienta gh no se publica en GitHub (https://cli.github.com)."
	} else {
		& gh auth status *> $null
		if ($LASTEXITCODE -ne 0) {
			Write-Host "[AVISO] gh no tiene la sesion iniciada (gh auth login): no se publica en GitHub."
		} else {
			$etiqueta = "v$nombre"
			$fichNotas = Join-Path $Dist "notas.md"
			[IO.File]::WriteAllText($fichNotas, $notas + "`n", $Utf8)
			& gh release view $etiqueta --repo $Repo *> $null
			if ($LASTEXITCODE -eq 0) {
				# Ya existe: se reemplazan los archivos y las notas
				& gh release upload $etiqueta $destino (Join-Path $Dist "version.json") --repo $Repo --clobber
				& gh release edit $etiqueta --repo $Repo --notes-file $fichNotas
			} else {
				& gh release create $etiqueta $destino (Join-Path $Dist "version.json") --repo $Repo --title "Lector Universal $nombre" --notes-file $fichNotas --latest
			}
			if ($LASTEXITCODE -eq 0) { Write-Host "[OK] Publicada en GitHub: https://github.com/$Repo/releases/tag/$etiqueta" -ForegroundColor Green }
			else { Write-Host "[ERROR] No se pudo publicar en GitHub." -ForegroundColor Red }
		}
	}
}

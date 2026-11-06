# Lector Universal para Android

**Libros, documentos, audiolibros, podcasts, noticias y radio en una sola app, pensada desde el principio para usarse con TalkBack.**

Lector Universal es una app Android hecha por y para personas ciegas o con baja visión. Lee casi cualquier formato de libro o documento, los lee en voz alta, reproduce audiolibros y libros DAISY, y además tiene un cliente de podcasts, un lector de noticias (RSS) y una radio con más de 50.000 emisoras de todo el mundo. Todo funciona igual en cada sección y todo se maneja con TalkBack, un teclado o Switch Access.

- Versión actual: **1.0.8**
- Android 7.0 (API 24) o posterior. Probada en Android 15.
- Idiomas: español (principal) e inglés.
- Autor: Héctor J. Benítez Corredera.

---

## Índice

- [Para quien usa la app](#para-quien-usa-la-app)
  - [Qué vas a encontrar](#qué-vas-a-encontrar)
  - [Accesibilidad](#accesibilidad)
  - [Instalar y actualizar](#instalar-y-actualizar)
  - [Privacidad](#privacidad)
- [Para quien quiere compilarla](#para-quien-quiere-compilarla)
  - [Requisitos](#requisitos)
  - [Compilar con compilar.bat (Windows)](#compilar-con-compilarbat-windows)
  - [Compilar a mano con Gradle](#compilar-a-mano-con-gradle)
  - [Firma](#firma)
  - [Pruebas](#pruebas)
  - [Publicar una versión](#publicar-una-versión)
  - [Estructura del código](#estructura-del-código)
  - [Normas del proyecto](#normas-del-proyecto)
  - [Traducir la app](#traducir-la-app)
- [Créditos y licencias de terceros](#créditos-y-licencias-de-terceros)
- [English summary](#english-summary)

---

## Para quien usa la app

### Qué vas a encontrar

La app tiene cuatro **secciones**, como cuatro apps en una. En todas, «Abrir menú» está arriba a la izquierda y las pestañas de la sección están abajo, siempre a la vista. Encima de las pestañas, un **mini reproductor** muestra lo que suena (un libro leído en voz alta, un audiolibro, un episodio o la radio) con Pausar, Detener y volver a su pantalla. Sigue ahí al cambiar de sección: puedes escuchar la radio mientras lees las noticias.

#### Biblioteca

- **Formatos**: EPUB 2 y 3, PDF (también escaneados, con reconocimiento de texto sin internet), Word (DOCX y DOC antiguo), OpenDocument (ODT, ODS, ODP), RTF, texto, HTML, Markdown, FictionBook (FB2), Kindle (MOBI, AZW, AZW3, PRC, PDB), CHM, PowerPoint, Excel, CSV, cómics (CBZ), ZIP, **DAISY 2.02 y 3** y audiolibros **M4B y MP3** con capítulos.
- Pestañas Leyendo, Biblioteca (con carpetas), Marcadores y Catálogos (OPDS y bibliotecas web como Tiflolibros o la Biblioteca Digital de la ONCE).
- Abrir una página web como libro, leer el portapapeles y escanear documentos con la cámara con guía de voz.
- Carpetas vigiladas, colecciones, series, estadísticas de lectura, copia de seguridad y sincronización con tu propio servidor (WebDAV).

#### Lectura

- Navegación como en un lector de pantalla de escritorio: por encabezados (y por niveles), párrafos, páginas, listas, tablas, enlaces, imágenes, notas y marcadores.
- **Lectura en voz alta** con cualquier motor de voz instalado, que sigue con la pantalla apagada y responde a los botones de los auriculares; cambia de voz según el idioma de cada párrafo; diccionario de pronunciación; temporizador.
- Fórmulas matemáticas leídas en voz alta, tablas con encabezados («Columna: valor») y descripción de imágenes.
- **Inteligencia artificial** opcional: la del propio móvil (Gemini Nano), una descargable que funciona sin internet (Gemma) o tus propias claves, para resumir, explicar, traducir o describir imágenes.

#### Reproductor de audio

- Audiolibros, DAISY, podcasts y el audio de las noticias con el mismo reproductor: capítulos, niveles DAISY, páginas, frases, marcadores, velocidad del 25 al 400 % sin cambiar el tono, saltar silencios, refuerzo de volumen y temporizador con bajada de volumen.

#### Podcasts

- Suscripciones con carpetas, Novedades, Cola, Descargas, Favoritos, Historial y Descubrir (los más escuchados por categoría y buscador de podcasts y de episodios).
- Ficha completa de cada podcast antes de suscribirte, capítulos, notas, transcripciones, filtros por podcast, podcasts privados, OPML y sincronización con gpodder.net o Nextcloud.

#### Noticias (RSS)

- Sin leer, Fuentes (con carpetas), Favoritos, Más tarde y Buscar. Artículo completo descargado de la web, lectura seguida en voz alta, reglas y alertas por palabras, búsquedas guardadas, OPML y sincronización con FreshRSS, The Old Reader, Miniflux o Nextcloud News.

#### Radio (nueva en la 1.0.8)

- Más de 50.000 emisoras del directorio libre [radio-browser.info](https://www.radio-browser.info/), sin cuenta ni publicidad, y las que añadas tú a mano.
- Pestañas **Favoritas** (con carpetas y orden), **Explorar** (las de tu país, las más escuchadas, las más votadas, en tendencia, lo que se escucha ahora, nuevas, y por género, país o idioma), **Buscar** (por nombre con filtros de país, idioma, género, calidad y orden), **Historial** (emisoras, canciones que han sonado y grabaciones) y **Programar**.
- Reproductor en directo que se reconecta solo si se corta, dice la canción que suena, pasa a la emisora anterior o siguiente (también con los auriculares), temporizador, ecualizador del sistema y búsqueda de la canción en internet.
- **Grabar** una emisora (sin perder calidad, aunque escuches otra cosa) y **grabaciones programadas**.
- **Despertador con radio**: días de la semana, volumen que sube poco a poco, Apagar y Posponer en la pantalla de bloqueo, y alarma del móvil de reserva si no hay internet.
- Emisoras a mano (también desde listas .pls o .m3u), importar y exportar M3U, «Añadir a Radio» desde Compartir y accesos directos en la pantalla de inicio.

El **manual completo** está dentro de la app (menú lateral, Manual de uso) y en [`app/src/main/assets/manual-es.html`](app/src/main/assets/manual-es.html). Las novedades de cada versión están en [`NOVEDADES.txt`](NOVEDADES.txt).

### Accesibilidad

La accesibilidad no es un añadido: cada pantalla se diseña y se prueba con TalkBack.

- Cada fila de una lista es **una sola parada** que lo dice todo, con el estado primero («No leído, título, fuente, hace 5 minutos»).
- **Acciones de TalkBack** en cada elemento, y el usuario elige cuáles salen y en qué orden en cada sitio de la app (Ajustes, Acciones de TalkBack). Todo lo que se hace con un gesto también tiene botón o acción.
- Encabezados reales, nombres claros en cada botón («Emisora siguiente», «Avanzar 30 segundos»), deslizadores que dicen su valor con unidad y avanzan pasos exactos.
- Al cerrar un diálogo o una pantalla, el foco vuelve al sitio desde el que se abrió.
- Lo que tarda muestra una ventana de espera con Cancelar; los avisos no mueven el foco.
- Tema claro y oscuro con buen contraste, texto que crece hasta el 200 %, objetivos táctiles de 48 dp, uso con teclado físico.
- El audio respeta a TalkBack: baja el volumen o se pausa mientras habla (a elegir).

### Instalar y actualizar

La app no está en Google Play. Se instala desde el APK publicado en el servidor del autor y **se actualiza sola**: al abrirse busca versiones nuevas, muestra qué traen y, al aceptar, descarga la actualización, comprueba que es auténtica (tamaño, SHA-256, que es esta app y que está firmada con la misma clave) y abre el instalador de Android.

La primera vez, Android pide permitir instalar aplicaciones de esta fuente.

### Privacidad

- Sin publicidad, sin estadísticas de uso y sin cuentas propias.
- Los libros, las posiciones, las notas y los ajustes se quedan en el móvil. Solo salen de él si configuras tu propia sincronización (WebDAV, una cuenta de noticias o de podcasts) o usas una IA en la nube con tu clave.
- Los permisos se piden al usar cada función (cámara para escanear, avisos para el despertador…) y la app sigue funcionando si los deniegas.

---

## Para quien quiere compilarla

### Requisitos

| | Versión |
|---|---|
| JDK | 17 |
| Gradle | 8.11.1 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.3.21 |
| Android SDK | compileSdk y targetSdk 35, minSdk 24 |

En Windows no hace falta instalar nada: `compilar.bat` descarga un **entorno portátil** dentro de la carpeta `entorno\` (JDK, Gradle, SDK de Android, emulador y una máquina virtual de pruebas con TalkBack). Esa carpeta no se sube al repositorio.

### Compilar con compilar.bat (Windows)

Haz doble clic en `compilar.bat` y elige una opción:

1. Compilar el APK → `LectorUniversal.apk`
2. Compilar e instalar en el móvil conectado por USB (con la depuración USB activada)
3. Instalar el APK ya compilado
4. Ver móviles conectados
5. Abrir la máquina virtual de pruebas (Pixel 6, Android 15, con TalkBack)
6. Compilar, abrir el emulador, instalar y copiar libros de ejemplo (`pruebas\libros`)
7. Pasar las pruebas automáticas
8. Compilar y publicar la actualización
9. Preparar o reparar el entorno (descarga lo que falte)

También sin menú: `compilar.bat compilar`, `instalar`, `todo`, `emulador`, `probar`, `pruebas` o `publicar`.

La primera vez, ejecuta la opción 9 (o cualquier otra: si falta el entorno, se prepara solo).

### Compilar a mano con Gradle

Con un JDK 17 y el SDK de Android configurados (`ANDROID_HOME` o `local.properties` con `sdk.dir`):

```bash
gradle assembleDebug        # versión de pruebas: com.xebolax.lector.pruebas, se instala junto a la normal
gradle assembleRelease      # versión de publicación (solo arm64-v8a y armeabi-v7a)
```

El proyecto no incluye el *wrapper* de Gradle; usa Gradle 8.11.1 (el de `entorno\gradle` o el tuyo).

El servidor de actualizaciones se puede cambiar al compilar:

```bash
gradle assembleRelease -PurlActualizaciones=https://tu-servidor/LectorUniversal
```

### Firma

La primera compilación de publicación crea la firma en `firma\` (`lector.jks` y `firma.properties`). **Guárdala en un sitio seguro y no la subas nunca**: sin ella no se puede actualizar la app instalada sin desinstalarla. `firma\`, `*.jks` y `*.keystore` están en `.gitignore`.

Si no hay firma propia, la versión de publicación se firma con la clave de depuración.

### Pruebas

- **En el PC** (JVM): `gradle testDebugUnitTest`. Ojo: en estas pruebas `org.json` es un sustituto que devuelve valores vacíos; lo que usa JSON se prueba en el dispositivo.
- **En el emulador o el móvil**: `gradle connectedDebugAndroidTest`.
  - `FormatosTest` abre un libro de muestra de cada formato (`app/src/androidTest/assets/muestras`).
  - `AccesibilidadTest` recorre todas las pantallas con **TalkBack activo**, pone el foco en cada elemento (TalkBack lo lee de verdad), prueba los deslizadores y pasa el [Accessibility Test Framework](https://github.com/google/Accessibility-Test-Framework-for-Android). Activa TalkBack antes de lanzarla y revisa `adb logcat` (etiquetas `Recorrido` y `ATF`).
  - Con dos dispositivos conectados, indica cuál con `ANDROID_SERIAL=emulator-5554`.
- Las muestras de prueba se guardan byte a byte (`.gitattributes`); no las abras y guardes con un editor.

### Publicar una versión

La app busca versiones en `https://xebolax.synology.me/LectorUniversal` (`version.json` y `LectorUniversal_v<versión>.apk`).

1. Sube `versionCode` (en 1) y `versionName` en `app/build.gradle.kts`.
2. Añade la sección `== <versión>` con las novedades al principio de `NOVEDADES.txt` (su texto es el que muestra la app al ofrecer la actualización).
3. Si cambiaste algún lector de formatos, sube `VERSION` en `formatos/CacheContenido.kt` para que los libros se vuelvan a convertir.
4. `compilar.bat` → opción 8. Deja el APK y `version.json` en `dist\` y los copia a la carpeta sincronizada con el servidor.

### Estructura del código

Código en Kotlin, en `app/src/main/java/com/xebolax/lector/`, con identificadores y comentarios en español:

| Paquete | Qué contiene |
|---|---|
| `modelo/` | El documento común (bloques, índice, páginas), audiolibros, frases y textos. |
| `formatos/` | Un lector por formato (`LectorEpub`, `LectorPdf`, `LectorDaisy`…), reconocimiento del formato, HTML → bloques y caché de libros convertidos. |
| `lectura/` | Pantalla de lectura, navegación por elementos y controles ajustables para TalkBack. |
| `voz/` | Lectura en voz alta (`LectorVoz`, `ServicioVoz`), pronunciación y exportar a audio. |
| `audio/` | Reproductor común (`ControlAudio`, `ServicioAudio` con Media3), su pantalla y el **mini reproductor** (`Sonando`). |
| `biblioteca/`, `datos/` | Añadir archivos y carpetas; base de datos de la biblioteca y ajustes. |
| `catalogo/` | Catálogos OPDS y bibliotecas web. |
| `podcast/` | Sección Podcasts (base de datos propia `podcasts.db`). |
| `rss/` | Sección Noticias (base de datos propia `rss.db`), artículo completo y sincronización. |
| `radio/` | Sección Radio: cliente de radio-browser.info, base de datos `radio.db`, reproductor en directo, grabación y despertador. |
| `ia/`, `ocr/` | Inteligencia artificial y reconocimiento de texto. |
| `sincronizacion/`, `actualizaciones/` | Sincronización WebDAV y actualizaciones de la app. |
| `ui/` | Ventana principal, secciones y menú lateral, ajustes, acciones de TalkBack, foco y ayudantes comunes. |

Pruebas en `app/src/test` (JVM) y `app/src/androidTest` (dispositivo). Herramientas de Windows en `herramientas/` (preparar el entorno y publicar).

### Normas del proyecto

Todo cambio sigue dos listas de comprobación, y si chocan, gana la accesibilidad:

- **Accesibilidad** (TalkBack y baja visión): nombre en cada control, filas de una sola parada, acciones de TalkBack por zona (`ui/AccionesTalkBack.kt`), encabezados, foco que vuelve al cerrar (`ui/MemoriaFoco.kt`), espera con Cancelar (`ui/Espera.kt`), 48 dp, contraste y texto al 200 %.
- **Calidad Android** (Core app quality y Material 3): Atrás estándar, de borde a borde, nada lento en el hilo principal, audio con foco y Media3, permisos en contexto, cadenas en recursos (es y en).
- **Coherencia**: cada sección funciona igual; Ajustes solo en el menú lateral; los textos según la sección (libro, artículo, episodio, emisora); las acciones cambian según el estado («Quitar de favoritas» si ya lo es).
- Sangría con tabuladores; finales de línea LF (los `.bat` y `.ps1`, CRLF).

### Traducir la app

Copia `app/src/main/res/values/strings.xml` y `arrays.xml` a `app/src/main/res/values-XX/` (XX = código del idioma), traduce los textos (no los nombres de las claves ni los `%1$s`) y añade el idioma a `idiomas_nombres` e `idiomas_valores` en `arrays.xml`. Para el manual, copia `assets/manual-es.html` a `assets/manual-XX.html`.

---

## Créditos y licencias de terceros

- [AndroidX, Media3 y Material Components](https://developer.android.com/) (Apache 2.0)
- [Jsoup](https://jsoup.org/) (MIT), [CommonMark Java](https://github.com/commonmark/commonmark-java) (BSD 2), [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) (Apache 2.0)
- ML Kit (reconocimiento de texto e idioma, IA del móvil) y LiteRT-LM, de Google
- Directorio de emisoras: [radio-browser.info](https://www.radio-browser.info/), comunitario y libre
- Sonidos de marcador y nota: [Paperback](https://github.com/trypsynth/paperback), de Quin Gillespie (MIT)
- Ideas de funciones: Paperback, AntennaPod, RadioDroid, Feeder, Read You, Readrops y Capy Reader. Su código no se ha copiado: las secciones de Podcasts y Radio están escritas desde cero.
- Iconos: Material Symbols (Apache 2.0)

La licencia del propio proyecto la decidirá su autor antes de publicarlo.

---

## English summary

**Lector Universal** is an Android reader built for blind and low-vision people who use TalkBack. It opens EPUB, PDF (including scanned ones, with offline OCR), Word, OpenDocument, RTF, HTML, Markdown, FB2, Kindle, CHM, PowerPoint, Excel, comics, DAISY 2.02/3 and M4B/MP3 audiobooks; reads them aloud with any installed voice; and includes a podcast client, an RSS reader and, since 1.0.8, an internet radio with 50,000+ stations from radio-browser.info, with recording and a radio alarm clock. A mini player above the tabs keeps what is playing at hand in every section.

Building on Windows: run `compilar.bat` (it downloads a portable JDK, Gradle, Android SDK and a TalkBack-enabled emulator into `entorno\`). Elsewhere: JDK 17 + Gradle 8.11.1 + Android SDK 35, then `gradle assembleDebug`. Tests: `gradle testDebugUnitTest` and `gradle connectedDebugAndroidTest` (run `AccesibilidadTest` with TalkBack on). The user interface is available in Spanish and English; source code identifiers and comments are in Spanish.

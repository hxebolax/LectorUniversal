# Lector Universal para Android

Lector de libros, documentos y audiolibros pensado para usarse con **TalkBack**:
EPUB, PDF, Word, OpenDocument, RTF, texto, HTML, Markdown, FictionBook, Kindle
(MOBI/AZW3), CHM, PowerPoint, Excel, CSV, **DAISY 2.02 y 3** y audiolibros
**M4B/MP3** con capítulos. En español, traducible (ya incluye inglés).

El manual de uso completo está dentro de la app (menú lateral, Manual de uso)
y en `app/src/main/assets/manual-es.html`.

## Compilar

Todo lo necesario está dentro de esta carpeta (no se instala nada en Windows):

- `entorno\jdk`: Java 17
- `entorno\gradle`: Gradle 8.11.1
- `entorno\android-sdk`: SDK de Android 35, emulador e imagen de sistema
- `entorno\avd\pruebas`: máquina virtual de pruebas (Pixel 6, Android 15, con TalkBack)

Haz doble clic en **`compilar.bat`** y elige una opción:

1. Compilar el APK → `LectorUniversal.apk`
2. Compilar e instalar en el móvil conectado por USB (depuración USB activada)
3. Instalar el APK ya compilado
4. Ver móviles conectados
5. Abrir la máquina virtual de pruebas
6. Compilar, abrir el emulador, instalar y copiar libros de ejemplo (`pruebas\libros`)
7. Pasar las pruebas automáticas: en el PC y, con emulador o móvil, las de
   todos los formatos (abre libros de muestra de cada formato)
8. Compilar y publicar la actualización
9. Preparar o reparar el entorno (si falta algo, lo descarga)

También sin menú: `compilar.bat compilar`, `instalar`, `todo`, `emulador`,
`probar`, `pruebas` o `publicar`.

La primera compilación crea la firma en `firma\` (`lector.jks` y
`firma.properties`). **Guárdala bien**: sin ella no se puede actualizar la app
instalada sin desinstalarla.

## Publicar una versión nueva (actualizaciones)

La app busca versiones nuevas en `https://xebolax.synology.me/LectorUniversal`
(`version.json` + `LectorUniversal_v<versión>.apk`). La carpeta local
sincronizada con ese servidor es `C:\Users\xebol\SynologyDrive\web\LectorUniversal`.

1. Sube `versionCode` (en 1) y `versionName` en `app\build.gradle.kts`.
2. Añade la sección `== <versión>` con las novedades en `NOVEDADES.txt`.
3. Si cambiaste algún lector de formatos, sube `VERSION` en
   `app\src\main\java\com\xebolax\lector\formatos\CacheContenido.kt` (así los
   libros se vuelven a convertir).
4. `compilar.bat` → opción 8. Deja el APK y `version.json` en `dist\` y los
   copia a la carpeta de Synology Drive, que los sube al servidor.

La app comprueba que el APK descargado es el publicado (tamaño y SHA-256), que
es esta app, más nueva y firmada con la misma clave. La dirección del servidor
se puede cambiar en Ajustes > General.

## Traducir a otro idioma

Copia `app\src\main\res\values\strings.xml` y `arrays.xml` a
`app\src\main\res\values-XX\` (XX = código del idioma: `fr`, `pt`, `it`...),
traduce los textos (no los nombres ni los `%1$s`) y añade el idioma a la lista
`idiomas_nombres`/`idiomas_valores` de `arrays.xml`. Para el manual, copia
`assets\manual-es.html` a `assets\manual-XX.html`.

## Estructura del código

`app/src/main/java/com/xebolax/lector/`:

- `modelo/`: el documento común (bloques, índice, páginas), audiolibros,
  frases (`Segmentador`) y textos.
- `formatos/`: un lector por formato (`LectorEpub`, `LectorPdf`, `LectorDocx`,
  `LectorDaisy`...), `Formatos.kt` (reconocer el formato), `ConversorHtml`
  (HTML → bloques) y `CacheContenido` (caché de libros convertidos).
- `lectura/`: pantalla de lectura (`LectorActivity`), navegación por elementos,
  adaptador de párrafos y el control ajustable para TalkBack.
- `voz/`: lectura en voz alta (`LectorVoz`, `ServicioVoz`) y pronunciación.
- `audio/`: reproductor de audiolibros y DAISY (`ControlAudio`, `ServicioAudio`).
- `biblioteca/`: añadir archivos y carpetas.
- `datos/`: base de datos (libros, carpetas, marcadores, estadísticas,
  pronunciación) y ajustes.
- `ui/`: ventana principal, listas, ajustes, estadísticas...
- `actualizaciones/`: búsqueda e instalación de versiones nuevas.

## Accesibilidad: cómo está hecho

- Un párrafo por fila (RecyclerView): deslizar pasa de párrafo, "Leer desde el
  siguiente elemento" lee todo el libro y dentro de cada párrafo funcionan
  caracteres, palabras y líneas. No se usa `contentDescription` en el texto.
- Encabezados reales (`setAccessibilityHeading`) con su nivel como papel
  ("Encabezado de nivel 2"); listas, tablas, citas e imágenes también dicen su
  papel; página, marcador y nota van en el estado (después del texto).
- La posición es el foco de TalkBack; al abrir o saltar se pone el foco en el
  párrafo de destino.
- Selector de elemento, velocidad y botón de reproducir ajustables deslizando
  arriba/abajo (truco de Paperback: se anuncian como control deslizante).
- Acciones de TalkBack en cada párrafo, libro, carpeta y marcador.
- La voz propia guarda la posición, sigue con la pantalla apagada y recibe los
  botones de los auriculares (mantiene una pista de audio en silencio para que
  Android le mande los botones).

## Créditos

- Sonidos de marcador y nota: Paperback (Quin Gillespie, licencia MIT),
  `adjuntos\paperback`.
- PDFBox-Android (Apache 2.0), Jsoup (MIT), CommonMark (BSD), AndroidX y Media3 (Apache 2.0).
- Ideas de navegación y formatos: Paperback y el Visor Universal de Windows.

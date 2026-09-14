# Video Downloader (Android)

App Android nativa que usa [yt-dlp](https://github.com/yt-dlp/yt-dlp) (vía la librería
`youtubedl-android`) para analizar un enlace de video y descargarlo en la calidad
disponible que elijas. Compila el APK automáticamente por GitHub Actions, sin necesidad
de Android Studio.

## Cómo compilar el APK (desde el celular, sin PC)

1. Crea un repositorio nuevo en tu cuenta de GitHub, por ejemplo `video-downloader-app`.
2. Sube todo el contenido de esta carpeta a ese repo (puedes usar la app de GitHub para
   Android, o la web de GitHub con "Add file → Upload files").
3. Ve a la pestaña **Actions** del repo. El workflow "Build APK" se ejecuta solo al
   hacer push a `main`, o puedes lanzarlo manualmente con el botón "Run workflow".
4. Cuando termine (unos 3-5 minutos), entra al run finalizado y descarga el artefacto
   **video-downloader-debug-apk** — es un .zip que contiene el `app-debug.apk`.
5. Instala el APK en tu teléfono (activa "Instalar apps de fuentes desconocidas" si te
   lo pide).

## Uso

- Pega un enlace de video y pulsa "Analizar enlace", o comparte el link directamente
  desde el navegador usando el botón "Compartir" → "Video Downloader".
- Elige la calidad de la lista de formatos disponibles y pulsa "Descargar".
- Los videos se guardan en `Movies/VideoDownloader` dentro del almacenamiento de la app.

## Cómo funciona

- `YoutubeDL.getInstance().init()` descarga y prepara el binario de yt-dlp la primera vez.
- `getInfo(url)` extrae los formatos disponibles (resolución, extensión, id).
- `execute(request)` descarga el formato elegido con progreso en tiempo real.
- Soporta cualquier sitio que yt-dlp reconozca (YouTube, Vimeo, Twitter/X, Reddit, etc.).

## Importante — uso legal

- Esta app **no funciona** con plataformas de streaming con DRM (Netflix, Disney+,
  Prime Video, etc.); esas usan cifrado diseñado para impedir la descarga y evitarlo
  viola sus términos de servicio y, en varios países, la ley.
- Úsala solo para contenido que tengas derecho a descargar (tuyo, de dominio público,
  con licencia libre, o donde el propio sitio lo permita). Descargar contenido con
  derechos de autor sin permiso del titular puede ser ilegal según la legislación de
  tu país.

## Próximos pasos sugeridos

- Firmar el APK como release (en vez de debug) para publicarlo o instalarlo sin avisos.
- Agregar una cola de descargas con notificaciones persistentes.
- Detectar automáticamente enlaces al pegarlos desde el portapapeles.

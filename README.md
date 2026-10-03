# Descarga Videos (Android)

App Android que usa yt-dlp (vía `youtubedl-android`) con ffmpeg y aria2c para descargar videos o audio de cientos de sitios.

## Funciones
- Aparece en el menú **Compartir** de cualquier app (Facebook, TikTok, navegador...).
- Calidad: mejor disponible, 720p, 480p o **solo audio MP3**.
- Une audio y video en MP4 automáticamente.
- Descarga con varias conexiones (aria2c) para mayor velocidad.
- Guarda en **Descargas/DescargaVideos** (visible en la galería y el gestor de archivos).
- Botón **Actualizar motor** para cuando un sitio cambia y deja de funcionar.

## Instalar
Cada push a `main` compila el APK en GitHub Actions y lo publica en
**Releases → latest → DescargaVideos.apk**. Se firma siempre con la misma clave,
así que las nuevas versiones se instalan encima sin desinstalar.

## Límites
No funciona con sitios protegidos con DRM (Netflix, Disney+, Spotify, etc.).
Úsala para contenido propio, libre o de uso personal.

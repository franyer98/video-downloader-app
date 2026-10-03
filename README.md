# Descarga Videos (Android)

App Android que usa yt-dlp (vía `youtubedl-android`) con ffmpeg y aria2c para descargar videos o audio de cientos de sitios.

## Funciones
- Aparece en el menú **Compartir** de cualquier app (Facebook, TikTok, navegador...).
- **10 descargas simultáneas en segundo plano** (servicio con notificación): siguen aunque cierres la app o apagues la pantalla; el resto espera en cola.
- Cada video baja 8 fragmentos en paralelo (HLS/DASH) y los archivos directos usan aria2c con 16 conexiones.
- **Miniatura** de cada video en la lista y en la notificación.
- **Peso del archivo y velocidad** mientras descarga.
- **Se actualiza sola**: al abrirla busca versión nueva, la baja en segundo plano y pide un toque para instalar. El motor yt-dlp se actualiza solo una vez al día.
- **Elige la calidad** de cada video: muestra las resoluciones reales disponibles con su peso aproximado, más la opción de solo audio MP3.
- Une audio y video en MP4 automáticamente.
- Descarga con varias conexiones (aria2c) para mayor velocidad.
- Videos en **H.264 + AAC** con índice al inicio: abren al instante y muestran miniatura en la galería.
- **Optimiza para adelantar**: si el sitio entrega el video con pocos puntos de salto, lo reescribe con uno por segundo usando el chip de video del teléfono.
- Guarda videos en **Películas/DescargaVideos** (galería) y audio en **Música/DescargaVideos**.
- **Carpeta privada** 🔒: con el interruptor activado, los videos se guardan dentro de la app (no salen en la galería ni en el gestor de archivos). Se abre con huella o PIN, tiene reproductor propio, bloquea capturas y se cierra al salir. Permite **importar videos de la galería** (se copian y se borra el original).
- **Sin duplicados**: si pegas o compartes un enlace de un video ya descargado o en descarga, no lo deja bajar otra vez (compara el enlace y el id del video en el sitio).
- Botón **Actualizar motor** para cuando un sitio cambia y deja de funcionar.

## Instalar
Cada push a `main` compila el APK en GitHub Actions y lo publica en
**Releases → latest → DescargaVideos.apk**. Se firma siempre con la misma clave,
así que las nuevas versiones se instalan encima sin desinstalar.

## Límites
No funciona con sitios protegidos con DRM (Netflix, Disney+, Spotify, etc.).
Úsala para contenido propio, libre o de uso personal.

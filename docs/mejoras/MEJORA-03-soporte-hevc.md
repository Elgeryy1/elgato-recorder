# MEJORA-03 — Soporte HEVC (H.265) en VideoRecorder

| Campo | Valor |
|---|---|
| Prioridad | Alta |
| Tipo | Backend |
| Esfuerzo estimado | 2 horas |

---

## Descripción

Añadir opción H.265/HEVC al `VideoRecorder`. HEVC reduce el tamaño del archivo un ~40% respecto a H.264 con la misma calidad perceptual. Todos los smartphones Android modernos tienen encoder HEVC hardware.

---

## Implementación

### Paso 1 — Añadir parámetro de codec al constructor

```kotlin
enum class VideoCodec { H264, HEVC }

class VideoRecorder(
    private val context: Context,
    val width: Int,
    val height: Int,
    val fps: Int = 30,
    private val codec: VideoCodec = VideoCodec.H264,
    private val outputPath: String? = null,
) {
    private val mimeType = when (codec) {
        VideoCodec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
        VideoCodec.HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
    }
    private val bitrateMultiplier = when (codec) {
        VideoCodec.H264 -> 1.0f
        VideoCodec.HEVC -> 0.6f  // HEVC necesita menos bitrate para misma calidad
    }
```

### Paso 2 — Verificar soporte en el dispositivo

```kotlin
companion object {
    fun isHEVCSupported(): Boolean {
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
            codec.isEncoder &&
            codec.supportedTypes.any {
                it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
            }
        }
    }
}
```

### Paso 3 — Configurar el formato para HEVC

```kotlin
private fun initVideoEncoder() {
    val bitrate = (VIDEO_BITRATE * bitrateMultiplier).toInt()
    val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        // Para HEVC: perfil Main
        if (codec == VideoCodec.HEVC) {
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
        }
    }
    encoder = MediaCodec.createEncoderByType(mimeType).also {
        it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        it.start()
    }
}
```

### Paso 4 — Exponer selector en el ViewModel y la UI

```kotlin
// En MainViewModel:
private val _selectedCodec = MutableStateFlow(VideoCodec.H264)
val selectedCodec: StateFlow<VideoCodec> = _selectedCodec.asStateFlow()

fun selectCodec(codec: VideoCodec) {
    if (_isRecording.value) return
    _selectedCodec.value = codec
}

// En startVideoRecording():
val recorder = VideoRecorder(
    context    = requireContext(),
    width      = res.width,
    height     = res.height,
    fps        = res.fps,
    codec      = vm.selectedCodec.value,
    outputPath = outputPath,
)
```

```kotlin
// En MainActivity.kt — añadir al panel UI:
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    listOf(VideoCodec.H264, VideoCodec.HEVC).forEach { c ->
        val isEnabled = c == VideoCodec.H264 || VideoRecorder.isHEVCSupported()
        OutlinedButton(
            onClick = { vm.selectCodec(c) },
            enabled = isEnabled && !isRecording,
            colors = if (selectedCodec == c) ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                     else ButtonDefaults.outlinedButtonColors()
        ) {
            Text(c.name, fontSize = 11.sp)
        }
    }
}
```

---

## Tests sugeridos

- Grabar con H.264 y H.265 el mismo contenido → el archivo H.265 debe ser ~40% más pequeño.
- `mediainfo video_hevc.mp4` → debe mostrar "HEVC" como codec de vídeo.
- Reproducir el archivo H.265 en VLC y en la galería del móvil → debe reproducirse sin problemas.
- En dispositivo sin HEVC encoder → la opción debe estar deshabilitada.

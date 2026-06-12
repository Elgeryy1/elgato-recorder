# BUG-02 — VideoRecorder no tiene pista de audio (grabaciones sin sonido)

| Campo | Valor |
|---|---|
| Severidad | ALTO (blocker para release) |
| Archivo | `VideoRecorder.kt` |
| Esfuerzo estimado | 1-2 días |

---

## Descripción

`VideoRecorder` solo codifica vídeo H.264. No se agrega ninguna pista de audio al `MediaMuxer`. Todos los vídeos grabados con esta app **no tienen sonido**.

Esto es un defecto crítico para el usuario final: el HD60 X captura tanto vídeo como audio HDMI, y el usuario espera que ambos sean grabados.

---

## Causa raíz

La pipeline de vídeo fue implementada como MVP funcional sin incluir audio. El `MediaMuxer` solo tiene `addTrack()` para vídeo y nunca se llama para audio.

---

## Solución paso a paso

### Paso 1 — Añadir `AudioRecord` y encoder AAC a `VideoRecorder`

```kotlin
class VideoRecorder(
    private val context: Context,
    val width: Int,
    val height: Int,
    val fps: Int = 30,
) {
    companion object {
        private const val TAG = "ElgatoRecorder"
        private const val VIDEO_BITRATE = 10_000_000
        private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_BITRATE = 192_000
        private const val AUDIO_CHANNELS = 2
        private const val MAX_QUEUE = 8
    }

    // ... campos existentes ...

    // ── Audio (nuevo) ─────────────────────────────────────────────────────
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: android.media.AudioRecord? = null
    private var audioTrack = -1
    private val audioThread = HandlerThread("AudioEncoder").also { it.start() }
    private val audioHandler = Handler(audioThread.looper)
    private val audioStartTimeUs = java.util.concurrent.atomic.AtomicLong(0L)
    private val isCapturingAudio = java.util.concurrent.atomic.AtomicBoolean(false)

    // ...

    fun start() {
        if (isRecording.get()) return
        encHandler.post {
            try {
                initMuxer()
                initVideoEncoder()
                initAudioEncoder()  // ← AÑADIR
                isRecording.set(true)
                startTimeUs = 0L
                startAudioCapture()  // ← AÑADIR
                Log.d(TAG, "VideoRecorder: iniciado ${width}x${height} @ ${fps}fps")
                onStarted()
            } catch (e: Exception) {
                Log.e(TAG, "VideoRecorder: error al iniciar", e)
                cleanup()
                onError("No se pudo iniciar grabación: ${e.message}")
            }
        }
    }
```

### Paso 2 — Inicializar el encoder AAC

```kotlin
private fun initAudioEncoder() {
    val format = MediaFormat.createAudioFormat(AUDIO_MIME, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
    }
    audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME).also {
        it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        it.start()
    }
    Log.d(TAG, "VideoRecorder: encoder AAC iniciado")
}
```

### Paso 3 — Capturar audio con `AudioRecord`

```kotlin
private fun startAudioCapture() {
    val minBufSize = android.media.AudioRecord.getMinBufferSize(
        AUDIO_SAMPLE_RATE,
        android.media.AudioFormat.CHANNEL_IN_STEREO,
        android.media.AudioFormat.ENCODING_PCM_16BIT
    )
    val bufSize = maxOf(minBufSize, 8192)
    audioRecord = android.media.AudioRecord(
        android.media.MediaRecorder.AudioSource.MIC,
        AUDIO_SAMPLE_RATE,
        android.media.AudioFormat.CHANNEL_IN_STEREO,
        android.media.AudioFormat.ENCODING_PCM_16BIT,
        bufSize
    )
    audioRecord?.startRecording()
    isCapturingAudio.set(true)
    audioStartTimeUs.set(0L)

    // Hilo de captura continua
    audioHandler.post {
        val buf = ShortArray(bufSize / 2)
        while (isCapturingAudio.get()) {
            val read = audioRecord?.read(buf, 0, buf.size) ?: break
            if (read > 0) feedAudioFrame(buf, read)
        }
    }
}

private fun feedAudioFrame(samples: ShortArray, count: Int) {
    val enc = audioEncoder ?: return
    val bytes = ByteArray(count * 2)
    for (i in 0 until count) {
        bytes[i * 2]     = (samples[i].toInt() and 0xFF).toByte()
        bytes[i * 2 + 1] = (samples[i].toInt() shr 8).toByte()
    }

    val inputIdx = enc.dequeueInputBuffer(5_000)
    if (inputIdx < 0) return
    val buf = enc.getInputBuffer(inputIdx) ?: return
    buf.clear()
    buf.put(bytes, 0, bytes.size)

    if (audioStartTimeUs.get() == 0L) audioStartTimeUs.set(System.nanoTime() / 1000)
    val pts = System.nanoTime() / 1000 - audioStartTimeUs.get()
    enc.queueInputBuffer(inputIdx, 0, bytes.size, pts, 0)
    drainAudioEncoder(false)
}
```

### Paso 4 — Drenar el encoder AAC

```kotlin
private val audioBufferInfo = MediaCodec.BufferInfo()

private fun drainAudioEncoder(endOfStream: Boolean) {
    val enc = audioEncoder ?: return
    val mux = muxer ?: return

    if (endOfStream) enc.signalEndOfInputStream()

    while (true) {
        val idx = enc.dequeueOutputBuffer(audioBufferInfo, if (endOfStream) 20_000 else 0)
        when {
            idx == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!endOfStream) break }
            idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                if (!muxerStarted.get()) {
                    // Esperar a que el vídeo también haya iniciado el muxer
                    // Iniciar el muxer solo cuando ambas pistas estén listas
                }
                audioTrack = mux.addTrack(enc.outputFormat)
                if (videoTrack >= 0) {
                    mux.start()
                    muxerStarted.set(true)
                }
            }
            idx >= 0 -> {
                val buf = enc.getOutputBuffer(idx)
                if (buf != null && audioBufferInfo.size > 0
                    && audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    && muxerStarted.get()) {
                    mux.writeSampleData(audioTrack, buf, audioBufferInfo)
                }
                enc.releaseOutputBuffer(idx, false)
                if (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            else -> break
        }
    }
}
```

### Paso 5 — Detener audio en `stop()` y `cleanup()`

```kotlin
fun stop() {
    if (!isRecording.getAndSet(false)) return
    isCapturingAudio.set(false)
    encHandler.post {
        try {
            drainEncoder(true)
            drainAudioEncoder(true)
        } catch (e: Exception) { Log.w(TAG, "Drain error", e) }
        val path = outputPath
        cleanup()
        if (path.isNotEmpty()) onComplete(path) else onError("Ruta vacía")
    }
}

private fun cleanup() {
    isCapturingAudio.set(false)
    runCatching { audioRecord?.stop() }
    runCatching { audioRecord?.release() }
    // ... resto igual, añadir:
    runCatching { audioEncoder?.stop() }
    runCatching { audioEncoder?.release() }
    audioRecord = null
    audioEncoder = null
    audioTrack = -1
}
```

> **Importante:** El inicio del `MediaMuxer` (`mux.start()`) debe ocurrir **después** de que tanto la pista de vídeo (`videoTrack`) como la de audio (`audioTrack`) hayan sido añadidas. Coordinar con `AtomicInteger` o similar que cuente cuántas pistas están listas.

---

## Tests sugeridos

- Grabar 10 segundos → abrir en VLC o reproductor → debe tener audio.
- Abrir con `mediainfo` → debe mostrar Audio Track: AAC 48kHz Stereo.
- Grabar en silencio → el audio debe estar presente aunque silencioso.
- Grabar con sonido fuerte → no debe haber distorsión notable.

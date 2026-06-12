# BUG-09 — Timestamps inconsistentes por frames descartados en la cola

| Campo | Valor |
|---|---|
| Severidad | MEDIO |
| Archivo | `VideoRecorder.kt` |
| Método | `feedNv21Frame()`, `encodeNextFrame()` |
| Esfuerzo estimado | 2 horas |

---

## Descripción

Cuando la cola de frames está llena, se descarta el frame más antiguo:

```kotlin
fun feedNv21Frame(data: ByteArray) {
    if (!isRecording.get()) return
    val copy = data.copyOf()
    if (!frameQueue.offer(copy)) {
        frameQueue.poll()   // ← descarta el frame más antiguo
        frameQueue.offer(copy)
    }
    encHandler.post { encodeNextFrame() }
}
```

El problema es que los timestamps PTS se calculan en el momento de **encoding**, no en el momento de **captura**:

```kotlin
private fun encodeNextFrame() {
    // ...
    if (startTimeUs == 0L) startTimeUs = System.nanoTime() / 1000
    val pts = System.nanoTime() / 1000 - startTimeUs  // ← timestamp al momento de encoding
    enc.queueInputBuffer(inputIdx, 0, ySize + uvOut.size, pts, 0)
}
```

Si el encoder va lento y se descartan frames, el tiempo transcurrido entre PTS consecutivos puede ser mayor que 1/fps, causando saltos en el vídeo.

---

## Solución paso a paso

### Paso 1 — Guardar el timestamp con el frame en la cola

Crear un wrapper que incluya el timestamp de captura:

```kotlin
data class TimestampedFrame(val data: ByteArray, val captureTimeUs: Long)

// En VideoRecorder:
private val frameQueue = ArrayBlockingQueue<TimestampedFrame>(MAX_QUEUE)

fun feedNv21Frame(data: ByteArray) {
    if (!isRecording.get()) return
    val captureTime = System.nanoTime() / 1000
    if (startTimeUs == 0L) startTimeUs = captureTime  // primer frame establece el origen

    val frame = TimestampedFrame(data.copyOf(), captureTime)
    if (!frameQueue.offer(frame)) {
        frameQueue.poll()  // descartar el más antiguo
        frameQueue.offer(frame)
    }
    encHandler.post { encodeNextFrame() }
}
```

### Paso 2 — Usar el timestamp del frame al hacer el input buffer

```kotlin
private fun encodeNextFrame() {
    val frame = frameQueue.poll() ?: return
    val enc   = encoder ?: return

    val inputIdx = enc.dequeueInputBuffer(10_000)
    if (inputIdx < 0) return

    val buf = enc.getInputBuffer(inputIdx) ?: return
    buf.clear()

    val ySize = width * height
    buf.put(frame.data, 0, ySize)
    // ... conversión NV21→NV12 ...

    // Usar el timestamp de captura real del frame
    val pts = frame.captureTimeUs - startTimeUs

    enc.queueInputBuffer(inputIdx, 0, ySize + uvSize, pts, 0)
    drainEncoder(false)
}
```

### Paso 3 — Estrategia de descarte más inteligente

En lugar de descartar siempre el frame más antiguo, descartar frames intermedios manteniendo la distribución temporal:

```kotlin
fun feedNv21Frame(data: ByteArray) {
    if (!isRecording.get()) return
    val captureTime = System.nanoTime() / 1000
    if (startTimeUs == 0L) startTimeUs = captureTime

    val frame = TimestampedFrame(data.copyOf(), captureTime)
    if (!frameQueue.offer(frame)) {
        // Cola llena: intentar mantener el frame más reciente
        // Si hay frames en la cola con PTS muy cercano entre sí, descartar uno intermedio
        val oldest = frameQueue.peek()
        if (oldest != null) {
            val gap = captureTime - oldest.captureTimeUs
            if (gap < (1_000_000L / fps) * 2) {
                // Los frames son muy cercanos → descartar el más antiguo
                frameQueue.poll()
            } else {
                // Hay un gap grande → descartar el nuevo (la cola tiene frames importantes)
                Log.w(TAG, "Frame descartado: cola llena, gap=$gap us")
                return
            }
        }
        frameQueue.offer(frame)
    }
    encHandler.post { encodeNextFrame() }
}
```

---

## Tests sugeridos

- Grabar 30s con resolución 1080p60 → analizar con `ffprobe -v quiet -print_format json -show_frames video.mp4 | grep pkt_pts_time` → los PTS deben ser equidistantes (1/60 ≈ 0.0167s).
- Grabar con encoder lento (bajo bitrate) → los PTS del vídeo deben ser correctos incluso si se descartan frames.
- Verificar que el vídeo no tiene saltos o "congelados" de más de 2 frames.

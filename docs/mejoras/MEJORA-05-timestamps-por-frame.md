# MEJORA-05 — Timestamps de captura por frame

| Campo | Valor |
|---|---|
| Prioridad | Media |
| Tipo | Backend |
| Esfuerzo estimado | 2 horas |

---

## Descripción

Asignar el timestamp en el momento de recibir el frame (`feedNv21Frame`) en lugar de en el momento de encoding. Esto elimina los saltos de PTS cuando el encoder va lento o descarta frames.

Ver implementación completa en [BUG-09](../bugs/BUG-09-timestamps-inconsistentes-framequeue.md).

---

## Cambio clave

```kotlin
// ANTES — timestamp en momento de encoding (incorrecto):
fun feedNv21Frame(data: ByteArray) {
    val copy = data.copyOf()
    frameQueue.offer(copy)
}
// En encodeNextFrame():
val pts = System.nanoTime() / 1000 - startTimeUs

// DESPUÉS — timestamp en momento de captura (correcto):
data class TimestampedFrame(val data: ByteArray, val captureTimeUs: Long)

fun feedNv21Frame(data: ByteArray) {
    val captureTime = System.nanoTime() / 1000
    val frame = TimestampedFrame(data.copyOf(), captureTime)
    frameQueue.offer(frame)
}
// En encodeNextFrame():
val pts = frame.captureTimeUs - startTimeUs
```

---

## Tests sugeridos

- Analizar PTS con `ffprobe`: `ffprobe -v quiet -show_frames -of csv output.mp4 | awk -F, '{print $NF}'`
- Los PTS deben ser equidistantes: para 60fps cada PTS debe diferir ~16667 µs del anterior.

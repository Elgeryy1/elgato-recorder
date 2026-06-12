# BUG-07 — Conversión NV21→NV12 con posible byte perdido (off-by-one)

| Campo | Valor |
|---|---|
| Severidad | MEDIO |
| Archivo | `VideoRecorder.kt` |
| Líneas afectadas | 119-126 |
| Esfuerzo estimado | 30 minutos |

---

## Descripción

```kotlin
val uvIn  = frame
val uvOut = ByteArray(frame.size - ySize)
var i = 0
while (i < uvOut.size - 1) {  // ← BUG: debería ser < uvOut.size
    uvOut[i]     = uvIn[ySize + i + 1]  // U
    uvOut[i + 1] = uvIn[ySize + i]      // V
    i += 2
}
```

El loop usa `while (i < uvOut.size - 1)`. Si `uvOut.size` es impar, el último byte nunca se convierte. Para resoluciones estándar (1920×1080, 1280×720) `uvOut.size` es siempre par, por lo que en la práctica no falla. Pero:

- El loop deja el último byte de `uvOut` con el valor 0 si `uvOut.size` es par y el último par `(i, i+1)` coincide con `uvOut.size - 2`.
- Si `uvOut.size` es impar, el **último byte queda como 0** (basura en el plano UV).

Además, la condición correcta para procesar todos los pares debe ser `i + 1 < uvOut.size`, no `i < uvOut.size - 1` (aunque son equivalentes).

---

## Solución paso a paso

### Opción A — Fix mínimo: corregir la condición del loop

```kotlin
var i = 0
while (i + 1 < uvOut.size) {  // procesar mientras haya al menos 2 bytes disponibles
    uvOut[i]     = uvIn[ySize + i + 1]  // U (de V en NV21)
    uvOut[i + 1] = uvIn[ySize + i]      // V (de U en NV21)
    i += 2
}
// Si uvOut.size es impar, el último byte queda como está (no hay par que intercambiar)
```

### Opción B — Implementación más clara y segura

```kotlin
private fun encodeNextFrame() {
    val frame = frameQueue.poll() ?: return
    val enc = encoder ?: return

    val inputIdx = enc.dequeueInputBuffer(10_000)
    if (inputIdx < 0) return
    val buf = enc.getInputBuffer(inputIdx) ?: return
    buf.clear()

    val ySize = width * height
    val uvSize = frame.size - ySize

    // Plano Y: copiar directamente
    buf.put(frame, 0, ySize)

    // Plano UV: NV21 (VU intercalado) → NV12 (UV intercalado)
    // NV21: [V0, U0, V1, U1, ...]
    // NV12: [U0, V0, U1, V1, ...]
    val uvNv12 = ByteArray(uvSize)
    var j = 0
    while (j + 1 < uvSize) {
        uvNv12[j]     = frame[ySize + j + 1]  // U de NV21[j+1]
        uvNv12[j + 1] = frame[ySize + j]      // V de NV21[j]
        j += 2
    }
    buf.put(uvNv12)

    if (startTimeUs == 0L) startTimeUs = System.nanoTime() / 1000
    val pts = System.nanoTime() / 1000 - startTimeUs

    enc.queueInputBuffer(inputIdx, 0, ySize + uvSize, pts, 0)
    drainEncoder(false)
}
```

---

## Tests sugeridos

- Grabar a 1920×1080 → el color en el vídeo debe ser correcto (sin tinte violeta/verde).
- Grabar a 1280×720 → idem.
- Si se prueba con una resolución no estándar con ancho impar → el último byte de UV no debe causar artefacto visible.
- Comparar frame a frame el color de un objeto rojo → debe aparecer rojo, no cian.

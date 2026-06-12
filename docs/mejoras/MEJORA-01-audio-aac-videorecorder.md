# MEJORA-01 — Audio AAC en VideoRecorder

| Campo | Valor |
|---|---|
| Prioridad | CRÍTICA (blocker para release) |
| Tipo | Backend |
| Esfuerzo estimado | 1-2 días |

---

## Descripción

Implementar captura y codificación de audio AAC en `VideoRecorder` para que las grabaciones tengan sonido. Ver implementación completa en [BUG-02-sin-audio-grabaciones.md](../bugs/BUG-02-sin-audio-grabaciones.md).

---

## Puntos clave de implementación

1. **AudioRecord** con `AudioSource.MIC`, 48kHz estéreo.
2. **MediaCodec AAC** (`audio/mp4a-latm`), 192kbps, LC profile.
3. **Sincronización de tracks**: el `MediaMuxer.start()` solo se llama cuando AMBAS pistas (vídeo y audio) han emitido `INFO_OUTPUT_FORMAT_CHANGED`.
4. **Timestamps compartidos**: tanto el encoder de vídeo como el de audio usan `System.nanoTime()` con el mismo origen para sincronización.
5. **Parada limpia**: `signalEndOfInputStream()` en el encoder de vídeo + `audioRecord.stop()` + drenar ambos encoders antes de `muxer.stop()`.

---

## Estructura de hilos recomendada

```
[AUSBC callback thread] → feedNv21Frame() → frameQueue → [encThread: encodeNextFrame()]
[audioRecord thread]    → PCM read loop  → audioQueue → [audioThread: encodeAudioFrame()]
                                                                         ↓
                                                               [MediaMuxer writeSampleData]
```

> El `MediaMuxer` **no es thread-safe**. Todas las llamadas a `writeSampleData()` deben sincronizarse con un `synchronized(muxer!!)` o ejecutarse desde el mismo hilo.

---

## Tests sugeridos

Ver [BUG-02](../bugs/BUG-02-sin-audio-grabaciones.md#tests-sugeridos).

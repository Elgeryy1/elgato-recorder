# MEJORA-08 — VU meter de audio durante grabación

| Campo | Valor |
|---|---|
| Prioridad | CRÍTICA (requiere MEJORA-01 audio) |
| Tipo | Frontend |
| Esfuerzo estimado | 2 horas |

---

## Descripción

Mostrar un medidor de nivel de audio (VU meter) de 2 canales durante la grabación. Crítico para que el operador sepa si el audio HDMI está llegando.

---

## Implementación

### Paso 1 — Calcular el nivel RMS en `VideoRecorder`

```kotlin
// En VideoRecorder.kt — añadir callback de nivel de audio:
var onAudioLevel: ((Float, Float) -> Unit)? = null  // (left, right) 0..1

private fun feedAudioFrame(samples: ShortArray, count: Int) {
    // ... encoding actual ...

    // Calcular RMS por canal
    var sumL = 0L; var sumR = 0L
    val pairs = count / 2
    for (i in 0 until pairs) {
        sumL += samples[i * 2].toLong() * samples[i * 2]
        sumR += samples[i * 2 + 1].toLong() * samples[i * 2 + 1]
    }
    val rmsL = if (pairs > 0) kotlin.math.sqrt(sumL.toDouble() / pairs).toFloat() / 32768f else 0f
    val rmsR = if (pairs > 0) kotlin.math.sqrt(sumR.toDouble() / pairs).toFloat() / 32768f else 0f
    onAudioLevel?.invoke(rmsL, rmsR)
}
```

### Paso 2 — Exponer el nivel en el ViewModel

```kotlin
private val _audioLevel = MutableStateFlow(Pair(0f, 0f))
val audioLevel: StateFlow<Pair<Float, Float>> = _audioLevel.asStateFlow()

// En startVideoRecording():
recorder.onAudioLevel = { l, r ->
    _audioLevel.value = Pair(l, r)
}
```

### Paso 3 — Composable `VuMeter`

```kotlin
@Composable
fun VuMeter(
    levelL: Float,
    levelR: Float,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        // Canal izquierdo
        VuBar(level = levelL, modifier = Modifier.height(height).width(8.dp))
        // Canal derecho
        VuBar(level = levelR, modifier = Modifier.height(height).width(8.dp))
    }
}

@Composable
private fun VuBar(level: Float, modifier: Modifier = Modifier) {
    val animLevel by animateFloatAsState(targetValue = level, animationSpec = tween(50), label = "vu")

    Box(modifier = modifier.clip(RoundedCornerShape(2.dp)).background(Color(0xFF1A1A1A))) {
        val segments = 20
        val activeSegments = (animLevel * segments).toInt()
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom
        ) {
            repeat(segments) { seg ->
                val idx = segments - 1 - seg
                val isActive = idx < activeSegments
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 0.5.dp)
                        .background(
                            when {
                                !isActive       -> Color(0xFF111111)
                                idx >= 17       -> Color(0xFFEF5350)  // rojo: > -3dB
                                idx >= 13       -> Color(0xFFFFA726)  // naranja: -6dB
                                else            -> Color(0xFF66BB6A)  // verde: normal
                            }
                        )
                )
            }
        }
    }
}

// En ElgatoRecorderScreen:
if (isRecording) {
    val (levelL, levelR) = audioLevel
    VuMeter(
        levelL = levelL,
        levelR = levelR,
        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 60.dp)
    )
}
```

---

## Tests sugeridos

- Grabar con música → las barras del VU meter deben moverse en sincronía con la música.
- Sin señal de audio → las barras deben estar en mínimo (verde bajo).
- Audio saturado → las barras deben llegar a rojo.

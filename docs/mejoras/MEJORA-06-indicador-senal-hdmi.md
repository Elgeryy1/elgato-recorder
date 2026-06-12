# MEJORA-06 — Indicador de señal HDMI activa

| Campo | Valor |
|---|---|
| Prioridad | Alta |
| Tipo | Frontend |
| Esfuerzo estimado | 2 horas |

---

## Descripción

Mostrar claramente si hay una señal HDMI activa conectada al HD60 X. Si no hay señal, mostrar un overlay "Sin señal HDMI" sobre el preview. Actualmente el usuario no sabe si el preview negro es por "sin señal" o por "cámara no iniciada".

---

## Implementación

### Paso 1 — Detectar señal por tamaño de frame

El HD60 X envía frames negros o de baja entropía cuando no hay señal HDMI. Detectar señal comparando la varianza de los frames NV21:

```kotlin
// En ElgatoCameraFragment.kt — añadir detector de señal:
private val frameEnergySamples = ArrayDeque<Int>(maxSize = 10)
private val hasHdmiSignal = java.util.concurrent.atomic.AtomicBoolean(false)

private fun checkFrameHasSignal(yPlane: ByteArray, sampleSize: Int = 1000): Boolean {
    var sum = 0L
    var sumSq = 0L
    val step = maxOf(1, yPlane.size / sampleSize)
    var count = 0
    var i = 0
    while (i < yPlane.size) {
        val v = yPlane[i].toInt() and 0xFF
        sum += v
        sumSq += v.toLong() * v
        count++
        i += step
    }
    if (count == 0) return false
    val mean = sum / count
    val variance = sumSq / count - mean * mean
    return variance > 100  // umbral: frames negros o uniformes tienen varianza ≈ 0
}
```

```kotlin
// En addPreviewDataCallBack:
override fun onPreviewData(data: ByteArray?, format: IPreviewDataCallBack.DataFormat) {
    if (data == null) return
    val ySize = (vm.currentResolution.value?.let { it.width * it.height }) ?: return
    val hasSignal = checkFrameHasSignal(data.copyOfRange(0, minOf(ySize, data.size)))
    if (hasSignal != hasHdmiSignal.get()) {
        hasHdmiSignal.set(hasSignal)
        vm.onHdmiSignalChanged(hasSignal)
    }
    // ...
}
```

### Paso 2 — Exponer el estado en el ViewModel

```kotlin
// En MainViewModel.kt:
private val _hasHdmiSignal = MutableStateFlow(false)
val hasHdmiSignal: StateFlow<Boolean> = _hasHdmiSignal.asStateFlow()

fun onHdmiSignalChanged(hasSignal: Boolean) {
    _hasHdmiSignal.value = hasSignal
}
```

### Paso 3 — Mostrar overlay en la UI

```kotlin
// En ElgatoRecorderScreen:
val hasHdmiSignal by vm.hasHdmiSignal.collectAsState()

Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
    // Preview del Fragment
    AndroidView(...)

    // Overlay "Sin señal HDMI"
    if (appState is AppState.PreviewStarted && !hasHdmiSignal) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xCC000000)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SIN SEÑAL HDMI", color = Color(0xFFEF5350),
                    fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Comprueba la conexión HDMI al HD60 X",
                    color = Color(0xFF90A4AE), fontSize = 13.sp)
            }
        }
    }
}
```

---

## Tests sugeridos

- Conectar HD60 X sin señal HDMI → debe aparecer "Sin señal HDMI".
- Encender la fuente HDMI → el overlay debe desaparecer y aparecer el preview.
- Desconectar el HDMI durante preview → el overlay debe aparecer inmediatamente.

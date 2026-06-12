# MEJORA-04 — Bitrate configurable en VideoRecorder

| Campo | Valor |
|---|---|
| Prioridad | Media |
| Tipo | Backend + Frontend |
| Esfuerzo estimado | 2 horas |

---

## Descripción

El bitrate actual es fijo a 10 Mbps. Añadir un selector en la UI: 5 Mbps (streaming), 10 Mbps (normal), 20 Mbps (alta calidad), 50 Mbps (profesional). Mostrar el tamaño estimado por minuto.

---

## Implementación

### Paso 1 — Exponer opciones de bitrate en el ViewModel

```kotlin
// En MainViewModel.kt:
enum class BitratePreset(val bps: Int, val label: String, val mbPerMin: Int) {
    LOW(5_000_000,  "5 Mbps — Streaming",     38),
    NORMAL(10_000_000, "10 Mbps — Normal",    75),
    HIGH(20_000_000,   "20 Mbps — Alta calidad", 150),
    PRO(50_000_000,    "50 Mbps — Profesional",  375),
}

private val _selectedBitrate = MutableStateFlow(BitratePreset.NORMAL)
val selectedBitrate: StateFlow<BitratePreset> = _selectedBitrate.asStateFlow()

fun selectBitrate(preset: BitratePreset) {
    if (_isRecording.value) return
    _selectedBitrate.value = preset
}
```

### Paso 2 — Pasar el bitrate al VideoRecorder

```kotlin
val recorder = VideoRecorder(
    context    = requireContext(),
    width      = res.width,
    height     = res.height,
    fps        = res.fps,
    bitrate    = vm.selectedBitrate.value.bps,
)
```

### Paso 3 — UI del selector de bitrate

```kotlin
// En el panel inferior de ElgatoRecorderScreen:
val selectedBitrate by vm.selectedBitrate.collectAsState()

Column(modifier = Modifier.fillMaxWidth()) {
    Text("Calidad", color = Color(0xFF90A4AE), fontSize = 11.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BitratePreset.entries.forEach { preset ->
            val isSelected = selectedBitrate == preset
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) Color(0xFF1565C0) else Color(0xFF1A1A1A))
                    .border(1.dp, if (isSelected) Color(0xFF1565C0) else Color(0xFF333333), RoundedCornerShape(6.dp))
                    .clickable(enabled = !isRecording) { vm.selectBitrate(preset) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${preset.bps / 1_000_000}M",
                        color = if (isSelected) Color.White else Color(0xFFAAAAAA),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "~${preset.mbPerMin}MB/m",
                        color = Color(0xFF777777),
                        fontSize = 9.sp
                    )
                }
            }
        }
    }
}
```

---

## Tests sugeridos

- Grabar 1 minuto a 5 Mbps → tamaño del archivo ≈ 38 MB.
- Grabar 1 minuto a 50 Mbps → tamaño del archivo ≈ 375 MB.
- Cambiar bitrate durante grabación → debe estar deshabilitado, no causar crash.

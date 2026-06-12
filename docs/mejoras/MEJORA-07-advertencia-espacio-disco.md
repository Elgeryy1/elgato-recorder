# MEJORA-07 — Advertencia de espacio en disco insuficiente

| Campo | Valor |
|---|---|
| Prioridad | Alta |
| Tipo | Frontend |
| Esfuerzo estimado | 1 hora |

---

## Descripción

Mostrar espacio disponible y duración máxima estimada de grabación. Advertir cuando quede menos de 5 minutos de espacio con el bitrate actual.

---

## Implementación

### Paso 1 — Calcular espacio disponible en el ViewModel

```kotlin
// En MainViewModel.kt:
data class StorageInfo(val freeBytes: Long, val maxRecordSeconds: Long)

fun getStorageInfo(): StorageInfo {
    val externalDir = getApplication<Application>()
        .getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
    val stat = android.os.StatFs(externalDir?.path ?: "/sdcard")
    val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
    val bytesPerSec = _selectedBitrate.value.bps / 8L
    val maxSec = if (bytesPerSec > 0) freeBytes / bytesPerSec else Long.MAX_VALUE
    return StorageInfo(freeBytes, maxSec)
}
```

### Paso 2 — Exponer como StateFlow actualizado periódicamente

```kotlin
private val _storageInfo = MutableStateFlow(StorageInfo(0L, 0L))
val storageInfo: StateFlow<StorageInfo> = _storageInfo.asStateFlow()

init {
    viewModelScope.launch {
        while (true) {
            _storageInfo.value = getStorageInfo()
            delay(5_000)  // actualizar cada 5 segundos
        }
    }
}
```

### Paso 3 — Mostrar en la UI

```kotlin
val storageInfo by vm.storageInfo.collectAsState()
val freeGB = storageInfo.freeBytes / (1024f * 1024 * 1024)
val maxMin = storageInfo.maxRecordSeconds / 60

// Mostrar bajo el botón REC:
val storageWarning = maxMin < 5 && appState is AppState.PreviewStarted
Text(
    text = when {
        storageWarning -> "⚠ Menos de ${maxMin}min disponibles"
        freeGB > 0 -> "~${maxMin}min disponibles (${String.format("%.1f", freeGB)}GB)"
        else -> ""
    },
    color = if (storageWarning) Color(0xFFEF5350) else Color(0xFF777777),
    fontSize = 10.sp
)
```

---

## Tests sugeridos

- Con 1 GB libre y 10 Mbps → debe mostrar "~13min disponibles".
- Con 200 MB libre → debe mostrar "⚠ Menos de 3min disponibles" en rojo.
- Durante grabación activa → el contador debe actualizarse cada 5s mostrando el espacio restante.

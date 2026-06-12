# BUG-10 — Sin persistencia de la resolución seleccionada

| Campo | Valor |
|---|---|
| Severidad | BAJO |
| Archivo | `MainViewModel.kt` |
| Esfuerzo estimado | 1 hora |

---

## Descripción

La resolución seleccionada se pierde al cerrar la app. En cada inicio se selecciona automáticamente 1080p60 (o la primera disponible), ignorando la preferencia del usuario.

---

## Solución paso a paso

### Paso 1 — Añadir DataStore

```kotlin
// app/build.gradle.kts:
implementation("androidx.datastore:datastore-preferences:1.1.1")
```

### Paso 2 — Guardar resolución al seleccionarla

```kotlin
private val Context.prefsStore: DataStore<Preferences>
    by preferencesDataStore("elgato_prefs")

private val KEY_RES_W = intPreferencesKey("res_w")
private val KEY_RES_H = intPreferencesKey("res_h")
private val KEY_RES_FPS = intPreferencesKey("res_fps")

fun selectResolution(res: PreviewResolution) {
    if (_isRecording.value) return
    _currentResolution.value = res
    _showResolutionPicker.value = false
    viewModelScope.launch {
        _cameraCommands.emit(CameraCommand.ChangeResolution(res.width, res.height, res.fps))
        // Persistir
        getApplication<Application>().prefsStore.edit { prefs ->
            prefs[KEY_RES_W]   = res.width
            prefs[KEY_RES_H]   = res.height
            prefs[KEY_RES_FPS] = res.fps
        }
    }
}
```

### Paso 3 — Restaurar al inicializar las resoluciones disponibles

```kotlin
fun onAvailableResolutions(sizes: List<PreviewResolution>, usbManager: android.hardware.usb.UsbManager) {
    // ... código existente de expand/sort ...
    val sorted = // ... sorted list ...

    _availableResolutions.value = sorted

    if (_currentResolution.value == null && sorted.isNotEmpty()) {
        // Intentar restaurar la resolución guardada
        viewModelScope.launch {
            val prefs = getApplication<Application>().prefsStore.data.first()
            val savedW   = prefs[KEY_RES_W]
            val savedH   = prefs[KEY_RES_H]
            val savedFps = prefs[KEY_RES_FPS]

            val restored = if (savedW != null && savedH != null && savedFps != null) {
                sorted.firstOrNull {
                    it.width == savedW && it.height == savedH && it.fps == savedFps
                }
            } else null

            _currentResolution.value = restored
                ?: sorted.firstOrNull { it.width == 1920 && it.fps >= 60 }
                ?: sorted.first()
        }
    }
}
```

---

## Tests sugeridos

- Seleccionar 2560×1440 60fps → cerrar app → reabrir → debe estar seleccionada la misma.
- Primera instalación → debe seleccionar 1080p60 por defecto.
- Resolución guardada ya no disponible (cambio de hardware) → debe hacer fallback a 1080p60.

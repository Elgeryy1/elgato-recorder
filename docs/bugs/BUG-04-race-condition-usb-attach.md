# BUG-04 — Race condition en `onUsbDeviceAttached`

| Campo | Valor |
|---|---|
| Severidad | ALTO |
| Archivo | `MainViewModel.kt` |
| Líneas afectadas | 113-127 |
| Esfuerzo estimado | 1 hora |

---

## Descripción

```kotlin
fun onUsbDeviceAttached() {
    _appState.value = AppState.OpeningCamera    // ← Línea 114: estado → OpeningCamera
    val elgato = usbDeviceManager.scanForElgatoDevice()
    if (elgato != null && _appState.value !is AppState.PreviewStarted) {  // ← Línea 116
        _appState.value = AppState.UsbDeviceDetected(...)
    }
}
```

En la línea 116, la condición `_appState.value !is AppState.PreviewStarted` es **siempre `true`** porque acabamos de establecer el estado a `OpeningCamera` en la línea 114. `OpeningCamera` no es `PreviewStarted`, por lo que la condición siempre se cumple.

**Pero hay un problema más grave:** en la línea 114 se establece `OpeningCamera` antes de saber si hay un dispositivo. Si no hay dispositivo Elgato conectado (`elgato == null`), el estado queda en `OpeningCamera` indefinidamente en lugar de volver a `WaitingForDevice`.

---

## Causa raíz

La lógica de estados está invertida. Se asume que hay un dispositivo (`OpeningCamera`) antes de verificarlo.

---

## Impacto

- Al conectar un dispositivo USB que NO es Elgato, el estado queda en `OpeningCamera` para siempre.
- La UI muestra "Abriendo stream UVC..." cuando no hay ninguna cámara conectada.
- El botón "Reintentar" es el único escape de ese estado.

---

## Solución paso a paso

### Paso 1 — Corregir el flujo de estados en `onUsbDeviceAttached`

```kotlin
fun onUsbDeviceAttached() {
    // NO asumir OpeningCamera antes de escanear
    val elgato = usbDeviceManager.scanForElgatoDevice()

    if (elgato == null) {
        // USB conectado pero no es Elgato — volver a esperar
        if (_appState.value is AppState.WaitingForDevice) {
            // Ya estamos esperando, nada que hacer
        } else {
            _appState.value = AppState.WaitingForDevice
        }
        return
    }

    // Solo hacer la transición si no estamos ya en preview
    val current = _appState.value
    if (current is AppState.PreviewStarted || current is AppState.OpeningCamera) {
        return  // ya estamos procesando este dispositivo
    }

    _appState.value = AppState.UsbDeviceDetected(
        deviceName     = elgato.deviceName,
        vendorId       = elgato.vendorId,
        productId      = elgato.productId,
        deviceClass    = elgato.deviceClass,
        deviceSubclass = elgato.deviceSubclass,
        deviceProtocol = elgato.deviceProtocol,
        interfaceCount = elgato.interfaceCount,
    )
}
```

### Paso 2 — Separar el estado `OpeningCamera` para que sea iniciado desde la UI

El estado `OpeningCamera` debe ser iniciado por el Fragment cuando AUSBC empieza a abrir la cámara, no por el ViewModel cuando detecta el USB:

```kotlin
// Llamar desde ElgatoCameraFragment.onViewCreated():
// vm.onCameraOpening()  ← nuevo método

fun onCameraOpening() {
    if (_appState.value is AppState.UsbDeviceDetected) {
        _appState.value = AppState.OpeningCamera
    }
}
```

---

## Tests sugeridos

- Conectar un ratón USB (no Elgato) → estado debe permanecer en `WaitingForDevice`, no en `OpeningCamera`.
- Conectar el HD60 X → estado debe ir a `UsbDeviceDetected` correctamente.
- Desconectar y reconectar rápidamente → no debe haber estado residual.
- Estado `PreviewStarted` activo → reconectar USB → no debe reiniciar el proceso.

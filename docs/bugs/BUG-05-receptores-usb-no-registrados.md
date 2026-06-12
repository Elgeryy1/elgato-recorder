# BUG-05 — Receptores USB nunca registrados en el ciclo de vida de MainActivity

| Campo | Valor |
|---|---|
| Severidad | ALTO |
| Archivos | `UsbDeviceManager.kt`, `MainActivity.kt` |
| Esfuerzo estimado | 30 minutos |

---

## Descripción

`UsbDeviceManager` tiene los métodos `registerReceivers()` y `unregisterReceivers()` correctamente implementados para escuchar eventos USB (`ACTION_USB_DEVICE_ATTACHED`, `ACTION_USB_DEVICE_DETACHED`) y resultados de permisos USB. Sin embargo, **`MainActivity` nunca llama a estos métodos**.

```kotlin
// UsbDeviceManager.kt — estos métodos existen pero nadie los llama:
fun registerReceivers() { ... }    // ← NUNCA LLAMADO
fun unregisterReceivers() { ... }  // ← NUNCA LLAMADO
```

```kotlin
// MainActivity.kt — no llama a ningún método del UsbDeviceManager:
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ... }
    }
    // No hay onStart, onStop, ni referencias a vm.usbDeviceManager
}
```

---

## Causa raíz

Durante el desarrollo, los métodos de registro fueron implementados en `UsbDeviceManager` pero la integración con el ciclo de vida de `MainActivity` nunca se completó.

---

## Impacto

- **Conectar el HD60 X con la app ya abierta no funciona.** El broadcast `ACTION_USB_DEVICE_ATTACHED` nunca se recibe.
- **Desconectar el HD60 X no actualiza el estado.** El `UsbDeviceDetected` no se limpia.
- **El diálogo de permiso USB no se maneja.** Aunque el usuario concede el permiso, el `permissionReceiver` no recibe el resultado.
- El flujo solo funciona si el HD60 X estaba conectado **antes** de abrir la app (se detecta en el `init` del ViewModel).

---

## Solución paso a paso

### Paso 1 — Registrar receptores en `onStart` de `MainActivity`

`MainActivity` extiende `FragmentActivity` que es una `ComponentActivity`. En Compose se puede acceder al ViewModel desde la Activity:

```kotlin
class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ... }
    }

    override fun onStart() {
        super.onStart()
        viewModel.usbDeviceManager.registerReceivers()
    }

    override fun onStop() {
        super.onStop()
        viewModel.usbDeviceManager.unregisterReceivers()
    }
}
```

> **Nota:** Se usa `onStart`/`onStop` en lugar de `onCreate`/`onDestroy` para evitar recibir broadcasts cuando la app está en background (y para que la Activity no los reciba cuando está oculta por otra Activity).

### Paso 2 — Conectar el resultado del permiso con el Fragment

Cuando se recibe el permiso USB, el Fragment necesita saberlo para continuar el proceso de apertura de la cámara. Añadir un observador en el Fragment:

```kotlin
// En ElgatoCameraFragment.onViewCreated():
viewLifecycleOwner.lifecycleScope.launch {
    viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        vm.usbDeviceManager.permissionGranted.collect { granted ->
            when (granted) {
                true -> {
                    Log.d(TAG, "Permiso USB concedido — abriendo cámara")
                    // AUSBC abrirá la cámara automáticamente si el permiso fue concedido
                }
                false -> {
                    vm.onCameraError("Permiso USB denegado por el usuario")
                }
                null -> { /* sin resultado aún */ }
            }
        }
    }
}
```

### Paso 3 — Actualizar el estado cuando se detecta USB en caliente

```kotlin
// En UsbDeviceManager (el callback ya existe, solo necesita llegar al ViewModel):
// En el receiver de ATTACHED:
UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
    Log.d(TAG, "USB: dispositivo conectado (broadcast)")
    scanForElgatoDevice()
    // Notificar al ViewModel (si está escuchando elgatoDevice via Flow ya se enterará)
}
```

El ViewModel puede observar el `StateFlow<UsbDevice?>` del `UsbDeviceManager`:

```kotlin
// En MainViewModel.init():
viewModelScope.launch {
    usbDeviceManager.elgatoDevice.collect { device ->
        if (device != null && _appState.value is AppState.WaitingForDevice) {
            _appState.value = AppState.UsbDeviceDetected(...)
        } else if (device == null && _appState.value !is AppState.WaitingForDevice
                   && _appState.value !is AppState.OpeningCamera
                   && _appState.value !is AppState.PreviewStarted) {
            // Solo limpiar si no estamos activamente usando la cámara
        }
    }
}
```

---

## Tests sugeridos

- Abrir la app sin HD60 X → conectar en caliente → el estado debe cambiar a `UsbDeviceDetected`.
- `UsbDeviceDetected` → desconectar → el estado debe volver a `WaitingForDevice`.
- Conectar con la app en background → al volver al primer plano no debe haber estado incorrecto.
- Conceder permiso USB en el diálogo → el Fragment debe recibirlo y continuar el proceso.

# MEJORA-02 — Registrar receptores USB en ciclo de vida de Activity

| Campo | Valor |
|---|---|
| Prioridad | CRÍTICA |
| Tipo | Backend |
| Esfuerzo estimado | 30 minutos |

---

## Descripción

Completar la integración del `UsbDeviceManager` en el ciclo de vida de `MainActivity`. Ver implementación completa en [BUG-05](../bugs/BUG-05-receptores-usb-no-registrados.md).

---

## Cambios necesarios

```kotlin
// MainActivity.kt — añadir:
private val viewModel: MainViewModel by viewModels()

override fun onStart() {
    super.onStart()
    viewModel.usbDeviceManager.registerReceivers()
}

override fun onStop() {
    super.onStop()
    viewModel.usbDeviceManager.unregisterReceivers()
}
```

Eso es todo. Los receptores ya están implementados — solo falta llamarlos.

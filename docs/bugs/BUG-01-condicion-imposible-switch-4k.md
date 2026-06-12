# BUG-01 — Condición imposible en switch 4K (`is4KSwitchDone` siempre true)

| Campo | Valor |
|---|---|
| Severidad | CRÍTICO |
| Archivo | `ElgatoCameraFragment.kt` |
| Líneas afectadas | 235-243 (método `attemptSwitchTo4K`) |
| Esfuerzo estimado | 1 hora |

---

## Descripción

En el método `attemptSwitchTo4K()`, después de intentar el switch via reflexión:

```kotlin
is4KSwitchDone.set(true)    // ← Línea 235: se pone a true
delay(1000)

// ← Línea 239: esta condición NUNCA puede ser true
if (!is4KSwitchDone.get()) {
    vm.onCameraError("4K negociado pero sin frames. El HD60 X no soporta captura 4K USB.")
} else {
    Log.d(TAG, "Switch a 4K completado")
    vm.onCameraOpened()
    reportAvailableSizes()
}
```

El flag `is4KSwitchDone` se pone a `true` en la línea 235. La comprobación `if (!is4KSwitchDone.get())` en la línea 239 siempre será `false` porque el flag fue establecido 4 líneas antes y nada lo puede cambiar a `false` en ese periodo. **El código del error "4K negociado pero sin frames" es inalcanzable.**

---

## Causa raíz

La lógica original intentaba detectar si la cámara realmente empezó a entregar frames 4K verificando el flag. Pero el flag se marcó como completado **antes** de esperar la confirmación de frames, haciendo la verificación inútil.

---

## Impacto

- El path de error del switch 4K nunca se ejecuta.
- Si el switch falla silenciosamente (cámara acepta el comando pero no envía frames 4K), la app reporta "Switch a 4K completado" cuando en realidad sigue en 1080p o en un estado undefined.
- El usuario cree que está grabando en 4K cuando no lo está.

---

## Cómo reproducirlo

1. Conectar el HD60 X.
2. Seleccionar una resolución 4K en el picker.
3. Observar los logs: "Switch a 4K completado" aparece siempre, incluso si el switch falla.

```
D/ElgatoRecorder: 4K setPreviewSize(3840×2160, 28-32, MJPEG, 1.0f) → OK
D/ElgatoRecorder: Switch a 4K completado   ← siempre llega aquí
```

---

## Solución paso a paso

### Paso 1 — Reemplazar el flag booleano por un contador de frames 4K

En lugar de usar `is4KSwitchDone` como indicador de que "el switch fue enviado", usarlo para confirmar que **llegaron frames reales del tamaño correcto**.

```kotlin
// En ElgatoCameraFragment.kt, añadir un contador de frames 4K:
private val frames4KReceived = java.util.concurrent.atomic.AtomicInteger(0)
```

### Paso 2 — Contar frames 4K reales en `addPreviewDataCallBack`

```kotlin
addPreviewDataCallBack(object : IPreviewDataCallBack {
    override fun onPreviewData(data: ByteArray?, format: IPreviewDataCallBack.DataFormat) {
        if (data == null) return

        val res = vm.currentResolution.value
        if (res != null && res.width >= 3840) {
            // Un frame 4K NV21 tiene tamaño = width * height * 1.5
            val expectedSize = res.width * res.height * 3 / 2
            val isFull4K = data.size >= expectedSize * 0.9  // tolerancia 10%
            if (isFull4K) {
                frames4KReceived.incrementAndGet()
            }
        }

        // Detección de primer frame para preview normal
        if (previewNotified.compareAndSet(false, true)) {
            val is4KTarget = res != null && res.width >= 3840
            if (is4KTarget && !is4KSwitchDone.get()) {
                viewLifecycleOwner.lifecycleScope.launch {
                    attemptSwitchTo4K(res.width, res.height, res.fps)
                }
            } else {
                vm.onCameraOpened()
                reportAvailableSizes()
            }
        }
        videoRecorder?.feedNv21Frame(data)
    }
})
```

### Paso 3 — Corregir `attemptSwitchTo4K` para verificar frames reales

```kotlin
private suspend fun attemptSwitchTo4K(width: Int, height: Int, fps: Int) {
    Log.d(TAG, "Intentando switch a 4K via reflexión (bandwidth=1.0f)…")
    delay(800)

    val strategy = cameraStrategy ?: run {
        vm.onCameraError("No se pudo acceder a la estrategia de cámara")
        return
    }
    val uvcCamera = findUVCCamera(strategy) ?: run {
        vm.onCameraError("4K: acceso nativo no disponible")
        return
    }

    try {
        runCatching { uvcCamera.javaClass.getMethod("stopPreview").invoke(uvcCamera) }

        val method = uvcCamera.javaClass.getMethod(
            "setPreviewSize",
            Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            Int::class.java, Float::class.java
        )
        val minFps = if (fps <= 25) 24 else 28
        method.invoke(uvcCamera, width, height, minFps, fps + 2, FRAME_MJPEG, BW_FULL)
        runCatching { uvcCamera.javaClass.getMethod("startPreview").invoke(uvcCamera) }

        // CORRECCIÓN: esperar frames 4K reales antes de confirmar
        frames4KReceived.set(0)
        val deadline = System.currentTimeMillis() + 3000L  // 3s para recibir al menos 1 frame 4K
        while (System.currentTimeMillis() < deadline && frames4KReceived.get() < 3) {
            delay(100)
        }

        if (frames4KReceived.get() >= 3) {
            is4KSwitchDone.set(true)
            Log.d(TAG, "Switch a 4K confirmado: ${frames4KReceived.get()} frames recibidos")
            vm.onCameraOpened()
            reportAvailableSizes()
        } else {
            // El error ahora SÍ es alcanzable
            vm.onCameraError(
                "4K negociado pero sin frames 4K reales en 3s.\n" +
                "La HD60 X captura hasta 1080p60 por USB.\n" +
                "Usa 2K60 o 1080p60."
            )
        }

    } catch (e: Exception) {
        Log.e(TAG, "setPreviewSize 4K falló: ${e.message}")
        runCatching { uvcCamera.javaClass.getMethod("startPreview").invoke(uvcCamera) }
        vm.onCameraError(
            "4K no soportado vía USB.\nUsa 2K60 o 1080p60."
        )
    }
}
```

### Paso 4 — Resetear el contador en `onDestroyView`

```kotlin
override fun onDestroyView() {
    super.onDestroyView()
    previewNotified.set(false)
    is4KSwitchDone.set(false)
    frames4KReceived.set(0)  // ← AÑADIR
    // ...
}
```

---

## Tests sugeridos

- Seleccionar 4K → HD60 X debería fallar (no soporta 4K USB) → debe aparecer el error correcto.
- Seleccionar 1080p60 → debe arrancar correctamente sin intentar switch 4K.
- En un dispositivo con cámara externa real 4K → debe confirmar frames y reportar éxito.

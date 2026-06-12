# BUG-08 — `createCaptureSession` deprecado en Camera2PreviewManager

| Campo | Valor |
|---|---|
| Severidad | MEDIO |
| Archivo | `Camera2PreviewManager.kt` |
| Líneas afectadas | ~212 |
| Esfuerzo estimado | 1 hora |

---

## Descripción

```kotlin
@Suppress("DEPRECATION")
camera.createCaptureSession(
    listOf(surface),
    object : CameraCaptureSession.StateCallback() { ... },
    backgroundHandler,
)
```

`CameraDevice.createCaptureSession(List<Surface>, ...)` está **deprecado desde API 28 (Android 9)**. La API moderna es `CameraDevice.createCaptureSession(SessionConfiguration)`.

---

## Solución paso a paso

### Paso 1 — Usar `SessionConfiguration` para API 28+

```kotlin
private fun createPreviewSession(camera: CameraDevice, surface: Surface) {
    Log.d(TAG, "Camera2: creando sesión de captura")

    val callback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            Log.d(TAG, "Camera2: sesión configurada — iniciando preview")
            captureSession = session
            startRepeatingRequest(session, camera, surface)
        }
        override fun onConfigureFailed(session: CameraCaptureSession) {
            Log.e(TAG, "Camera2: fallo al configurar la sesión")
            onStateChanged?.invoke(AppState.Error("No se pudo configurar la sesión Camera2."))
        }
    }

    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // API 28+: usar SessionConfiguration
            val outputConfig = android.hardware.camera2.params.OutputConfiguration(surface)
            val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
                android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                listOf(outputConfig),
                java.util.concurrent.Executors.newSingleThreadExecutor(),
                callback
            )
            camera.createCaptureSession(sessionConfig)
        } else {
            // API < 28: usar la API deprecada (ya no debería ocurrir con minSdk moderno)
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(surface), callback, backgroundHandler)
        }
    } catch (e: CameraAccessException) {
        Log.e(TAG, "Camera2: error creando sesión", e)
        onStateChanged?.invoke(AppState.Error("Error creando sesión: ${e.message}"))
    }
}
```

### Paso 2 — Verificar el `minSdk` del proyecto

Si `minSdk >= 28`, se puede eliminar el branch de la API deprecada completamente:

```kotlin
private fun createPreviewSession(camera: CameraDevice, surface: Surface) {
    val outputConfig = android.hardware.camera2.params.OutputConfiguration(surface)
    val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
        android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
        listOf(outputConfig),
        java.util.concurrent.Executors.newSingleThreadExecutor(),
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) { ... }
            override fun onConfigureFailed(session: CameraCaptureSession) { ... }
        }
    )
    runCatching { camera.createCaptureSession(sessionConfig) }
        .onFailure { onStateChanged?.invoke(AppState.Error("Error sesión: ${it.message}")) }
}
```

---

## Tests sugeridos

- Abrir la cámara externa → debe mostrar preview sin errores.
- API 28 emulador → sin warnings de deprecación.
- API 35 dispositivo real → sin regresión en el preview.

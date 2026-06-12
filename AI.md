# ElgatoRecorder — Contexto para IA

## Qué hace
App Android que captura vídeo de alta calidad desde una Elgato HD60 X conectada por USB (UVC) a un smartphone. AUSBC abre el stream UVC, la app recibe frames NV21 y los recodifica con su propia pipeline MediaCodec H.264 → MediaMuxer → MediaStore. Soporta Android Auto... no, soporta Android 10+ (minSdk 29).

## Por qué no usa el grabador de AUSBC
AUSBC 3.2.7 usa la columna `_data` de MediaStore (bloqueada en Android 10+) para grabar. La app implementa su propia pipeline para evitar esto y tener control total sobre el output.

## Stack
Kotlin · Jetpack Compose · AUSBC 3.2.7 (biblioteca UVC) · MediaCodec · MediaMuxer · MediaStore · USB HID · Camera2 (solo para cámaras externas via UVC si el kernel lo expone) · minSdk 29

## Mapa de archivos clave
```
app/src/main/java/com/gery/elgatorecorder/
├── MainActivity.kt              — FragmentActivity con Compose. Aloja ElgatoCameraFragment via AndroidView
├── MainViewModel.kt             — Estado central. Flujos: appState, isRecording, availableResolutions, currentResolution
├── AppState.kt                  — Sealed class: WaitingForDevice | UsbDeviceDetected | RequestingUsbPermission | OpeningCamera | PreviewStarted | Error
├── ElgatoCameraFragment.kt      — Extiende CameraFragment de AUSBC. Preview UVC + lógica 4K via reflexión + VideoRecorder
├── VideoRecorder.kt             — Pipeline NV21→H.264: MediaCodec encoder + MediaMuxer + MediaStore. SOLO VÍDEO (sin audio — BUG-02)
├── UsbDeviceManager.kt          — Detecta Elgato (vendorId=4057), pide permiso USB, emite StateFlow<UsbDevice?>
├── UvcDescriptorParser.kt       — Lee descriptores USB raw para obtener fps reales (no el placeholder 30fps de AUSBC)
├── Camera2PreviewManager.kt     — Intento de preview via Camera2 para cámaras LENS_FACING_EXTERNAL (Samsung no expone el HD60 X así)
├── CameraCommand.kt             — Sealed class para comandos de ViewModel→Fragment: StartRecording(path) | StopRecording | ChangeResolution
└── ReceiverCompatContext.kt     — Wrapper de Context para compatibilidad de BroadcastReceiver con AUSBC
```

## Arquitectura en pocas líneas
- `MainActivity` lanza `ElgatoCameraFragment` dentro de un `FragmentContainerView` gestionado por Compose (patrón `AndroidView`).
- El ViewModel NO toca el Fragment directamente — le envía comandos via `SharedFlow<CameraCommand>`.
- El Fragment llama callbacks del ViewModel: `onCameraOpened()`, `onRecordingStarted()`, etc.
- `VideoRecorder` es completamente independiente: recibe `ByteArray` NV21 de AUSBC via `feedNv21Frame()`, los encola y codifica en un hilo separado.
- `UvcDescriptorParser` abre una conexión USB *temporal* (sin reclamar interfaces) para leer los descriptores raw y extraer los fps reales del dispositivo.
- Cambio de resolución: el Fragment se elimina a sí mismo (`remove(this).commitNow()`) y el ViewModel incrementa un contador `fragmentRestartTrigger`, que provoca que `LaunchedEffect` lo re-añada.

## Convenciones del código
- TAG único `"ElgatoRecorder"` en todos los `Log.d/e`.
- `_appState = MutableStateFlow<AppState>(AppState.WaitingForDevice)`.
- Los callbacks del Fragment al ViewModel se llaman `onXxx()` (ej: `vm.onCameraOpened()`).
- Los comandos del ViewModel al Fragment son sealed class `CameraCommand`.
- `AtomicBoolean` para flags de estado en el Fragment (no StateFlow, porque el Fragment no es un ViewModel).
- Suspensions de coordenación con `delay()` en coroutines del Fragment para timing crítico.

## Bugs críticos conocidos (ver docs/bugs/)
1. **BUG-01**: Switch 4K tiene condición imposible (`is4KSwitchDone.set(true)` antes del `if (!is4KSwitchDone.get())`). El error "4K negociado pero sin frames" es inalcanzable.
2. **BUG-02**: `VideoRecorder` NO tiene pista de audio. Todas las grabaciones son mudas. **BLOCKER para release.**
3. **BUG-03**: El `path` de `CameraCommand.StartRecording(path)` es generado por el ViewModel pero completamente ignorado por el Fragment. `VideoRecorder` genera su propio nombre.
4. **BUG-05**: `UsbDeviceManager.registerReceivers()` y `unregisterReceivers()` NUNCA son llamados. Los eventos USB en caliente no funcionan.

## Gotchas críticos
- **AUSBC y el Fragment se destruyen a sí mismos**: al cambiar de resolución, el Fragment se elimina. El reenganche viene de `fragmentRestartTrigger`. No intentar reiniciar AUSBC manualmente — destruir y recrear el Fragment es el mecanismo correcto.
- **NV21 → NV12**: los frames de AUSBC son NV21 (plano VU intercalado), MediaCodec espera NV12 (plano UV). La conversión está en `VideoRecorder.encodeNextFrame()`. Intercambia los bytes del plano UV.
- **4K via USB no funciona en HD60 X**: el hardware captura hasta 1080p60 via USB. El switch 4K via reflexión siempre falla. Las resoluciones ≥3840 son "preview only" en la práctica.
- **`AUSBC` no es thread-safe para cambio de resolución**: hay que destruir/recrear el Fragment entero. No existe API de AUSBC para cambiar la resolución en caliente de forma fiable.
- **`MediaMuxer.start()`** solo se puede llamar después de `addTrack()`. Cuando se añada audio (BUG-02), asegurarse de que AMBAS pistas (vídeo y audio) han emitido `INFO_OUTPUT_FORMAT_CHANGED` antes de llamar `mux.start()`.
- **`IS_PENDING=1`** en MediaStore: se usa correctamente en `VideoRecorder.initMuxer()` y se limpia a 0 en `cleanup()`. Si el proceso es kilado durante la grabación, el archivo queda pendiente y no es visible en la galería. Considerar un mecanismo de recovery al inicio de la app.
- **`UvcDescriptorParser`** abre una conexión USB temporal. Si AUSBC ya tiene el dispositivo abierto, puede fallar `usbManager.openDevice(device)`. El parser maneja esto devolviendo `emptyList()` silenciosamente.
- **`Camera2PreviewManager`** existe pero Samsung nunca expone el HD60 X como `LENS_FACING_EXTERNAL`. Es código diagnóstico/experimental, no se usa en el flujo principal. No eliminarlo sin entender por qué está.

## Flujo principal de estados
```
WaitingForDevice
    → [USB conectado] → UsbDeviceDetected
    → [AUSBC abre cámara] → OpeningCamera
    → [primer frame GL] → PreviewStarted
    → [error] → Error (recuperable con retry())
```

## Estado actual del proyecto
- ✅ Preview UVC de HD60 X funciona a 1080p60
- ✅ Resoluciones detectadas via UvcDescriptorParser
- ✅ Cambio de resolución destruyendo/recreando Fragment
- ✅ Grabación vídeo H.264 funciona (sin audio)
- ✅ MediaStore correcto en Android 10+
- ✅ Timer de grabación en UI
- ❌ SIN AUDIO en grabaciones (MediaCodec AAC pendiente)
- ❌ Receptores USB en caliente no registrados (conectar en frío funciona)
- ❌ Cambio de resolución a veces necesita 2 intentos
- ❌ Switch 4K via reflexión no funciona (limitación del hardware HD60 X)

## Cómo añadir audio (BUG-02 — tarea más urgente)
1. Añadir `AudioRecord` en `VideoRecorder` con `AudioSource.MIC`, 48kHz estéreo.
2. Añadir encoder `MediaCodec` AAC (`MediaFormat.MIMETYPE_AUDIO_AAC`).
3. Añadir pista audio al `MediaMuxer` con `muxer.addTrack(audioEncoder.outputFormat)`.
4. **CRÍTICO**: `muxer.start()` solo cuando AMBAS pistas han hecho `INFO_OUTPUT_FORMAT_CHANGED`.
5. Sincronizar `synchronized(muxer!!)` en `writeSampleData` — el muxer no es thread-safe.
6. En `stop()`: señalizar EOS en vídeo + parar AudioRecord + drenar ambos encoders antes de `muxer.stop()`.

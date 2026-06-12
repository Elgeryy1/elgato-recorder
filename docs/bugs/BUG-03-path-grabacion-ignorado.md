# BUG-03 — Path de grabación del ViewModel completamente ignorado

| Campo | Valor |
|---|---|
| Severidad | ALTO |
| Archivos | `MainViewModel.kt` (startRecording), `ElgatoCameraFragment.kt` (startVideoRecording) |
| Esfuerzo estimado | 30 minutos |

---

## Descripción

`MainViewModel.startRecording()` genera un path de salida y lo envía como `CameraCommand.StartRecording(path)`:

```kotlin
// MainViewModel.kt
fun startRecording() {
    // ...
    val path = buildRecordingPath()  // genera algo como: .../ElgatoRecorder/REC_20260601_143022.mp4
    viewModelScope.launch {
        _cameraCommands.emit(CameraCommand.StartRecording(path))  // envía el path
    }
}
```

Pero en `ElgatoCameraFragment.startVideoRecording()`, el parámetro del comando se ignora:

```kotlin
// ElgatoCameraFragment.kt
private fun startVideoRecording() {
    val res = vm.currentResolution.value ?: return
    val recorder = VideoRecorder(
        context = requireContext(),
        width   = res.width,
        height  = res.height,
        fps     = res.fps,
    )
    // El path del CameraCommand.StartRecording nunca se usa
    // VideoRecorder.initMuxer() genera su propio nombre de archivo
}
```

`VideoRecorder.initMuxer()` genera su propio timestamp independiente, haciendo que el path del ViewModel sea completamente inútil.

---

## Causa raíz

El `CameraCommand.StartRecording(path)` fue diseñado para que el Fragment use el path proporcionado, pero la implementación de `startVideoRecording()` ignora el parámetro del comando y `VideoRecorder` genera su propio nombre.

---

## Impacto

- El `buildRecordingPath()` del ViewModel nunca se usa. Si se quiere cambiar el directorio de salida desde el ViewModel, el cambio no tendrá efecto.
- El ViewModel cree que el archivo está en un path pero `VideoRecorder` lo guarda en otro.
- `_lastRecordingPath` en el ViewModel muestra el path generado por el ViewModel, pero el archivo real está en el path generado por `VideoRecorder`. Inconsistencia total.

---

## Solución paso a paso

### Paso 1 — Modificar `handleCommand` para pasar el path al VideoRecorder

```kotlin
// En ElgatoCameraFragment.kt:
private fun handleCommand(cmd: CameraCommand) {
    when (cmd) {
        is CameraCommand.StartRecording   -> startVideoRecording(cmd.path)  // ← PASAR el path
        is CameraCommand.StopRecording    -> stopVideoRecording()
        is CameraCommand.ChangeResolution -> changeResolution(cmd.width, cmd.height, cmd.fps)
    }
}
```

### Paso 2 — Modificar `startVideoRecording` para aceptar y usar el path

```kotlin
private fun startVideoRecording(outputPath: String) {
    val res = vm.currentResolution.value ?: return
    Log.d(TAG, "VideoRecorder: start ${res.width}×${res.height} @ ${res.fps}fps → $outputPath")
    val recorder = VideoRecorder(
        context    = requireContext(),
        width      = res.width,
        height     = res.height,
        fps        = res.fps,
        outputPath = outputPath,  // ← pasar el path
    ).apply {
        onStarted  = { vm.onRecordingStarted() }
        onError    = { msg -> vm.onRecordingError(msg) }
        onComplete = { path -> vm.onRecordingStopped(path) }
    }
    videoRecorder = recorder
    recorder.start()
}
```

### Paso 3 — Añadir el parámetro `outputPath` a `VideoRecorder`

```kotlin
class VideoRecorder(
    private val context: Context,
    val width: Int,
    val height: Int,
    val fps: Int = 30,
    private val outputPath: String? = null,  // ← AÑADIR (null = usar MediaStore automático)
) {
    // En initMuxer():
    private fun initMuxer() {
        if (outputPath != null) {
            // Usar el path proporcionado
            val file = java.io.File(outputPath)
            file.parentFile?.mkdirs()
            this.outputPath = outputPath
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            // Comportamiento actual: MediaStore
            // ... código existente ...
        }
    }
}
```

### Paso 4 — Verificar que `buildRecordingPath()` en el ViewModel ya hace `mkdirs()`

El método ya hace `dir.mkdirs()` — no hay nada que cambiar aquí:

```kotlin
private fun buildRecordingPath(): String {
    val dir = File(
        getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MOVIES),
        "ElgatoRecorder"
    )
    dir.mkdirs()  // ✓ ya crea el directorio
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return File(dir, "REC_$ts.mp4").absolutePath
}
```

---

## Tests sugeridos

- Grabar → el archivo debe encontrarse en el path que devuelve `vm.lastRecordingPath.value`.
- `vm.lastRecordingPath.value` del ViewModel debe coincidir con la ruta real del archivo.
- Cambiar el directorio de salida en `buildRecordingPath()` → el archivo debe guardarse en el nuevo directorio.

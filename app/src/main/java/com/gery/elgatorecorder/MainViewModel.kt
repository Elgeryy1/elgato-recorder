package com.gery.elgatorecorder

import android.app.Application
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "ElgatoRecorder"
    }

    val usbDeviceManager = UsbDeviceManager(app)

    // ── Estado de cámara ──────────────────────────────────────────────────────

    private val _appState = MutableStateFlow<AppState>(AppState.WaitingForDevice)
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    // ── Resoluciones ──────────────────────────────────────────────────────────

    private val _availableResolutions = MutableStateFlow<List<PreviewResolution>>(emptyList())
    val availableResolutions: StateFlow<List<PreviewResolution>> = _availableResolutions.asStateFlow()

    private val _currentResolution = MutableStateFlow<PreviewResolution?>(null)
    val currentResolution: StateFlow<PreviewResolution?> = _currentResolution.asStateFlow()

    // ── Grabación ─────────────────────────────────────────────────────────────

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingSeconds = MutableStateFlow(0L)
    val recordingSeconds: StateFlow<Long> = _recordingSeconds.asStateFlow()

    private val _lastRecordingPath = MutableStateFlow<String?>(null)
    val lastRecordingPath: StateFlow<String?> = _lastRecordingPath.asStateFlow()

    private var timerJob: Job? = null

    // ── Canal de comandos al Fragment ─────────────────────────────────────────

    private val _cameraCommands = MutableSharedFlow<CameraCommand>(extraBufferCapacity = 8)
    val cameraCommands = _cameraCommands.asSharedFlow()

    // ── Selector de resolución visible ────────────────────────────────────────

    private val _showResolutionPicker = MutableStateFlow(false)
    val showResolutionPicker: StateFlow<Boolean> = _showResolutionPicker.asStateFlow()

    /**
     * Incrementar este valor hace que Compose re-ejecute el AndroidView.update block
     * para re-añadir el ElgatoCameraFragment después de que se elimine a sí mismo
     * durante un cambio de resolución.
     */
    private val _fragmentRestartTrigger = MutableStateFlow(0)
    val fragmentRestartTrigger: StateFlow<Int> = _fragmentRestartTrigger.asStateFlow()

    fun triggerFragmentRestart() {
        _fragmentRestartTrigger.value++
        Log.d(TAG, "Fragment restart trigger → ${_fragmentRestartTrigger.value}")
    }

    init {
        val elgato = usbDeviceManager.scanForElgatoDevice()
        if (elgato != null) {
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
    }

    // ── Callbacks desde ElgatoCameraFragment ─────────────────────────────────

    fun onCameraOpened() {
        Log.d(TAG, "ViewModel: cámara abierta")
        _appState.value = AppState.PreviewStarted
    }

    fun onCameraClosed() {
        if (_appState.value is AppState.PreviewStarted) {
            _appState.value = AppState.WaitingForDevice
        }
        _isRecording.value = false
        timerJob?.cancel()
    }

    fun onCameraError(msg: String) {
        Log.e(TAG, "ViewModel: error cámara → $msg")
        _appState.value = AppState.Error(msg)
    }

    fun onUsbDeviceAttached() {
        _appState.value = AppState.OpeningCamera
        val elgato = usbDeviceManager.scanForElgatoDevice()
        if (elgato != null && _appState.value !is AppState.PreviewStarted) {
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
    }

    /**
     * El Fragment llama esto cuando tiene la lista de resoluciones de AUSBC.
     * Enriquecer con fps reales leyendo los descriptores USB/UVC.
     */
    fun onAvailableResolutions(
        sizes     : List<PreviewResolution>,
        usbManager: android.hardware.usb.UsbManager,
    ) {
        // Intentar obtener fps reales desde el descriptor USB
        val elgato = usbDeviceManager.scanForElgatoDevice()
        val uvcFormats = if (elgato != null) {
            UvcDescriptorParser.getFormats(usbManager, elgato)
        } else emptyList()

        // Construir mapa width×height → List<fps>
        val fpsMap = uvcFormats.associate { f -> (f.width to f.height) to f.fpsList }

        // Expandir: una entrada por (resolución × fps)
        val expanded = mutableListOf<PreviewResolution>()
        sizes.forEach { r ->
            val fpsList = fpsMap[r.width to r.height]?.takeIf { it.isNotEmpty() }
                ?: listOf(r.fps)   // fallback al fps que venga
            fpsList.forEach { fps ->
                expanded.add(PreviewResolution(r.width, r.height, fps))
            }
        }

        // Ordenar: primero mayor resolución, después mayor fps
        val sorted = expanded.sortedWith(
            compareByDescending<PreviewResolution> { it.pixels }
                .thenByDescending { it.fps }
        )

        Log.d(TAG, "Resoluciones+fps: ${sorted.map { it.label }}")
        _availableResolutions.value = sorted

        if (_currentResolution.value == null && sorted.isNotEmpty()) {
            // Selección inicial: 1080p60 si existe, si no la primera
            _currentResolution.value = sorted.firstOrNull {
                it.width == 1920 && it.fps >= 60
            } ?: sorted.first()
        }
    }

    // ── Acciones del usuario ──────────────────────────────────────────────────

    fun selectResolution(res: PreviewResolution) {
        if (_isRecording.value) return
        _currentResolution.value = res
        _showResolutionPicker.value = false
        viewModelScope.launch {
            _cameraCommands.emit(CameraCommand.ChangeResolution(res.width, res.height, res.fps))
        }
        Log.d(TAG, "Resolución seleccionada: ${res.label}")
    }

    fun startRecording() {
        if (_isRecording.value || _appState.value !is AppState.PreviewStarted) return

        val res = _currentResolution.value
        if (res?.isPreviewOnly == true) {
            // No grabar en modo preview-only — mostrar motivo al usuario
            _appState.value = AppState.Error(res.previewOnlyReason ?: "No se puede grabar en esta resolución.")
            return
        }

        val path = buildRecordingPath()
        Log.d(TAG, "Iniciando grabación → $path (${res?.label})")
        viewModelScope.launch {
            _cameraCommands.emit(CameraCommand.StartRecording(path))
        }

        // Watchdog: si onRecordingStarted no llega en 5s, reportar error
        viewModelScope.launch {
            delay(5_000)
            if (!_isRecording.value && _appState.value is AppState.PreviewStarted) {
                Log.e(TAG, "Watchdog: grabación no arrancó en 5s")
                _appState.value = AppState.Error(
                    "No se pudo iniciar la grabación.\n" +
                    "AUSBC no configuró el encoder.\n" +
                    "Prueba con 1080p 60fps."
                )
            }
        }
    }

    fun stopRecording() {
        if (!_isRecording.value) return
        Log.d(TAG, "Deteniendo grabación")
        viewModelScope.launch {
            _cameraCommands.emit(CameraCommand.StopRecording)
        }
    }

    fun toggleResolutionPicker() {
        if (!_isRecording.value) {
            _showResolutionPicker.value = !_showResolutionPicker.value
        }
    }

    fun dismissResolutionPicker() {
        _showResolutionPicker.value = false
    }

    fun retry() {
        _appState.value = AppState.WaitingForDevice
        _availableResolutions.value = emptyList()
        usbDeviceManager.scanForElgatoDevice()
    }

    // ── Callbacks de grabación desde Fragment ─────────────────────────────────

    fun onRecordingStarted() {
        _isRecording.value = true
        _recordingSeconds.value = 0L
        timerJob = viewModelScope.launch {
            while (_isRecording.value) {
                delay(1_000)
                _recordingSeconds.value++
            }
        }
        Log.d(TAG, "Grabación iniciada")
    }

    fun onRecordingStopped(path: String?) {
        _isRecording.value = false
        timerJob?.cancel()
        _lastRecordingPath.value = path
        Log.d(TAG, "Grabación guardada → $path")
    }

    fun onRecordingError(msg: String?) {
        _isRecording.value = false
        timerJob?.cancel()
        Log.e(TAG, "Error de grabación: $msg")
        _appState.value = AppState.Error("Error grabación: $msg")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildRecordingPath(): String {
        val dir = File(
            getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "ElgatoRecorder"
        )
        dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "REC_$ts.mp4").absolutePath
    }

    fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%02d:%02d".format(m, s)
    }
}

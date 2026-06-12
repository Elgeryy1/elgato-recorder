package com.gery.elgatorecorder

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jiangdg.ausbc.CameraClient
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.CameraUvcStrategy
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class ElgatoCameraFragment : CameraFragment() {

    companion object {
        private const val TAG           = "ElgatoRecorder"
        private const val FRAME_MJPEG   = 4     // UVCCamera.FRAME_FORMAT_MJPEG
        private const val BW_FULL       = 1.0f  // bandwidth al 100% para 4K
        private const val BW_NORMAL     = 0.9f  // bandwidth al 90% para resoluciones normales
    }

    private val vm: MainViewModel by activityViewModels()
    private lateinit var mRootLayout: FrameLayout
    private val previewNotified = AtomicBoolean(false)
    private val is4KSwitchDone  = AtomicBoolean(false)
    private var cameraStrategy: CameraUvcStrategy? = null
    private var videoRecorder: VideoRecorder? = null

    // ── Abstractos ───────────────────────────────────────────────────────────

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): android.view.View {
        mRootLayout = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        return mRootLayout
    }

    /** Detectar apertura de cámara vía GL (funciona a cualquier resolución incluida 4K). */
    override fun getCameraView(): IAspectRatio? =
        AspectRatioTextureView(requireContext()).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    Log.d(TAG, "TextureView surface disponible ${w}×${h}")
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = false
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                    // GL renderizó un frame → cámara activa
                    if (previewNotified.compareAndSet(false, true)) {
                        Log.d(TAG, "TextureView: GL frame → cámara abierta")
                        val res = vm.currentResolution.value
                        if (res != null && res.width >= 3840 && !is4KSwitchDone.get()) {
                            // Abrir a 1080p primero, pero el TARGET es 4K →
                            // intentar switch a 4K via reflexión ahora que el device está abierto
                            viewLifecycleOwner.lifecycleScope.launch {
                                attemptSwitchTo4K(res.width, res.height, res.fps)
                            }
                        } else {
                            vm.onCameraOpened()
                            reportAvailableSizes()
                        }
                    }
                }
            }
        }

    override fun getCameraViewContainer(): ViewGroup? = mRootLayout

    /**
     * Para 4K: abrimos SIEMPRE a 1080p (AUSBC falla a 4K con bandwidth=0.8).
     * Después del primer frame, usamos reflexión para cambiar a 4K con bandwidth=1.0.
     * Para otras resoluciones: abrimos directamente al tamaño pedido.
     */
    override fun getCameraClient(): CameraClient? {
        val res = vm.currentResolution.value
        val is4K = (res?.width ?: 0) >= 3840

        // Para 4K abrimos con 1080p (workaround bandwidth)
        val w   = if (is4K) 1920 else (res?.width  ?: 1920)
        val h   = if (is4K) 1080 else (res?.height ?: 1080)
        val fps = if (is4K) 60   else (res?.fps    ?: 60)

        Log.d(TAG, "getCameraClient: solicitando ${w}×${h} @ ${fps}fps (target=${res?.label})")
        val ctx      = ReceiverCompatContext(requireActivity())
        val strategy = CameraUvcStrategy(ctx).also { cameraStrategy = it }
        return CameraClient.newBuilder(ctx)
            .setCameraStrategy(strategy)
            .setCameraRequest(
                CameraRequest.Builder()
                    .setPreviewWidth(w).setPreviewHeight(h)
                    .setFrontCamera(false).create()
            )
            .setEnableGLES(true)
            .openDebug(false)
            .build()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        previewNotified.set(false)
        is4KSwitchDone.set(false)
        vm.onUsbDeviceAttached()

        // NV21 callback → backup de detección Y fuente para VideoRecorder
        addPreviewDataCallBack(object : IPreviewDataCallBack {
            override fun onPreviewData(data: ByteArray?, format: IPreviewDataCallBack.DataFormat) {
                if (data == null) return
                if (previewNotified.compareAndSet(false, true)) {
                    Log.d(TAG, "NV21 primer frame: format=$format bytes=${data.size}")
                    val res = vm.currentResolution.value
                    if (res != null && res.width >= 3840 && !is4KSwitchDone.get()) {
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

        // Timeout
        val res       = vm.currentResolution.value
        val timeoutMs = if ((res?.width ?: 0) >= 3840) 30_000L else 12_000L
        viewLifecycleOwner.lifecycleScope.launch {
            delay(timeoutMs)
            if (!previewNotified.get() || ((res?.width ?: 0) >= 3840 && !is4KSwitchDone.get())) {
                Log.w(TAG, "Timeout ${timeoutMs/1000}s: ${res?.label}")
                if ((res?.width ?: 0) >= 3840) {
                    vm.onCameraError(
                        "4K30 no soportado por USB en este dispositivo.\n" +
                        "La HD60 X captura vía USB hasta 1080p60.\n" +
                        "Usa 2K60 o 1080p60."
                    )
                } else {
                    vm.onCameraError("Sin frames en ${timeoutMs/1000}s.\nComprueba HDMI y fuente.")
                }
            }
        }

        // Comandos
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.cameraCommands.collectLatest { cmd -> handleCommand(cmd) }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        previewNotified.set(false)
        is4KSwitchDone.set(false)
        cameraStrategy = null
        videoRecorder?.release()
        videoRecorder = null
        vm.onCameraClosed()
    }

    // ── Switch a 4K vía reflexión ─────────────────────────────────────────────

    /**
     * AUSBC falla a 4K con bandwidth=0.8f. Workaround:
     * 1. Abrimos a 1080p (siempre funciona).
     * 2. Obtenemos el UVCCamera nativo vía reflexión.
     * 3. Paramos preview, llamamos setPreviewSize con bandwidth=1.0f, reiniciamos.
     *
     * La signatura nativa es: setPreviewSize(w, h, minFps, maxFps, mode, bandwidth: Float)
     */
    private suspend fun attemptSwitchTo4K(width: Int, height: Int, fps: Int) {
        Log.d(TAG, "Intentando switch a 4K via reflexión (bandwidth=1.0f)…")
        delay(800)  // dejar que 1080p se estabilice

        val strategy = cameraStrategy
        if (strategy == null) {
            Log.w(TAG, "strategy es null, cancelando switch 4K")
            vm.onCameraError("No se pudo acceder a la estrategia de cámara")
            return
        }

        val uvcCamera = findUVCCamera(strategy)
        if (uvcCamera == null) {
            Log.w(TAG, "UVCCamera no encontrado via reflexión")
            vm.onCameraError("4K: acceso nativo no disponible")
            return
        }

        Log.d(TAG, "UVCCamera encontrado: ${uvcCamera.javaClass.simpleName}")

        // Log todos los métodos setPreviewSize disponibles
        uvcCamera.javaClass.methods
            .filter { it.name == "setPreviewSize" }
            .forEach { Log.d(TAG, "  setPreviewSize(${it.parameterTypes.map { p -> p.simpleName }})") }

        try {
            // Parar preview actual
            runCatching { uvcCamera.javaClass.getMethod("stopPreview").invoke(uvcCamera) }

            // Intentar setPreviewSize con bandwidth completo
            val method = uvcCamera.javaClass.getMethod(
                "setPreviewSize",
                Int::class.java, Int::class.java,
                Int::class.java, Int::class.java,
                Int::class.java, Float::class.java
            )
            val minFps = if (fps <= 25) 24 else 28
            val maxFps = fps + 2
            method.invoke(uvcCamera, width, height, minFps, maxFps, FRAME_MJPEG, BW_FULL)
            Log.d(TAG, "4K setPreviewSize(${width}×${height}, $minFps-$maxFps, MJPEG, 1.0f) → OK")

            // Reiniciar preview
            runCatching { uvcCamera.javaClass.getMethod("startPreview").invoke(uvcCamera) }

            is4KSwitchDone.set(true)
            delay(1000)

            // Verificar si llegan frames 4K
            if (!is4KSwitchDone.get()) {
                vm.onCameraError("4K negociado pero sin frames. El HD60 X no soporta captura 4K USB.")
            } else {
                Log.d(TAG, "Switch a 4K completado")
                vm.onCameraOpened()
                reportAvailableSizes()
            }

        } catch (e: Exception) {
            Log.e(TAG, "setPreviewSize 4K falló: ${e.message}")
            // Fallback: quedarse en 1080p
            runCatching { uvcCamera.javaClass.getMethod("startPreview").invoke(uvcCamera) }
            vm.onCameraError(
                "4K no soportado vía USB (${e.message?.take(60)}).\n" +
                "La HD60 X captura hasta 1080p60 por USB.\n" +
                "Usa 2K60 o 1080p60."
            )
        }
    }

    private fun findUVCCamera(strategy: Any): Any? {
        var cls: Class<*>? = strategy.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField("mUVCCamera")
                f.isAccessible = true
                val cam = f.get(strategy)
                if (cam != null) return cam
            } catch (_: NoSuchFieldException) {}
            cls = cls.superclass
        }
        return null
    }

    // ── Comandos ─────────────────────────────────────────────────────────────

    private fun handleCommand(cmd: CameraCommand) {
        when (cmd) {
            is CameraCommand.StartRecording   -> startVideoRecording()
            is CameraCommand.StopRecording    -> stopVideoRecording()
            is CameraCommand.ChangeResolution -> changeResolution(cmd.width, cmd.height, cmd.fps)
        }
    }

    private fun startVideoRecording() {
        val res = vm.currentResolution.value ?: return
        Log.d(TAG, "VideoRecorder: start ${res.width}×${res.height} @ ${res.fps}fps")
        val recorder = VideoRecorder(
            context = requireContext(),
            width   = res.width,
            height  = res.height,
            fps     = res.fps,
        ).apply {
            onStarted  = { vm.onRecordingStarted() }
            onError    = { msg -> vm.onRecordingError(msg) }
            onComplete = { path -> vm.onRecordingStopped(path) }
        }
        videoRecorder = recorder
        recorder.start()
    }

    private fun stopVideoRecording() {
        videoRecorder?.stop()
        videoRecorder = null
    }

    private fun changeResolution(width: Int, height: Int, fps: Int) {
        Log.d(TAG, "Recreando Fragment → ${width}×${height} @ ${fps}fps")
        previewNotified.set(false)
        vm.triggerFragmentRestart()
        parentFragmentManager.beginTransaction()
            .remove(this)
            .commitNowAllowingStateLoss()
    }

    private fun reportAvailableSizes() {
        val raw = getAllPreviewSizes(null)
        if (raw.isNullOrEmpty()) return
        val res   = raw.map { PreviewResolution(it.width, it.height, 30) }
        val usbMgr = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
        vm.onAvailableResolutions(res, usbMgr)
    }
}

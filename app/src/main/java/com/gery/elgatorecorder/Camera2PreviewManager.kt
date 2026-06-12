package com.gery.elgatorecorder

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface

/**
 * Gestiona el ciclo de vida de Camera2 para la cámara externa (HD60 X).
 *
 * Uso:
 *   1. [startBackgroundThread]
 *   2. [findExternalCameraId] → obtiene el ID de la cámara EXTERNAL
 *   3. [getPreferredSurfaceSize] → obtiene la resolución óptima
 *   4. [openCamera] → abre y arranca el preview
 *   5. [closeCamera] + [stopBackgroundThread] al salir
 */
class Camera2PreviewManager(private val context: Context) {

    companion object {
        private const val TAG = "ElgatoRecorder"

        // Preferencias de resolución en orden de prioridad
        private val PREFERRED_SIZES = listOf(
            Size(1920, 1080),
            Size(1280, 720),
            Size(640, 480),
        )
    }

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    /** Callback para propagar estados a [MainViewModel]. */
    var onStateChanged: ((AppState) -> Unit)? = null

    // ── Thread de fondo ─────────────────────────────────────────────────────

    fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera2BG").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    fun stopBackgroundThread() {
        try {
            backgroundThread?.quitSafely()
            backgroundThread?.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Camera2: error parando thread de fondo", e)
        } finally {
            backgroundThread = null
            backgroundHandler = null
        }
    }

    // ── Enumeración de cámaras ───────────────────────────────────────────────

    /**
     * Recorre todos los IDs de Camera2 y devuelve el primero con LENS_FACING_EXTERNAL.
     * Loguea en detalle todas las cámaras para diagnóstico.
     */
    fun findExternalCameraId(): String? {
        return try {
            val ids = cameraManager.cameraIdList
            Log.d(TAG, "Camera2: ${ids.size} cámara(s) encontrada(s) en el sistema")

            ids.forEach { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val facingStr = when (facing) {
                    CameraMetadata.LENS_FACING_FRONT    -> "FRONT"
                    CameraMetadata.LENS_FACING_BACK     -> "BACK"
                    CameraMetadata.LENS_FACING_EXTERNAL -> "EXTERNAL ← candidata"
                    else                                -> "DESCONOCIDO($facing)"
                }
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
                    ?.joinToString(", ") { "${it.width}x${it.height}" }
                    ?: "sin información de tamaños"
                Log.d(TAG, "  Camera2 id=$id facing=$facingStr sizes=[$sizes]")
            }

            val externalId = ids.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) ==
                    CameraMetadata.LENS_FACING_EXTERNAL
            }

            if (externalId != null) {
                Log.d(TAG, "Camera2: cámara externa seleccionada → id=$externalId")
            } else {
                Log.w(
                    TAG,
                    "Camera2: NINGUNA cámara EXTERNAL encontrada. " +
                        "La HD60 X no ha sido expuesta como cámara UVC por el kernel de Samsung. " +
                        "→ La siguiente fase requerirá AUSBC/UVCCamera."
                )
            }
            externalId
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera2: error al enumerar cámaras", e)
            null
        }
    }

    /**
     * Devuelve la resolución preferida para [cameraId] buscando 1080p primero,
     * luego 720p, 480p, y como último recurso la primera disponible.
     */
    fun getPreferredSurfaceSize(cameraId: String): Size {
        return try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
            Log.d(
                TAG,
                "Camera2: resoluciones disponibles para id=$cameraId: " +
                    sizes.joinToString(", ") { "${it.width}x${it.height}" }
            )
            selectBestSize(sizes)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera2: error obteniendo resoluciones para id=$cameraId", e)
            Size(1280, 720)
        }
    }

    private fun selectBestSize(available: Array<Size>): Size {
        for (preferred in PREFERRED_SIZES) {
            if (available.any { it.width == preferred.width && it.height == preferred.height }) {
                Log.d(TAG, "Camera2: resolución seleccionada → ${preferred.width}x${preferred.height}")
                return preferred
            }
        }
        val fallback = available.firstOrNull() ?: Size(1280, 720)
        Log.d(TAG, "Camera2: resolución fallback → ${fallback.width}x${fallback.height}")
        return fallback
    }

    // ── Apertura y preview ───────────────────────────────────────────────────

    /**
     * Abre [cameraId] y crea una sesión de preview con [surface].
     * La Surface debe tener el tamaño correcto (setDefaultBufferSize) antes de llamar aquí.
     */
    @androidx.annotation.RequiresPermission(android.Manifest.permission.CAMERA)
    fun openCamera(cameraId: String, surface: Surface) {
        onStateChanged?.invoke(AppState.OpeningCamera)
        Log.d(TAG, "Camera2: abriendo cámara id=$cameraId")

        try {
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        Log.d(TAG, "Camera2: cámara abierta correctamente")
                        cameraDevice = camera
                        createPreviewSession(camera, surface)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        Log.w(TAG, "Camera2: cámara desconectada")
                        camera.close()
                        cameraDevice = null
                        onStateChanged?.invoke(AppState.Error("Cámara desconectada inesperadamente"))
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        val msg = when (error) {
                            ERROR_CAMERA_DEVICE   -> "ERROR_CAMERA_DEVICE"
                            ERROR_CAMERA_DISABLED -> "ERROR_CAMERA_DISABLED"
                            ERROR_CAMERA_IN_USE   -> "ERROR_CAMERA_IN_USE"
                            ERROR_CAMERA_SERVICE  -> "ERROR_CAMERA_SERVICE"
                            ERROR_MAX_CAMERAS_IN_USE -> "ERROR_MAX_CAMERAS_IN_USE"
                            else                  -> "ERROR_DESCONOCIDO($error)"
                        }
                        Log.e(TAG, "Camera2: error de hardware → $msg")
                        camera.close()
                        cameraDevice = null
                        onStateChanged?.invoke(AppState.Error("Error de cámara: $msg"))
                    }
                },
                backgroundHandler,
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera2: CameraAccessException al abrir id=$cameraId", e)
            onStateChanged?.invoke(AppState.Error("CameraAccessException: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Camera2: ID de cámara inválido=$cameraId", e)
            onStateChanged?.invoke(AppState.Error("ID de cámara inválido: $cameraId"))
        }
    }

    private fun createPreviewSession(camera: CameraDevice, surface: Surface) {
        Log.d(TAG, "Camera2: creando sesión de captura")
        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "Camera2: sesión configurada — iniciando preview")
                        captureSession = session
                        startRepeatingRequest(session, camera, surface)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera2: fallo al configurar la sesión")
                        onStateChanged?.invoke(
                            AppState.Error(
                                "No se pudo configurar la sesión Camera2. " +
                                    "Posible: resolución no soportada o sin señal HDMI."
                            )
                        )
                    }
                },
                backgroundHandler,
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera2: error creando sesión", e)
            onStateChanged?.invoke(AppState.Error("Error creando sesión: ${e.message}"))
        }
    }

    private fun startRepeatingRequest(
        session: CameraCaptureSession,
        camera: CameraDevice,
        surface: Surface,
    ) {
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            }.build()

            session.setRepeatingRequest(request, null, backgroundHandler)

            // Obtener resolución efectiva para informar en la UI
            val chars = cameraManager.getCameraCharacteristics(camera.id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
            val size = selectBestSize(sizes)

            Log.d(TAG, "Camera2: preview iniciado — ${size.width}x${size.height}")
            onStateChanged?.invoke(AppState.PreviewStarted)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera2: error iniciando repeating request", e)
            onStateChanged?.invoke(AppState.Error("Error iniciando preview: ${e.message}"))
        }
    }

    // ── Cierre ───────────────────────────────────────────────────────────────

    fun closeCamera() {
        Log.d(TAG, "Camera2: cerrando cámara y sesión")
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.e(TAG, "Camera2: error durante el cierre", e)
        }
    }
}

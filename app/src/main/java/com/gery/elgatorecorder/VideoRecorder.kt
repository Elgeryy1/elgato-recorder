package com.gery.elgatorecorder

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Grabador de video independiente de AUSBC.
 *
 * AUSBC 3.2.7 usa MediaStore con la columna _data (bloqueada en Android 10+), así que
 * implementamos nuestra propia pipeline:
 *   NV21 frames (de addPreviewDataCallBack) → MediaCodec H.264 → MediaMuxer → MediaStore
 *
 * Usa MediaMuxer(FileDescriptor) para escribir directamente al archivo de MediaStore
 * sin necesidad de permisos especiales ni de la columna _data.
 */
class VideoRecorder(
    private val context: Context,
    val width   : Int,
    val height  : Int,
    val fps     : Int = 30,
) {
    companion object {
        private const val TAG        = "ElgatoRecorder"
        private const val BITRATE    = 10_000_000   // 10 Mbps
        private const val MIME_TYPE  = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val MAX_QUEUE  = 8            // frames en cola max
    }

    // Callbacks
    var onStarted  : () -> Unit         = {}
    var onError    : (String) -> Unit   = {}
    var onComplete : (String) -> Unit   = {}

    private var encoder    : MediaCodec?             = null
    private var muxer      : MediaMuxer?             = null
    private var pfd        : ParcelFileDescriptor?   = null
    private var mediaUri   : Uri?                    = null
    private var videoTrack : Int                     = -1
    private var outputPath : String                  = ""

    private val isRecording = AtomicBoolean(false)
    private val muxerStarted = AtomicBoolean(false)

    // Cola de frames para no bloquear el hilo de cámara
    private val frameQueue = ArrayBlockingQueue<ByteArray>(MAX_QUEUE)

    private val encThread = HandlerThread("VidEncoder").also { it.start() }
    private val encHandler = Handler(encThread.looper)

    private var startTimeUs = 0L
    private val bufferInfo  = MediaCodec.BufferInfo()

    // ── Inicio ────────────────────────────────────────────────────────────────

    fun start() {
        if (isRecording.get()) return
        encHandler.post {
            try {
                initMuxer()
                initEncoder()
                isRecording.set(true)
                startTimeUs = 0L
                Log.d(TAG, "VideoRecorder: iniciado ${width}x${height} @ ${fps}fps")
                onStarted()
            } catch (e: Exception) {
                Log.e(TAG, "VideoRecorder: error al iniciar", e)
                cleanup()
                onError("No se pudo iniciar grabación: ${e.message}")
            }
        }
    }

    // ── Recibir frame NV21 desde AUSBC ────────────────────────────────────────

    fun feedNv21Frame(data: ByteArray) {
        if (!isRecording.get()) return
        // Copiar el frame (el buffer de AUSBC se reutiliza)
        val copy = data.copyOf()
        if (!frameQueue.offer(copy)) {
            // Cola llena: descartar frame antiguo y añadir nuevo
            frameQueue.poll()
            frameQueue.offer(copy)
        }
        encHandler.post { encodeNextFrame() }
    }

    private fun encodeNextFrame() {
        val frame = frameQueue.poll() ?: return
        val enc   = encoder ?: return

        val inputIdx = enc.dequeueInputBuffer(10_000)
        if (inputIdx < 0) return

        val buf = enc.getInputBuffer(inputIdx) ?: return
        buf.clear()

        // NV21 → NV12: intercambiar planos U y V
        val ySize = width * height
        buf.put(frame, 0, ySize)
        val uvIn  = frame
        val uvOut = ByteArray(frame.size - ySize)
        var i = 0
        while (i < uvOut.size - 1) {
            uvOut[i]     = uvIn[ySize + i + 1]  // U (de V en NV21)
            uvOut[i + 1] = uvIn[ySize + i]       // V (de U en NV21)
            i += 2
        }
        buf.put(uvOut)

        if (startTimeUs == 0L) startTimeUs = System.nanoTime() / 1000
        val pts = System.nanoTime() / 1000 - startTimeUs

        enc.queueInputBuffer(inputIdx, 0, ySize + uvOut.size, pts, 0)
        drainEncoder(false)
    }

    // ── Parar ─────────────────────────────────────────────────────────────────

    fun stop() {
        if (!isRecording.getAndSet(false)) return
        encHandler.post {
            try {
                drainEncoder(true)
            } catch (e: Exception) {
                Log.w(TAG, "VideoRecorder: drain error al parar", e)
            }
            val path = outputPath
            cleanup()
            if (path.isNotEmpty()) {
                Log.d(TAG, "VideoRecorder: guardado → $path")
                onComplete(path)
            } else {
                onError("Ruta de salida vacía")
            }
        }
    }

    // ── Helpers internos ──────────────────────────────────────────────────────

    private fun initMuxer() {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "REC_$ts.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: MediaStore con RELATIVE_PATH (sin _data)
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/ElgatoRecorder")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: throw Exception("MediaStore insert devolvió null")
            mediaUri = uri
            outputPath = fileName

            val fd = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw Exception("No se pudo abrir FileDescriptor")
            pfd = fd
            muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            // Android 8-9: path en app-specific external storage
            val dir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "ElgatoRecorder"
            ).also { it.mkdirs() }
            val file = File(dir, fileName)
            outputPath = file.absolutePath
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }
        Log.d(TAG, "VideoRecorder: muxer creado → $outputPath")
    }

    private fun initEncoder() {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE,   BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder = MediaCodec.createEncoderByType(MIME_TYPE).also {
            it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
        }
        Log.d(TAG, "VideoRecorder: encoder H.264 iniciado")
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val enc = encoder ?: return
        val mux = muxer  ?: return

        if (endOfStream) {
            enc.signalEndOfInputStream()
        }

        while (true) {
            val idx = enc.dequeueOutputBuffer(bufferInfo, if (endOfStream) 50_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted.get()) {
                        videoTrack = mux.addTrack(enc.outputFormat)
                        mux.start()
                        muxerStarted.set(true)
                        Log.d(TAG, "VideoRecorder: muxer iniciado, track=$videoTrack")
                    }
                }
                idx >= 0 -> {
                    val buf = enc.getOutputBuffer(idx)
                    if (buf != null && bufferInfo.size > 0
                        && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        && muxerStarted.get()) {
                        mux.writeSampleData(videoTrack, buf, bufferInfo)
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                else -> break
            }
        }
    }

    private fun cleanup() {
        try {
            if (muxerStarted.getAndSet(false)) muxer?.stop()
        } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        try { encoder?.stop()  } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        muxer   = null
        encoder = null
        pfd     = null
        videoTrack = -1
        frameQueue.clear()

        // Marcar IS_PENDING=0 para que el video sea visible en la galería
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaUri?.let { uri ->
                try {
                    val v = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    context.contentResolver.update(uri, v, null, null)
                    Log.d(TAG, "VideoRecorder: IS_PENDING=0, video visible en galería")
                } catch (e: Exception) {
                    Log.w(TAG, "VideoRecorder: no se pudo actualizar IS_PENDING: ${e.message}")
                }
            }
        }
    }

    fun release() {
        isRecording.set(false)
        encThread.quitSafely()
        cleanup()
    }
}

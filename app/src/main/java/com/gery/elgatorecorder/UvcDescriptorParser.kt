package com.gery.elgatorecorder

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Parsea los descriptores USB brutos de un dispositivo UVC para extraer
 * las resoluciones soportadas y sus frame rates (intervalos de frame).
 *
 * El estándar UVC define Class-Specific VS Interface descriptors:
 *   VS_FORMAT_MJPEG (0x10) → VS_FRAME_MJPEG (0x11)
 *   VS_FORMAT_UNCOMPRESSED (0x06) → VS_FRAME_UNCOMPRESSED (0x05)
 *
 * Frame interval en unidades de 100ns → FPS = 10_000_000 / interval
 */
object UvcDescriptorParser {

    private const val TAG = "ElgatoRecorder"

    // Descriptor types
    private const val DT_INTERFACE = 0x04
    private const val DT_CS_INTERFACE = 0x24

    // Video class codes
    private const val CC_VIDEO = 0x0E
    private const val SC_VIDEOSTREAMING = 0x02

    // VS subtype codes
    private const val VS_FRAME_UNCOMPRESSED = 0x05
    private const val VS_FRAME_MJPEG = 0x11
    private const val VS_FRAME_FRAME_BASED = 0x14

    private const val INTERVAL_100NS = 10_000_000L

    data class UvcFormat(
        val width : Int,
        val height: Int,
        val fpsList: List<Int>,   // ordenado de mayor a menor
    ) {
        val key: Long get() = width.toLong() shl 32 or height.toLong()
    }

    /**
     * Abre una conexión temporal al dispositivo para leer los descriptores raw.
     * No reclama ninguna interfaz → compatible con AUSBC teniendo el dispositivo abierto.
     */
    fun getFormats(usbManager: UsbManager, device: UsbDevice): List<UvcFormat> {
        val connection = usbManager.openDevice(device) ?: run {
            Log.w(TAG, "UvcParser: no se pudo abrir conexión para leer descriptores")
            return emptyList()
        }
        return try {
            val raw = connection.rawDescriptors ?: run {
                Log.w(TAG, "UvcParser: rawDescriptors devolvió null")
                return emptyList()
            }
            Log.d(TAG, "UvcParser: ${raw.size} bytes de descriptor")
            parse(raw)
        } finally {
            connection.close()
        }
    }

    private fun parse(raw: ByteArray): List<UvcFormat> {
        val formatsMap = mutableMapOf<Long, MutableSet<Int>>() // key → fps set
        var i = 0
        var inVideoStreaming = false

        while (i < raw.size) {
            val bLength = raw[i].toUnsigned()
            if (bLength == 0 || i + bLength > raw.size) break

            val type    = raw[i + 1].toUnsigned()
            val subtype = if (bLength > 2) raw[i + 2].toUnsigned() else 0

            // Detectar si estamos en una Video Streaming interface
            if (type == DT_INTERFACE && bLength >= 9) {
                val cls    = raw[i + 5].toUnsigned()
                val subcls = raw[i + 6].toUnsigned()
                inVideoStreaming = (cls == CC_VIDEO && subcls == SC_VIDEOSTREAMING)
            }

            if (inVideoStreaming && type == DT_CS_INTERFACE) {
                if (subtype == VS_FRAME_MJPEG ||
                    subtype == VS_FRAME_UNCOMPRESSED ||
                    subtype == VS_FRAME_FRAME_BASED) {
                    parseFrameDesc(raw, i, bLength)?.let { (w, h, fps) ->
                        val key = w.toLong() shl 32 or h.toLong()
                        formatsMap.getOrPut(key) { mutableSetOf() }.addAll(fps)
                    }
                }
            }

            i += bLength
        }

        val result = formatsMap.map { (key, fpsSet) ->
            UvcFormat(
                width  = (key shr 32).toInt(),
                height = (key and 0xFFFFFFFFL).toInt(),
                fpsList = fpsSet.sortedDescending(),
            )
        }
        Log.d(TAG, "UvcParser: ${result.size} formatos encontrados")
        result.forEach { Log.d(TAG, "  ${it.width}x${it.height} @ ${it.fpsList}fps") }
        return result
    }

    /** Devuelve (width, height, fpsList) o null si el descriptor está incompleto. */
    private fun parseFrameDesc(
        raw   : ByteArray,
        offset: Int,
        length: Int,
    ): Triple<Int, Int, List<Int>>? {
        // Campos fijos: offset 5-6 = wWidth, 7-8 = wHeight, 25 = bFrameIntervalType
        if (length < 26 || offset + 26 > raw.size) return null

        val w = readU16(raw, offset + 5)
        val h = readU16(raw, offset + 7)
        if (w <= 0 || h <= 0) return null

        val intervalType = raw[offset + 25].toUnsigned()
        val fpsList = mutableListOf<Int>()

        if (intervalType == 0) {
            // Continuo: min (26), max (30), step (34)
            if (offset + 38 > raw.size) return null
            val minI = readU32(raw, offset + 26)
            val maxI = readU32(raw, offset + 30)
            // Verificar fps estándar dentro del rango
            for (fps in listOf(240, 120, 60, 50, 30, 25, 15, 10)) {
                val interval = INTERVAL_100NS / fps
                if (interval in minI..maxI) fpsList.add(fps)
            }
        } else {
            // Discreto: intervalType = número de entradas
            for (j in 0 until intervalType) {
                val pos = offset + 26 + j * 4
                if (pos + 4 > raw.size) break
                val interval = readU32(raw, pos)
                if (interval > 0L) {
                    val fps = (INTERVAL_100NS / interval).toInt()
                    if (fps in 1..240) fpsList.add(fps)
                }
            }
        }

        return if (fpsList.isNotEmpty()) Triple(w, h, fpsList.distinct()) else null
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        (data[offset].toUnsigned()) or (data[offset + 1].toUnsigned() shl 8)

    private fun readU32(data: ByteArray, offset: Int): Long =
        data[offset].toULong() or
        (data[offset + 1].toULong() shl 8) or
        (data[offset + 2].toULong() shl 16) or
        (data[offset + 3].toULong() shl 24)

    private fun Byte.toUnsigned(): Int = this.toInt() and 0xFF
    private fun Byte.toULong(): Long  = this.toLong() and 0xFF
}

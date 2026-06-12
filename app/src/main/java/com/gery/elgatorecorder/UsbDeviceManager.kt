package com.gery.elgatorecorder

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gestiona la detección de dispositivos USB y el flujo de permisos.
 *
 * Prioriza dispositivos con vendorId = 4057 (Elgato / Corsair 0x0FD9).
 * No hardcodea productId para aceptar cualquier modelo Elgato.
 */
class UsbDeviceManager(private val context: Context) {

    companion object {
        private const val TAG = "ElgatoRecorder"
        const val ELGATO_VENDOR_ID = 4057          // 0x0FD9
        private const val ACTION_USB_PERMISSION =
            "com.gery.elgatorecorder.USB_PERMISSION"
    }

    private val usbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    // Último dispositivo Elgato detectado (null = desconectado)
    private val _elgatoDevice = MutableStateFlow<UsbDevice?>(null)
    val elgatoDevice: StateFlow<UsbDevice?> = _elgatoDevice.asStateFlow()

    // null = sin resultado todavía, true = concedido, false = denegado
    private val _permissionGranted = MutableStateFlow<Boolean?>(null)
    val permissionGranted: StateFlow<Boolean?> = _permissionGranted.asStateFlow()

    private var usbSystemReceiver: BroadcastReceiver? = null
    private var permissionReceiver: BroadcastReceiver? = null

    /** Registra receptores dinámicos. Llamar en onStart() / init del ViewModel. */
    fun registerReceivers() {
        // Receptor para eventos del sistema USB (attach/detach)
        usbSystemReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        Log.d(TAG, "USB: dispositivo conectado (broadcast)")
                        scanForElgatoDevice()
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device = intent.usbDevice()
                        Log.d(TAG, "USB: dispositivo desconectado → ${device?.deviceName}")
                        if (device?.vendorId == ELGATO_VENDOR_ID) {
                            _elgatoDevice.value = null
                            _permissionGranted.value = null
                        }
                    }
                }
            }
        }
        val systemFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // Broadcasts del sistema → RECEIVER_EXPORTED en API 33+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbSystemReceiver, systemFilter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(usbSystemReceiver, systemFilter)
        }

        // Receptor para el resultado del diálogo de permiso USB
        permissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val device = intent.usbDevice()
                val granted = intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED, false
                )
                Log.d(
                    TAG,
                    "USB: permiso para ${device?.deviceName} → granted=$granted"
                )
                _permissionGranted.value = granted
            }
        }
        val permFilter = IntentFilter(ACTION_USB_PERMISSION)
        // Broadcast propio → RECEIVER_NOT_EXPORTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                permissionReceiver, permFilter, Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(permissionReceiver, permFilter)
        }
    }

    /** Desregistra receptores. Llamar en onStop() / onCleared() del ViewModel. */
    fun unregisterReceivers() {
        listOf(usbSystemReceiver, permissionReceiver).forEach { receiver ->
            receiver?.let {
                try {
                    context.unregisterReceiver(it)
                } catch (_: IllegalArgumentException) {
                    // ya desregistrado
                }
            }
        }
        usbSystemReceiver = null
        permissionReceiver = null
    }

    /**
     * Escanea los dispositivos USB actualmente conectados.
     * Devuelve el primero con vendorId = 4057, o null si no hay ninguno.
     */
    fun scanForElgatoDevice(): UsbDevice? {
        val devices = usbManager.deviceList
        Log.d(TAG, "USB: scan — ${devices.size} dispositivos encontrados")
        devices.forEach { (_, device) ->
            Log.d(
                TAG,
                "  USB dev: name=${device.deviceName}" +
                    " vendorId=${device.vendorId} (0x${device.vendorId.toString(16).uppercase()})" +
                    " productId=${device.productId} (0x${device.productId.toString(16).uppercase()})" +
                    " class=${device.deviceClass}" +
                    " subclass=${device.deviceSubclass}" +
                    " protocol=${device.deviceProtocol}" +
                    " interfaces=${device.interfaceCount}"
            )
        }

        val elgato = devices.values.firstOrNull { it.vendorId == ELGATO_VENDOR_ID }
        if (elgato != null) {
            Log.d(
                TAG,
                "USB: HD60 X detectada → name=${elgato.deviceName}" +
                    " productId=${elgato.productId} interfaces=${elgato.interfaceCount}"
            )
            _elgatoDevice.value = elgato
        } else {
            Log.d(TAG, "USB: ningún dispositivo Elgato (vendorId=$ELGATO_VENDOR_ID) encontrado")
            _elgatoDevice.value = null
        }
        return elgato
    }

    /** Lanza el diálogo del sistema pidiendo permiso USB para [device]. */
    fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), flags
        )
        Log.d(TAG, "USB: solicitando permiso para ${device.deviceName}")
        usbManager.requestPermission(device, pi)
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    // Compatibilidad getParcelableExtra API 33+
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}

package com.gery.elgatorecorder

sealed class AppState {
    data object WaitingForDevice : AppState()

    data class UsbDeviceDetected(
        val deviceName: String,
        val vendorId: Int,
        val productId: Int,
        val deviceClass: Int,
        val deviceSubclass: Int,
        val deviceProtocol: Int,
        val interfaceCount: Int,
    ) : AppState()

    /** AUSBC está mostrando el diálogo de permiso USB al usuario. */
    data object RequestingUsbPermission : AppState()

    /** AUSBC está abriendo el stream UVC. */
    data object OpeningCamera : AppState()

    /** Preview activo — AUSBC ha abierto la cámara y está recibiendo frames. */
    data object PreviewStarted : AppState()

    /** Error recuperable — el usuario puede reintentar. */
    data class Error(val message: String) : AppState()
}

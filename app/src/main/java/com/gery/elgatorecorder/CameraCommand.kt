package com.gery.elgatorecorder

sealed class CameraCommand {
    data class StartRecording(val path: String) : CameraCommand()
    data object StopRecording : CameraCommand()
    data class ChangeResolution(val width: Int, val height: Int, val fps: Int) : CameraCommand()
}

data class PreviewResolution(
    val width : Int,
    val height: Int,
    val fps   : Int,
) {
    val pixels: Int get() = width * height

    /**
     * 120fps: MediaCodec H.264 no garantiza grabación por encima de 60fps.
     * 4K: se intenta siempre (el HD60 X lo lista en su descriptor UVC).
     */
    val isPreviewOnly: Boolean get() = fps > 60

    val canRecord: Boolean get() = !isPreviewOnly

    val label: String
        get() = buildString {
            when {
                width >= 3840 -> append("4K")
                width >= 2560 -> append("2K")
                width >= 1920 -> append("1080p")
                width >= 1280 -> append("720p")
                else          -> append("${height}p")
            }
            append("  ${fps}fps")
            append("  (${width}×${height})")
            if (isPreviewOnly) append("  ⚠")
        }

    val previewOnlyReason: String?
        get() = when {
            fps > 60 -> "Grabación limitada a 60fps por MediaCodec.\nEl preview a ${fps}fps funciona,\npero no se puede grabar."
            else     -> null
        }
}

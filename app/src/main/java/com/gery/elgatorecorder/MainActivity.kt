package com.gery.elgatorecorder

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gery.elgatorecorder.ui.theme.ElgatoRecorderTheme

private const val TAG          = "ElgatoRecorder"
private const val FRAGMENT_TAG = "elgato_camera"
private val CONTAINER_ID       = android.view.View.generateViewId()

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ElgatoRecorderTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    ElgatoRecorderScreen()
                }
            }
        }
    }
}

@Composable
fun ElgatoRecorderScreen(vm: MainViewModel = viewModel()) {
    val context           = LocalContext.current
    val appState          by vm.appState.collectAsState()
    val isRecording       by vm.isRecording.collectAsState()
    val recordingSeconds  by vm.recordingSeconds.collectAsState()
    val availableRes      by vm.availableResolutions.collectAsState()
    val currentRes        by vm.currentResolution.collectAsState()
    val showPicker        by vm.showResolutionPicker.collectAsState()
    val lastPath          by vm.lastRecordingPath.collectAsState()
    val restartTrigger    by vm.fragmentRestartTrigger.collectAsState()

    // Re-añadir el Fragment cuando se elimina a sí mismo para cambiar resolución.
    // El trigger cambia ANTES de la eliminación, así que cuando el LaunchedEffect
    // se ejecuta el Fragment ya fue removido y puede re-añadirse con la nueva res.
    LaunchedEffect(restartTrigger) {
        if (restartTrigger > 0) {
            delay(150) // dar tiempo al commitNow de remove para completarse
            val fm = (context as FragmentActivity).supportFragmentManager
            if (fm.findFragmentByTag(FRAGMENT_TAG) == null) {
                Log.d(TAG, "LaunchedEffect: re-añadiendo Fragment (trigger=$restartTrigger)")
                fm.beginTransaction()
                    .add(CONTAINER_ID, ElgatoCameraFragment(), FRAGMENT_TAG)
                    .commitNow()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {

        // ── Camera preview (Fragment AUSBC) ───────────────────────────────────
        AndroidView(
            factory  = { ctx -> FragmentContainerView(ctx).apply { id = CONTAINER_ID } },
            update   = { fcv ->
                val fm = (fcv.context as FragmentActivity).supportFragmentManager
                if (fm.findFragmentByTag(FRAGMENT_TAG) == null) {
                    Log.d(TAG, "AndroidView update: añadiendo ElgatoCameraFragment")
                    fm.beginTransaction()
                        .add(fcv.id, ElgatoCameraFragment(), FRAGMENT_TAG)
                        .commitNow()
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ── Panel inferior ─────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .systemBarsPadding()
                .background(Color(0xCC000000))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Estado + timer
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text       = appState.statusLabel(),
                    color      = appState.statusColor(),
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier   = Modifier.weight(1f),
                )
                if (isRecording) {
                    RecordingIndicator()
                    Text(
                        text     = vm.formatDuration(recordingSeconds),
                        color    = Color.Red,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Resolución actual
            if (currentRes != null || availableRes.isNotEmpty()) {
                Text(
                    text     = currentRes?.label ?: "Detectando resolución…",
                    color    = Color(0xFFAAAAAA),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // Info detallada solo cuando hay error o USB detectado
            appState.detailText()?.let {
                Text(text = it, color = Color(0xFFCCCCCC), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }

            // Último archivo grabado
            lastPath?.let {
                Text(
                    text     = "Guardado: ${it.substringAfterLast('/')}",
                    color    = Color(0xFF66BB6A),
                    fontSize = 10.sp,
                )
            }

            // Botones de acción
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                // Botón REC / STOP
                val previewActive = appState is AppState.PreviewStarted
                val resIsRecordable = currentRes?.canRecord != false
                Button(
                    onClick = { if (isRecording) vm.stopRecording() else vm.startRecording() },
                    enabled = previewActive && (isRecording || resIsRecordable),
                    colors  = ButtonDefaults.buttonColors(
                        containerColor = when {
                            isRecording      -> Color(0xFFCC0000)
                            resIsRecordable  -> Color(0xFF8B0000)
                            else             -> Color(0xFF555555)
                        },
                        disabledContainerColor = Color(0xFF333333),
                    ),
                    modifier = Modifier.height(40.dp),
                ) {
                    Text(
                        text = when {
                            isRecording         -> "■  STOP"
                            !resIsRecordable    -> "⚠  REC"
                            else                -> "●  REC"
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                // Botón selector de resolución
                OutlinedButton(
                    onClick  = { vm.toggleResolutionPicker() },
                    enabled  = previewActive && !isRecording && availableRes.isNotEmpty(),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    modifier = Modifier.height(40.dp),
                ) {
                    Text(text = "Resolución ▾", fontSize = 12.sp)
                }

                // Reintentar (solo cuando no hay preview)
                if (!previewActive && !isRecording) {
                    OutlinedButton(
                        onClick = { vm.retry() },
                        colors  = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF90A4AE)),
                        modifier = Modifier.height(40.dp),
                    ) {
                        Text(text = "Reintentar", fontSize = 12.sp)
                    }
                }
            }
        }

        // ── Selector de resolución (overlay) ─────────────────────────────────
        if (showPicker && availableRes.isNotEmpty()) {
            ResolutionPickerOverlay(
                resolutions = availableRes,
                current     = currentRes,
                onSelect    = { vm.selectResolution(it) },
                onDismiss   = { vm.dismissResolutionPicker() },
            )
        }
    }
}

// ── Indicador de grabación parpadeante ────────────────────────────────────────

@Composable
private fun RecordingIndicator() {
    val inf = rememberInfiniteTransition(label = "rec")
    val alpha by inf.animateFloat(
        initialValue  = 1f,
        targetValue   = 0.1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label         = "alpha",
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .alpha(alpha)
            .background(Color.Red, CircleShape)
    )
}

// ── Picker de resolución ──────────────────────────────────────────────────────

@Composable
private fun ResolutionPickerOverlay(
    resolutions: List<PreviewResolution>,
    current    : PreviewResolution?,
    onSelect   : (PreviewResolution) -> Unit,
    onDismiss  : () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x88000000))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF1E1E1E),
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .clickable { /* consume click, no dismiss */ },
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text     = "Resolución de preview",
                    color    = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider(color = Color(0xFF333333))

                LazyColumn {
                    items(resolutions) { res ->
                        val isSelected = res == current
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(res) }
                                .background(if (isSelected) Color(0xFF1565C0) else Color.Transparent)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text     = res.label,
                                    color    = when {
                                        isSelected        -> Color.White
                                        res.isPreviewOnly -> Color(0xFFFFAA44)
                                        else              -> Color(0xFFDDDDDD)
                                    },
                                    fontSize   = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier   = Modifier.weight(1f),
                                )
                                if (isSelected) {
                                    Text("✓", color = Color.White, fontSize = 13.sp)
                                }
                            }
                            if (res.isPreviewOnly && isSelected) {
                                Text(
                                    text     = res.previewOnlyReason ?: "",
                                    color    = Color(0xFFFFCC66),
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF333333))
                Text(
                    text     = "Cancelar",
                    color    = Color(0xFF90A4AE),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

// ── Extensiones AppState ──────────────────────────────────────────────────────

private fun AppState.statusLabel(): String = when (this) {
    AppState.WaitingForDevice        -> "Esperando HD60 X…"
    is AppState.UsbDeviceDetected    -> "HD60 X detectada — vendorId=$vendorId  productId=$productId"
    AppState.RequestingUsbPermission -> "Solicitando permiso USB…"
    AppState.OpeningCamera           -> "Abriendo stream UVC…"
    AppState.PreviewStarted          -> "Preview activo — HD60 X"
    is AppState.Error                -> "Error: $message"
}

private fun AppState.statusColor(): Color = when (this) {
    AppState.PreviewStarted   -> Color(0xFF66BB6A)
    is AppState.Error         -> Color(0xFFEF5350)
    AppState.WaitingForDevice -> Color(0xFF90A4AE)
    else                      -> Color(0xFF29B6F6)
}

private fun AppState.detailText(): String? = when (this) {
    is AppState.UsbDeviceDetected ->
        "name: $deviceName\n" +
        "vendorId: $vendorId (0x${vendorId.toString(16).uppercase()})  " +
        "productId: $productId (0x${productId.toString(16).uppercase()})\n" +
        "interfaces: $interfaceCount"
    is AppState.Error ->
        "Revisa: adb logcat -s ElgatoRecorder"
    else -> null
}

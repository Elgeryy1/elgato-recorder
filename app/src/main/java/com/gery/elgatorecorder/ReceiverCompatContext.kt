package com.gery.elgatorecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * ContextWrapper que:
 *  1. Implementa LifecycleOwner delegando al Activity envuelto (CameraClient lo requiere).
 *  2. Añade RECEIVER_EXPORTED a registerReceiver() sin flags (requerido en Android 13+).
 *  3. Propaga el parche a getApplicationContext() para que USBMonitor, que internamente
 *     guarda context.getApplicationContext(), también use el registerReceiver() parcheado.
 */
class ReceiverCompatContext(base: Context) : ContextWrapper(base), LifecycleOwner {

    companion object {
        private const val TAG = "ElgatoRecorder"
    }

    // ── LifecycleOwner ────────────────────────────────────────────────────────

    override val lifecycle: Lifecycle
        get() {
            // Busca un LifecycleOwner subiendo la cadena de contextos
            var ctx: Context = baseContext
            while (true) {
                if (ctx is LifecycleOwner) return ctx.lifecycle
                if (ctx is ContextWrapper) ctx = ctx.baseContext else break
            }
            error("ReceiverCompatContext: no se encontró LifecycleOwner en la cadena de contextos")
        }

    // ── getApplicationContext ─────────────────────────────────────────────────

    /**
     * USBMonitor hace context.getApplicationContext() y almacena ese resultado.
     * Devolvemos otro ReceiverCompatContext para que el registerReceiver parcheado
     * siga activo aunque se use el Application context.
     */
    override fun getApplicationContext(): Context =
        ReceiverCompatContext(super.getApplicationContext())

    // ── registerReceiver parcheados ───────────────────────────────────────────

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.d(TAG, "ReceiverCompatContext: +RECEIVER_EXPORTED")
            super.registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            super.registerReceiver(receiver, filter)
        }
    }

    override fun registerReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter?,
        broadcastPermission: String?,
        scheduler: android.os.Handler?,
    ): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            super.registerReceiver(receiver, filter, broadcastPermission, scheduler, RECEIVER_EXPORTED)
        } else {
            super.registerReceiver(receiver, filter, broadcastPermission, scheduler)
        }
    }
}

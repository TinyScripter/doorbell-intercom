package de.kleinabersmart.doorbellintercom

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration.Companion.milliseconds

/**
 * Baut beim Klingeln bei Bedarf den WireGuard-Tunnel auf und danach wieder ab –
 * per Intent an die WireGuard-App (Voraussetzung dort: "Fernsteuerungs-Apps
 * zulassen"; hier: Berechtigung CONTROL_TUNNELS, die WireGuard als "gefährlich"
 * einstuft und die deshalb zur Laufzeit erteilt werden muss).
 *
 * Ob ein Tunnel nötig ist, zeigt ein TCP-Test auf den RTSP-Port der Kamera:
 * erreichbar = Heim-WLAN oder VPN schon aktiv, nichts zu tun. Getrennt wird nur
 * ein Tunnel, den diese App selbst aufgebaut hat – ein vorher schon laufendes
 * VPN bleibt unangetastet.
 */
object WireGuardTunnel {

    const val PERMISSION_CONTROL_TUNNELS = "com.wireguard.android.permission.CONTROL_TUNNELS"

    private const val TAG = "WireGuardTunnel"
    private const val WIREGUARD_PACKAGE = "com.wireguard.android"
    private const val ACTION_TUNNEL_UP = "com.wireguard.android.action.SET_TUNNEL_UP"
    private const val ACTION_TUNNEL_DOWN = "com.wireguard.android.action.SET_TUNNEL_DOWN"
    private const val EXTRA_TUNNEL = "tunnel"

    private const val CAMERA_RTSP_PORT = 554
    private const val CONNECT_TIMEOUT_MS = 700
    private const val POLL_INTERVAL_MS = 500L
    private const val TUNNEL_READY_TIMEOUT_MS = 10_000L

    /**
     * Notbremse: wird die App während des Anrufs vom System beendet, würde der Tunnel
     * sonst dauerhaft offen bleiben. Der Alarm schickt das Trennen dann von selbst.
     */
    private const val SAFETY_DISCONNECT_MS = 15 * 60_000L

    @Volatile
    private var startedByApp = false

    /**
     * Bildschirme, die den Tunnel gerade brauchen (Klingelbildschirm, Hauptbildschirm), als
     * eigenes Token-Objekt je Bildschirm (siehe LiveViewController) – eindeutig und ohne die
     * Activity selbst festzuhalten. Getrennt wird erst, wenn der letzte fertig ist – sonst
     * würde das Ende eines Anrufs dem geöffneten Hauptbildschirm den Tunnel wegnehmen.
     */
    private val owners = mutableSetOf<Any>()

    /**
     * Stellt für [owner] sicher, dass die Kamera erreichbar ist, und baut dafür bei Bedarf
     * den Tunnel auf. Kehrt danach zurück – auch ohne Erfolg; das Live-Bild meldet dann selbst
     * den Verbindungsfehler. Ist die Funktion in den Einstellungen aus, sofort.
     *
     * Wartet bis zu TUNNEL_READY_TIMEOUT_MS; die Netzwerk-Tests laufen auf Dispatchers.IO.
     * Gibt der Besitzer den Tunnel währenddessen schon wieder frei (App verlassen, aufgelegt)
     * oder wird die Coroutine abgebrochen, wird nicht weiter gewartet.
     *
     * [onStartingTunnel] wird nur aufgerufen, wenn tatsächlich ein Tunnel aufgebaut wird, und
     * zwar auf dem Thread des Aufrufers. Das Gegenstück ist [release].
     */
    suspend fun prepare(context: Context, owner: Any, onStartingTunnel: () -> Unit) {
        val config = AppConfig(context)
        if (!config.wireguardAutoVpn) return
        synchronized(this) { owners.add(owner) }

        val tunnel = config.wireguardTunnel
        if (tunnel.isBlank() || config.cameraHost.isBlank()) return
        if (isReachableAsync(config.cameraHost) || isVpnActive(context)) return

        if (context.checkSelfPermission(PERMISSION_CONTROL_TUNNELS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Berechtigung CONTROL_TUNNELS fehlt – Tunnel wird nicht aufgebaut")
            return
        }

        val startedAt = SystemClock.elapsedRealtime()
        // Gemeinsam mit release() synchronisiert: entweder sieht release() den gestarteten
        // Tunnel und trennt ihn, oder er wird hier gar nicht erst gestartet.
        synchronized(this) {
            if (owner !in owners) return
            context.sendBroadcast(tunnelIntent(ACTION_TUNNEL_UP, tunnel))
            startedByApp = true
            scheduleSafetyDisconnect(context, tunnel)
        }
        onStartingTunnel()

        while (SystemClock.elapsedRealtime() - startedAt < TUNNEL_READY_TIMEOUT_MS) {
            if (!isOwned(owner)) return
            if (isReachableAsync(config.cameraHost)) {
                Log.i(TAG, "Tunnel \"$tunnel\" nach ${SystemClock.elapsedRealtime() - startedAt} ms bereit")
                return
            }
            delay(POLL_INTERVAL_MS.milliseconds)
        }
        Log.w(TAG, "Kamera nach $TUNNEL_READY_TIMEOUT_MS ms über Tunnel \"$tunnel\" nicht erreichbar")
    }

    @Synchronized
    private fun isOwned(owner: Any) = owner in owners

    private suspend fun isReachableAsync(host: String) = withContext(Dispatchers.IO) { isReachable(host) }

    /**
     * [owner] braucht den Tunnel nicht mehr. Baut ihn ab, falls ihn diese App aufgebaut hat
     * und kein anderer Bildschirm ihn noch braucht.
     */
    @Synchronized
    fun release(context: Context, owner: Any) {
        owners.remove(owner)
        if (owners.isNotEmpty() || !startedByApp) return
        startedByApp = false
        val tunnel = AppConfig(context).wireguardTunnel
        context.getSystemService(AlarmManager::class.java).cancel(safetyIntent(context, tunnel))
        context.sendBroadcast(tunnelIntent(ACTION_TUNNEL_DOWN, tunnel))
        Log.i(TAG, "Tunnel \"$tunnel\" getrennt")
    }

    private fun tunnelIntent(action: String, tunnel: String) =
        Intent(action).setPackage(WIREGUARD_PACKAGE).putExtra(EXTRA_TUNNEL, tunnel)

    private fun safetyIntent(context: Context, tunnel: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0, tunnelIntent(ACTION_TUNNEL_DOWN, tunnel),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun scheduleSafetyDisconnect(context: Context, tunnel: String) {
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + SAFETY_DISCONNECT_MS,
            safetyIntent(context, tunnel)
        )
    }

    private fun isVpnActive(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /** Ob beim Klingeln ein Tunnel aufgebaut würde (Auto-VPN an und Tunnel benannt). */
    fun isAutoVpnConfigured(config: AppConfig): Boolean =
        config.wireguardAutoVpn && config.wireguardTunnel.isNotBlank()

    /** TCP-Test auf den RTSP-Port der Kamera; blockiert bis CONNECT_TIMEOUT_MS, daher nur aus einem Hintergrund-Thread. */
    fun isReachable(host: String): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress(host, CAMERA_RTSP_PORT), CONNECT_TIMEOUT_MS) }
            true
        } catch (e: IOException) {
            false
        }
}

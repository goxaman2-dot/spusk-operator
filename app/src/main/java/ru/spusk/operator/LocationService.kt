package ru.spusk.operator

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Служба переднего плана: получает координаты GNSS раз в секунду
 * и передаёт их в [Hub] даже при погашенном экране.
 */
class LocationService : Service(), LocationListener {
    private var lm: LocationManager? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Навигация спуска", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, CH)
            .setContentTitle("Оператор спуска")
            .setContentText("Режим: ${Hub.mode.title}")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(ID, n)
        } catch (e: Exception) {
            Hub.toast("Не удалось запустить геолокацию: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (lm == null) {
            lm = getSystemService(LocationManager::class.java)
            try {
                lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            } catch (e: SecurityException) {
                Hub.toast("Нет разрешения на геолокацию")
                stopSelf()
            }
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spusk:nav").apply { acquire(6 * 60 * 60 * 1000L) }
        }
        return START_STICKY
    }

    override fun onLocationChanged(loc: Location) {
        Hub.onFix(
            Fix(
                loc.latitude, loc.longitude, loc.altitude,
                if (loc.hasSpeed()) loc.speed.toDouble() else -1.0,
                loc.bearing.toDouble(), loc.hasBearing(),
                if (loc.hasAccuracy()) loc.accuracy.toDouble() else 50.0,
                System.currentTimeMillis(),
            )
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) { Hub.toast("Включите GPS в настройках") }

    override fun onDestroy() {
        lm?.removeUpdates(this)
        lm = null
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val CH = "nav"
        private const val ID = 7
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, LocationService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, LocationService::class.java))
    }
}

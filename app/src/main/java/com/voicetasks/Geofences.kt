package com.voicetasks

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Keeps one geofence per saved place registered with Play services. */
object Geofences {

    private const val TAG = "Geofences"

    fun hasPermission(context: Context): Boolean = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    ).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Replaces the registered geofences with the current places. Safe to call any time. */
    fun sync(context: Context) {
        val client = LocationServices.getGeofencingClient(context)
        val intent = pendingIntent(context)
        client.removeGeofences(intent)

        val places = PlaceStore(context).all()
        if (places.isEmpty() || !hasPermission(context)) return
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofences(
                places.map {
                    Geofence.Builder()
                        .setRequestId(it.id)
                        .setCircularRegion(it.latitude, it.longitude, it.radiusMeters)
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                        .build()
                },
            )
            .build()
        try {
            client.addGeofences(request, intent).addOnFailureListener { Log.w(TAG, "Couldn't add geofences", it) }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission was revoked", e)
        }
    }

    // Geofencing fills in the event extras, so this one has to be mutable.
    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
}

/** Called by Play services when you enter a place. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError() || event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
        val placeIds = event.triggeringGeofences.orEmpty().map { it.requestId }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                PlaceReminders.onArrive(context.applicationContext, placeIds)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Geofences are cleared on reboot and app updates, so put them back. */
class GeofenceRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Geofences.sync(context.applicationContext)
        }
    }
}

package com.voicetasks

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale

/** Saved places for "when I get to …" tasks, plus the location and notification permissions they need. */
@Composable
internal fun PlacesSection() {
    val context = LocalContext.current
    val store = remember { PlaceStore(context) }
    val scope = rememberCoroutineScope()

    var places by remember { mutableStateOf(store.all()) }
    var name by remember { mutableStateOf("") }
    var coordinates by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(Place.DEFAULT_RADIUS.toInt().toString()) }
    var status by remember { mutableStateOf<String?>(null) }

    // Re-checked on resume, since "Allow all the time" is granted in system settings.
    var permissionCheck by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { permissionCheck++ } }
    val hasFine = remember(permissionCheck) { granted(context, Manifest.permission.ACCESS_FINE_LOCATION) }
    val hasBackground = remember(permissionCheck) { granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
    val hasNotifications = remember(permissionCheck) { granted(context, Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(hasBackground) { if (hasBackground) Geofences.sync(context) }

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionCheck++
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionCheck++
    }

    fun update(list: List<Place>) {
        store.save(list)
        places = list
        Geofences.sync(context)
    }

    Section("Places") {
        Text(
            "Say “buy milk when I get to the store” or “at home”, and you'll get a notification when you arrive.",
            style = MaterialTheme.typography.bodySmall,
        )

        when {
            !hasFine -> Button(onClick = {
                askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Text("Allow location") }
            !hasBackground -> {
                Button(onClick = { askPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) {
                    Text("Allow location all the time")
                }
                Text(
                    "Pick “Allow all the time” so arrivals are noticed while the app is closed.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (!hasNotifications) {
            Button(onClick = { askPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
        }

        places.forEach { place ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(place.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        String.format(Locale.US, "%.5f, %.5f · %d m", place.latitude, place.longitude, place.radiusMeters.toInt()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = {
                    PlaceReminders.removePlace(context, place.id)
                    update(places - place)
                }) { Text("Remove") }
            }
        }

        Text("Add a place", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            supportingText = { Text("What you'll say: gym, home, grocery store") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = coordinates,
            onValueChange = { coordinates = it },
            label = { Text("Location") },
            supportingText = { Text("lat, lng — paste from Google Maps, or use your current location") },
            singleLine = true,
            isError = coordinates.isNotBlank() && Place.parseCoordinates(coordinates) == null,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                scope.launch {
                    status = "Finding your location…"
                    status = runCatching { currentLocation(context) }.fold(
                        { coordinates = it; null },
                        { "Couldn't get your location: ${it.message}" },
                    )
                }
            },
            enabled = hasFine,
        ) { Text("Use my current location") }
        OutlinedTextField(
            value = radius,
            onValueChange = { radius = it },
            label = { Text("Radius (meters)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val point = Place.parseCoordinates(coordinates)
        val meters = radius.toFloatOrNull()?.takeIf { it >= 50f }
        Button(
            onClick = {
                update(places + Place(name.trim(), point!!.first, point.second, meters!!))
                name = ""
                coordinates = ""
            },
            enabled = name.isNotBlank() && point != null && meters != null,
        ) { Text("Save place") }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

// Only reachable once the fine location permission is granted (the button is disabled otherwise).
@SuppressLint("MissingPermission")
private suspend fun currentLocation(context: Context): String {
    val location = LocationServices.getFusedLocationProviderClient(context)
        .getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null)
        .await() ?: error("no fix yet, try again outside")
    return String.format(Locale.US, "%.6f, %.6f", location.latitude, location.longitude)
}

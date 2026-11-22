package com.finowise.hr.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import android.os.CancellationSignal
import com.finowise.hr.core.LocFix
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Asks the device once, when the person presses Punch, and never otherwise.
 * Resolves with a reading or with the reason there is none; it never
 * returns a coordinate it was not given.
 */
object LocationHelper {
    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun getFix(ctx: Context): LocFix {
        if (!hasPermission(ctx)) return LocFix(false, "denied", why = "Location permission was refused for this app.")
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(lm)) return LocFix(false, "unavailable", why = "The device's location is switched off.")
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return LocFix(false, "unavailable", why = "The device could not work out where it is.")
        }
        val loc = withTimeoutOrNull(13_000) {
            suspendCancellableCoroutine<android.location.Location?> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(lm, provider, signal, ContextCompat.getMainExecutor(ctx)) { l -> if (cont.isActive) cont.resume(l) }
            }
        } ?: return LocFix(false, "timeout", why = "The device did not answer in time.")
        return LocFix(true, "captured",
            lat = "%.6f".format(java.util.Locale.US, loc.latitude).toDouble(),
            lng = "%.6f".format(java.util.Locale.US, loc.longitude).toDouble(),
            accuracy = if (loc.hasAccuracy()) loc.accuracy.roundToInt() else null)
    }
}

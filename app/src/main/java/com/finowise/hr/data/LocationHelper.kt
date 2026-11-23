package com.finowise.hr.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.finowise.hr.core.LocFix
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Asks the device once, when the person presses Punch, and never otherwise.
 *
 * Every location source the phone has is asked at the same time — Android's
 * fused location, the network (Wi-Fi / mobile towers) and GPS — and the first
 * real reading wins, so a punch indoors does not wait for a GPS fix that may
 * never come. A reading the phone took in the last two minutes is used at once.
 * Nothing here ever returns a coordinate it was not given.
 */
object LocationHelper {
    private const val FRESH_MS = 2 * 60_000L
    private const val WAIT_MS = 25_000L

    private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    fun hasPermission(ctx: Context) =
        granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun ageMs(l: Location) = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000

    private fun fix(l: Location) = LocFix(true, "captured",
        lat = "%.6f".format(Locale.US, l.latitude).toDouble(),
        lng = "%.6f".format(Locale.US, l.longitude).toDouble(),
        accuracy = if (l.hasAccuracy()) l.accuracy.roundToInt() else null)

    @SuppressLint("MissingPermission")
    suspend fun getFix(ctx: Context): LocFix {
        if (!hasPermission(ctx)) return LocFix(false, "denied", why = "Location permission was refused for this app.")
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(lm))
            return LocFix(false, "unavailable", why = "The phone's Location is switched off. Turn it on in quick settings and try again.")

        val fine = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (fine) add(LocationManager.GPS_PROVIDER) // GPS needs precise location
        }.filter { p -> runCatching { lm.allProviders.contains(p) && lm.isProviderEnabled(p) }.getOrDefault(false) }
        if (providers.isEmpty()) return LocFix(false, "unavailable", why = "The phone has no location source switched on.")

        // A reading the phone already took moments ago is a real reading: use it straight away.
        val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { ageMs(it) < FRESH_MS }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
        if (recent != null) return fix(recent)

        // Otherwise ask every source at once; the first answer wins.
        val first = CompletableDeferred<Location?>()
        val nulls = java.util.concurrent.atomic.AtomicInteger(0)
        val signals = providers.map { p ->
            CancellationSignal().also { signal ->
                runCatching {
                    LocationManagerCompat.getCurrentLocation(lm, p, signal, ContextCompat.getMainExecutor(ctx)) { l ->
                        if (l != null) first.complete(l)
                        else if (nulls.incrementAndGet() == providers.size) first.complete(null) // nobody has a fix
                    }
                }
            }
        }
        val got = withTimeoutOrNull(WAIT_MS) { first.await() }
        signals.forEach { runCatching { it.cancel() } }
        if (got != null) return fix(got)

        return LocFix(false, "timeout", why = if (fine)
            "The phone could not find its location within ${WAIT_MS / 1000} seconds. Move near a window or turn on Wi-Fi, then try again."
        else "Only approximate location is allowed and the phone could not find it. Allow Precise location for this app, then try again.")
    }
}

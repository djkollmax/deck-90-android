package com.djkollmax.deck90

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import java.util.Locale

// ═══════════════════════════════════════
//  LOC: the radios of the place where the phone is. Radio Browser's stations within 40 km and those whose region or
//  name says the town (RadioBrowser.near); the twelve most heard become the band LOCAL, kept until the phone is
//  more than 25 km away or half a day has passed. Only the approximate location (the city is enough).
// ═══════════════════════════════════════
object Local {
    private const val MOVED_KM = 25.0
    private const val OLD_MS = 12 * 3600_000L

    fun allowed(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // where the phone was last seen by the network (or anything else); null without the permission or a fix
    @SuppressLint("MissingPermission")
    fun lastKnown(ctx: Context): Location? {
        if (!allowed(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    // the town and its country code, from the phone's own geocoder ("" when it has none)
    @Suppress("DEPRECATION")
    fun town(ctx: Context, lat: Double, lon: Double): Pair<String, String> = runCatching {
        if (!Geocoder.isPresent()) return "" to ""
        val a = Geocoder(ctx, Locale("es")).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return "" to ""
        (a.locality ?: a.subAdminArea ?: "").take(40) to (a.countryCode ?: "").uppercase()
    }.getOrDefault("" to "")

    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = FloatArray(1); Location.distanceBetween(lat1, lon1, lat2, lon2, r); return r[0] / 1000.0
    }

    // whether LOCAL was found somewhere else, long ago, or never
    fun stale(store: Store, lat: Double, lon: Double, now: Long = System.currentTimeMillis()): Boolean {
        val at = store.localAt() ?: return true
        if (store.station(Bands.LOCAL, 1) == null) return true
        return now - at.optLong("t") > OLD_MS || km(lat, lon, at.optDouble("lat"), at.optDouble("lon")) > MOVED_KM
    }

    // blocking (a few seconds): the stations near, the first twelve kept as LOCAL; the town found, and the list
    fun refresh(ctx: Context, store: Store, lat: Double, lon: Double): Pair<String, List<Found>> {
        val (town, cc) = town(ctx, lat, lon)
        val found = RadioBrowser.near(lat, lon, town, cc)
        if (found.isNotEmpty()) store.localSet(found.take(Bands.SLOTS).map { it.station() }, lat, lon, town)
        return town to found
    }
}

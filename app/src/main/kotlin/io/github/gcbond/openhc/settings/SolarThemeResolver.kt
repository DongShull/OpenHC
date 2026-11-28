package io.github.gcbond.openhc.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import java.util.TimeZone

/**
 * 解析当前设备用于自动昼夜判断的坐标来源并算出太阳起落状态：
 * 1. 若已授予系统定位权限，优先读取最新一次 GPS / 网络 / 被动定位并缓存；
 * 2. 若本次未拿到实时定位但有上次缓存定位，沿用缓存坐标；
 * 3. 无定位权限或无定位硬件（如电视、未授权车机）时，按当前系统时区推算当地太阳起落。
 */
internal object SolarThemeResolver {

    fun resolve(
        context: Context,
        settings: AppSettings,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): SolarThemeCalculator.SolarSnapshot {
        val live = readLastKnownLocation(context)
        if (live != null) {
            settings.cachedLocation = live
            return SolarThemeCalculator.evaluate(nowMillis, timeZone, live, SolarThemeCalculator.GeoSource.LOCATION)
        }
        val cached = settings.cachedLocation
        if (cached != null) {
            return SolarThemeCalculator.evaluate(nowMillis, timeZone, cached, SolarThemeCalculator.GeoSource.CACHED_LOCATION)
        }
        val tzPoint = SolarThemeCalculator.pointForTimeZone(timeZone)
        return SolarThemeCalculator.evaluate(nowMillis, timeZone, tzPoint, SolarThemeCalculator.GeoSource.TIMEZONE)
    }

    @SuppressLint("MissingPermission")
    private fun readLastKnownLocation(context: Context): SolarThemeCalculator.GeoPoint? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null

        val manager = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        val providers = if (fine) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        }

        var best: Location? = null
        for (provider in providers) {
            val loc = try {
                manager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: Exception) {
                null
            } ?: continue
            if (!SolarThemeCalculator.isValidCoordinate(loc.latitude, loc.longitude)) continue
            val prev = best
            if (prev == null || loc.time > prev.time) {
                best = loc
            }
        }
        return best?.let { SolarThemeCalculator.GeoPoint(it.latitude, it.longitude) }
    }
}

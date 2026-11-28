package io.github.gcbond.openhc.settings

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * 纯 Kotlin 天文太阳高度角与日出日落计算（NOAA 算法，兼容 Android API 23，无 java.time 依赖）。
 *
 * 用于「自动」主题：不再依赖常年卡在白天的车机系统 `UI_MODE_NIGHT`，而是按设备定位（优先）或时区推算当地日出日落时间，
 * 太阳落到地平线以下（官方日出日落角 -0.833°，已含大气折射与太阳视半径）自动切深色，日出后自动切浅色。
 */
internal object SolarThemeCalculator {

    /** 官方日出日落太阳高度角阈值（度）：-0.833° 对应天顶角 90.833°。 */
    const val SUNRISE_ELEVATION_DEG = -0.833
    private const val OFFICIAL_ZENITH_DEG = 90.833
    private const val MILLIS_PER_DAY = 86_400_000.0
    private const val MILLIS_PER_MINUTE = 60_000.0
    private const val MINUTES_PER_DAY = 1440

    data class GeoPoint(val latitude: Double, val longitude: Double)

    enum class GeoSource { LOCATION, CACHED_LOCATION, TIMEZONE }

    data class SolarSnapshot(
        val isNight: Boolean,
        val elevationDeg: Double,
        /** 当地时区今日日出（0..1439 分钟）；极昼/极夜时为 null。 */
        val sunriseMinutes: Int?,
        /** 当地时区今日日落（0..1439 分钟）；极昼/极夜时为 null。 */
        val sunsetMinutes: Int?,
        val point: GeoPoint,
        val source: GeoSource,
    )

    /** 判断某经纬度是否有效（同时排除部分车机未定位前返回的 (0, 0) 假坐标）。 */
    fun isValidCoordinate(latitude: Double, longitude: Double): Boolean {
        if (latitude.isNaN() || longitude.isNaN() || latitude.isInfinite() || longitude.isInfinite()) return false
        if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) return false
        if (kotlin.math.abs(latitude) < 1e-4 && kotlin.math.abs(longitude) < 1e-4) return false
        return true
    }

    /**
     * 按当前时刻、时区和坐标计算太阳高度角与当日日出日落时间。
     */
    fun evaluate(
        epochMillis: Long,
        timeZone: TimeZone,
        point: GeoPoint,
        source: GeoSource,
    ): SolarSnapshot {
        val lat = point.latitude.coerceIn(-89.9, 89.9)
        val lon = point.longitude.coerceIn(-180.0, 180.0)
        val elevation = solarElevationDeg(epochMillis, lat, lon)
        val isNight = elevation < SUNRISE_ELEVATION_DEG
        val (sunrise, sunset) = sunriseSunsetMinutes(epochMillis, timeZone, lat, lon)
        return SolarSnapshot(
            isNight = isNight,
            elevationDeg = elevation,
            sunriseMinutes = sunrise,
            sunsetMinutes = sunset,
            point = GeoPoint(lat, lon),
            source = source,
        )
    }

    /**
     * 计算指定 UTC 毫秒时刻、经纬度（北纬正、东经正）的太阳高度角（度）。
     */
    fun solarElevationDeg(epochMillis: Long, latitudeDeg: Double, longitudeDeg: Double): Double {
        val lat = latitudeDeg.coerceIn(-89.9, 89.9)
        val lon = longitudeDeg.coerceIn(-180.0, 180.0)
        val astro = solarParams(epochMillis)

        val utcMinutes = positiveMod(epochMillis / MILLIS_PER_MINUTE, MINUTES_PER_DAY.toDouble())
        val trueSolarMin = positiveMod(utcMinutes + astro.eqTimeMinutes + 4.0 * lon, MINUTES_PER_DAY.toDouble())
        var hourAngleDeg = trueSolarMin / 4.0 - 180.0
        if (hourAngleDeg < -180.0) hourAngleDeg += 360.0

        val latRad = Math.toRadians(lat)
        val cosZenith = (
            sin(latRad) * sin(astro.declinationRad) +
                cos(latRad) * cos(astro.declinationRad) * cos(Math.toRadians(hourAngleDeg))
            ).coerceIn(-1.0, 1.0)
        val zenithDeg = Math.toDegrees(acos(cosZenith))
        return 90.0 - zenithDeg
    }

    /**
     * 计算当地日期的日出与日落时间（当地时区 0..1439 分钟）。极昼或极夜返回 (null, null)。
     */
    fun sunriseSunsetMinutes(
        epochMillis: Long,
        timeZone: TimeZone,
        latitudeDeg: Double,
        longitudeDeg: Double,
    ): Pair<Int?, Int?> {
        val lat = latitudeDeg.coerceIn(-89.9, 89.9)
        val lon = longitudeDeg.coerceIn(-180.0, 180.0)
        val cal = GregorianCalendar(timeZone).apply {
            timeInMillis = epochMillis
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val localNoonMillis = cal.timeInMillis
        val tzOffsetMinutes = timeZone.getOffset(localNoonMillis) / MILLIS_PER_MINUTE

        // 以当地太阳中天时刻求赤纬与均时差，误差在 1 分钟内。
        val solarNoonApproxUtc = localNoonMillis + ((tzOffsetMinutes - 4.0 * lon) * MILLIS_PER_MINUTE).toLong()
        val astro = solarParams(solarNoonApproxUtc)

        val latRad = Math.toRadians(lat)
        val zenithRad = Math.toRadians(OFFICIAL_ZENITH_DEG)
        val cosHa = (cos(zenithRad) / (cos(latRad) * cos(astro.declinationRad))) -
            tan(latRad) * tan(astro.declinationRad)
        if (cosHa > 1.0 || cosHa < -1.0 || cosHa.isNaN()) {
            return null to null
        }

        val haDeg = Math.toDegrees(acos(cosHa))
        val noonLocalMin = 720.0 - 4.0 * lon - astro.eqTimeMinutes + tzOffsetMinutes
        val rise = wrapDayMinutes((noonLocalMin - haDeg * 4.0).roundToInt())
        val set = wrapDayMinutes((noonLocalMin + haDeg * 4.0).roundToInt())
        return rise to set
    }

    /**
     * 无定位权限或尚未拿到定位时，按设备时区推算代表经纬度：
     * 常见 IANA 时区直接查代表坐标；其余时区按标准时区偏移（每小时 15° 经度）回退。
     */
    fun pointForTimeZone(timeZone: TimeZone): GeoPoint {
        val id = timeZone.id
        TIMEZONE_POINTS[id]?.let { return it }
        val offsetHours = timeZone.rawOffset / 3_600_000.0
        val lon = (offsetHours * 15.0).coerceIn(-180.0, 180.0)
        val lat = if (
            id.startsWith("Australia/") ||
            id.startsWith("Antarctica/") ||
            id == "Pacific/Auckland" ||
            id == "America/Argentina/Buenos_Aires" ||
            id == "America/Santiago" ||
            id == "Africa/Johannesburg"
        ) {
            -30.0
        } else {
            35.0
        }
        return GeoPoint(lat, lon)
    }

    /** 将 0..1439 的当天分钟数格式化为 HH:mm。 */
    fun formatClockMinutes(minutes: Int): String {
        val m = wrapDayMinutes(minutes)
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
    }

    private data class SolarParams(val declinationRad: Double, val eqTimeMinutes: Double)

    private fun solarParams(epochMillis: Long): SolarParams {
        val jd = epochMillis / MILLIS_PER_DAY + 2440587.5
        val t = (jd - 2451545.0) / 36525.0

        val l0 = positiveMod(280.46646 + t * (36000.76983 + t * 0.0003032), 360.0)
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val mRad = Math.toRadians(m)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)

        val c = sin(mRad) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2.0 * mRad) * (0.019993 - 0.000101 * t) +
            sin(3.0 * mRad) * 0.000289
        val trueLong = l0 + c
        val omega = 125.04 - 1934.136 * t
        val appLong = trueLong - 0.00569 - 0.00478 * sin(Math.toRadians(omega))

        val meanObliq = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val obliqCorr = meanObliq + 0.00256 * cos(Math.toRadians(omega))

        val sinDecl = (sin(Math.toRadians(obliqCorr)) * sin(Math.toRadians(appLong))).coerceIn(-1.0, 1.0)
        val declRad = asin(sinDecl)

        val tanHalf = tan(Math.toRadians(obliqCorr / 2.0))
        val y = tanHalf * tanHalf
        val l0Rad = Math.toRadians(l0)
        val eqTime = 4.0 * Math.toDegrees(
            y * sin(2.0 * l0Rad) -
                2.0 * e * sin(mRad) +
                4.0 * e * y * sin(mRad) * cos(2.0 * l0Rad) -
                0.5 * y * y * sin(4.0 * l0Rad) -
                1.25 * e * e * sin(2.0 * mRad),
        )
        return SolarParams(declinationRad = declRad, eqTimeMinutes = eqTime)
    }

    private fun positiveMod(value: Double, modulus: Double): Double = ((value % modulus) + modulus) % modulus

    private fun wrapDayMinutes(minutes: Int): Int = ((minutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY

    /** 常见时区代表坐标（中国大陆标准时区取中东部人口重心，覆盖京沪广深汉蓉误差均在 ±20 分钟内）。 */
    private val TIMEZONE_POINTS: Map<String, GeoPoint> = mapOf(
        "Asia/Shanghai" to GeoPoint(34.5, 116.4),
        "PRC" to GeoPoint(34.5, 116.4),
        "CTT" to GeoPoint(34.5, 116.4),
        "Asia/Chongqing" to GeoPoint(29.56, 106.55),
        "Asia/Chungking" to GeoPoint(29.56, 106.55),
        "Asia/Harbin" to GeoPoint(45.80, 126.53),
        "Asia/Urumqi" to GeoPoint(43.83, 87.62),
        "Asia/Kashgar" to GeoPoint(39.47, 75.99),
        "Asia/Hong_Kong" to GeoPoint(22.32, 114.17),
        "Asia/Macau" to GeoPoint(22.20, 113.54),
        "Asia/Taipei" to GeoPoint(25.03, 121.57),
        "Asia/Tokyo" to GeoPoint(35.68, 139.69),
        "Asia/Seoul" to GeoPoint(37.57, 126.98),
        "Asia/Singapore" to GeoPoint(1.35, 103.82),
        "Asia/Kuala_Lumpur" to GeoPoint(3.14, 101.69),
        "Asia/Bangkok" to GeoPoint(13.76, 100.50),
        "Asia/Ho_Chi_Minh" to GeoPoint(10.82, 106.63),
        "Asia/Jakarta" to GeoPoint(-6.21, 106.85),
        "Asia/Manila" to GeoPoint(14.60, 120.98),
        "Asia/Kolkata" to GeoPoint(22.57, 88.36),
        "Asia/Dubai" to GeoPoint(25.20, 55.27),
        "Asia/Riyadh" to GeoPoint(24.71, 46.68),
        "Europe/London" to GeoPoint(51.51, -0.13),
        "Europe/Paris" to GeoPoint(48.86, 2.35),
        "Europe/Berlin" to GeoPoint(52.52, 13.40),
        "Europe/Madrid" to GeoPoint(40.42, -3.70),
        "Europe/Rome" to GeoPoint(41.90, 12.50),
        "Europe/Amsterdam" to GeoPoint(52.37, 4.90),
        "Europe/Zurich" to GeoPoint(47.37, 8.54),
        "Europe/Moscow" to GeoPoint(55.76, 37.62),
        "Europe/Istanbul" to GeoPoint(41.01, 28.98),
        "America/New_York" to GeoPoint(40.71, -74.01),
        "America/Chicago" to GeoPoint(41.88, -87.63),
        "America/Denver" to GeoPoint(39.74, -104.99),
        "America/Los_Angeles" to GeoPoint(34.05, -118.24),
        "America/Toronto" to GeoPoint(43.65, -79.38),
        "America/Vancouver" to GeoPoint(49.28, -123.12),
        "America/Mexico_City" to GeoPoint(19.43, -99.13),
        "America/Sao_Paulo" to GeoPoint(-23.55, -46.63),
        "Australia/Sydney" to GeoPoint(-33.87, 151.21),
        "Australia/Melbourne" to GeoPoint(-37.81, 144.96),
        "Australia/Perth" to GeoPoint(-31.95, 115.86),
        "Pacific/Auckland" to GeoPoint(-36.85, 174.76),
    )
}

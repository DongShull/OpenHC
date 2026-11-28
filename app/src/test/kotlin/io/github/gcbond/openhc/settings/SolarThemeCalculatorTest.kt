package io.github.gcbond.openhc.settings

import io.github.gcbond.openhc.settings.SolarThemeCalculator.GeoPoint
import io.github.gcbond.openhc.settings.SolarThemeCalculator.GeoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.GregorianCalendar
import java.util.TimeZone

class SolarThemeCalculatorTest {

    private fun epochMillis(
        year: Int,
        month1Based: Int,
        day: Int,
        hour: Int,
        minute: Int,
        tzId: String,
    ): Long {
        val cal = GregorianCalendar(TimeZone.getTimeZone(tzId))
        cal.clear()
        cal.set(year, month1Based - 1, day, hour, minute, 0)
        return cal.timeInMillis
    }

    @Test
    fun beijingWinterSunriseSunsetAndDayNight() {
        val tz = TimeZone.getTimeZone("Asia/Shanghai")
        val beijing = GeoPoint(39.9042, 116.4074)

        val midnight = SolarThemeCalculator.evaluate(epochMillis(2026, 11, 29, 0, 22, "Asia/Shanghai"), tz, beijing, GeoSource.LOCATION)
        assertTrue("00:22 in Beijing must be night", midnight.isNight)
        assertNotNull(midnight.sunriseMinutes)
        assertNotNull(midnight.sunsetMinutes)
        // 北京 11 月底日出约 07:14，日落约 16:51（容差 ±5 分钟）
        assertTrue("Expected Beijing sunrise ~07:14, got ${midnight.sunriseMinutes}", midnight.sunriseMinutes!! in 429..439)
        assertTrue("Expected Beijing sunset ~16:51, got ${midnight.sunsetMinutes}", midnight.sunsetMinutes!! in 1006..1016)

        val beforeRise = SolarThemeCalculator.evaluate(epochMillis(2026, 11, 29, 7, 0, "Asia/Shanghai"), tz, beijing, GeoSource.LOCATION)
        val afterRise = SolarThemeCalculator.evaluate(epochMillis(2026, 11, 29, 7, 30, "Asia/Shanghai"), tz, beijing, GeoSource.LOCATION)
        val noon = SolarThemeCalculator.evaluate(epochMillis(2026, 11, 29, 12, 0, "Asia/Shanghai"), tz, beijing, GeoSource.LOCATION)
        val beforeSet = SolarThemeCalculator.evaluate(epochMillis(2026, 11, 29, 16, 30, "Asia/Shanghai"), tz, beijing, GeoSource.LOCATION)
        val afterSet = SolarThemeCalculator.evaluate(epochMillis(2026, 11, 29, 17, 15, "Asia/Shanghai"), tz, beijing, GeoSource.LOCATION)

        assertTrue(beforeRise.isNight)
        assertFalse(afterRise.isNight)
        assertFalse(noon.isNight)
        assertFalse(beforeSet.isNight)
        assertTrue(afterSet.isNight)
    }

    @Test
    fun urumqiUsesTrueLongitudeInsideUtcPlus8() {
        val tz = TimeZone.getTimeZone("Asia/Shanghai")
        val urumqi = GeoPoint(43.8256, 87.6168)

        // 乌鲁木齐冬日北京时间 08:30 仍未日出，17:30 仍是白天
        val morning = SolarThemeCalculator.evaluate(epochMillis(2026, 11, 29, 8, 30, "Asia/Shanghai"), tz, urumqi, GeoSource.LOCATION)
        val afternoon = SolarThemeCalculator.evaluate(epochMillis(2026, 11, 29, 17, 30, "Asia/Shanghai"), tz, urumqi, GeoSource.LOCATION)
        assertTrue("08:30 CST in Urumqi should still be night", morning.isNight)
        assertFalse("17:30 CST in Urumqi should still be day", afternoon.isNight)
    }

    @Test
    fun southernAndWesternHemispheres() {
        val nyTz = TimeZone.getTimeZone("America/New_York")
        val newYork = GeoPoint(40.7128, -74.0060)
        assertTrue(SolarThemeCalculator.evaluate(epochMillis(2026, 11, 29, 6, 30, "America/New_York"), nyTz, newYork, GeoSource.TIMEZONE).isNight)
        assertFalse(SolarThemeCalculator.evaluate(epochMillis(2026, 11, 29, 12, 0, "America/New_York"), nyTz, newYork, GeoSource.TIMEZONE).isNight)
        assertTrue(SolarThemeCalculator.evaluate(epochMillis(2026, 11, 29, 17, 0, "America/New_York"), nyTz, newYork, GeoSource.TIMEZONE).isNight)

        val sydTz = TimeZone.getTimeZone("Australia/Sydney")
        val sydney = GeoPoint(-33.8688, 151.2093)
        assertFalse(SolarThemeCalculator.evaluate(epochMillis(2026, 11, 29, 6, 0, "Australia/Sydney"), sydTz, sydney, GeoSource.TIMEZONE).isNight)
        assertFalse(SolarThemeCalculator.evaluate(epochMillis(2026, 11, 29, 19, 0, "Australia/Sydney"), sydTz, sydney, GeoSource.TIMEZONE).isNight)
        assertTrue(SolarThemeCalculator.evaluate(epochMillis(2026, 11, 29, 20, 30, "Australia/Sydney"), sydTz, sydney, GeoSource.TIMEZONE).isNight)
    }

    @Test
    fun polarNightAndMidnightSunHandledCleanly() {
        val tz = TimeZone.getTimeZone("Europe/Oslo")
        val tromso = GeoPoint(69.6492, 18.9553)

        val winterNoon = SolarThemeCalculator.evaluate(epochMillis(2026, 12, 21, 12, 0, "Europe/Oslo"), tz, tromso, GeoSource.LOCATION)
        assertTrue(winterNoon.isNight)
        assertNull(winterNoon.sunriseMinutes)
        assertNull(winterNoon.sunsetMinutes)

        val summerMidnight = SolarThemeCalculator.evaluate(epochMillis(2026, 6, 21, 0, 22, "Europe/Oslo"), tz, tromso, GeoSource.LOCATION)
        assertFalse(summerMidnight.isNight)
        assertNull(summerMidnight.sunriseMinutes)
        assertNull(summerMidnight.sunsetMinutes)
    }

    @Test
    fun timezoneFallbackAndCoordinateValidation() {
        val shanghaiPoint = SolarThemeCalculator.pointForTimeZone(TimeZone.getTimeZone("Asia/Shanghai"))
        assertEquals(34.5, shanghaiPoint.latitude, 0.01)
        assertEquals(116.4, shanghaiPoint.longitude, 0.01)

        val gmtPlus5 = SolarThemeCalculator.pointForTimeZone(TimeZone.getTimeZone("GMT+05:00"))
        assertEquals(75.0, gmtPlus5.longitude, 0.01)

        assertFalse(SolarThemeCalculator.isValidCoordinate(0.0, 0.0))
        assertFalse(SolarThemeCalculator.isValidCoordinate(Double.NaN, 116.0))
        assertFalse(SolarThemeCalculator.isValidCoordinate(95.0, 116.0))
        assertTrue(SolarThemeCalculator.isValidCoordinate(39.9, 116.4))

        assertEquals("07:14", SolarThemeCalculator.formatClockMinutes(434))
        assertEquals("00:00", SolarThemeCalculator.formatClockMinutes(1440))
    }
}

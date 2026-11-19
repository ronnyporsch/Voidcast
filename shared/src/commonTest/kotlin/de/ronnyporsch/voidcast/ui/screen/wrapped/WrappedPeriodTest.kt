package de.ronnyporsch.voidcast.ui.screen.wrapped

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.asTimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WrappedPeriodTest {
    private val zone: TimeZone = UtcOffset(hours = 2).asTimeZone()
    private val today = LocalDate(2026, 11, 19)

    private fun start(day: LocalDate) = day.atStartOfDayIn(zone)

    @Test
    fun lastDaysIncludeToday() {
        val (from, to) = WrappedPeriod(WrappedPreset.Last7Days).bounds(today, zone)
        assertEquals(start(LocalDate(2026, 11, 13)), from)
        assertNull(to)
    }

    @Test
    fun lastYearIsTheWholeCalendarYear() {
        val (from, to) = WrappedPeriod(WrappedPreset.LastYear).bounds(today, zone)
        assertEquals(start(LocalDate(2025, 1, 1)), from)
        assertEquals(start(LocalDate(2026, 1, 1)), to)
    }

    @Test
    fun allTimeHasNoBounds() = assertEquals(null to null, WrappedPeriod(WrappedPreset.AllTime).bounds(today, zone))

    @Test
    fun customIncludesBothDaysInAnyOrder() {
        val (from, to) = WrappedPeriod(WrappedPreset.Custom, LocalDate(2026, 3, 10), LocalDate(2026, 3, 1)).bounds(today, zone)
        assertEquals(start(LocalDate(2026, 3, 1)), from)
        assertEquals(start(LocalDate(2026, 3, 11)), to)
    }

    @Test
    fun labels() {
        assertEquals("2026", WrappedPeriod(WrappedPreset.ThisYear).label(today))
        assertEquals("2025", WrappedPeriod(WrappedPreset.LastYear).label(today))
        assertEquals("Mar 1 – Mar 10, 2026", WrappedPeriod(WrappedPreset.Custom, LocalDate(2026, 3, 1), LocalDate(2026, 3, 10)).label(today))
        assertEquals("Dec 30, 2025 – Jan 2, 2026", formatDayRange(LocalDate(2025, 12, 30), LocalDate(2026, 1, 2)))
        assertEquals("March 5, 2026", formatDay("2026-03-05"))
    }

    @Test
    fun counts() {
        assertEquals("0", formatCount(0))
        assertEquals("999", formatCount(999))
        assertEquals("1,000", formatCount(1000))
        assertEquals("12,345,678", formatCount(12345678L))
        assertEquals("1 play", plural(1, "play"))
        assertEquals("2,000 plays", plural(2000, "play"))
    }
}

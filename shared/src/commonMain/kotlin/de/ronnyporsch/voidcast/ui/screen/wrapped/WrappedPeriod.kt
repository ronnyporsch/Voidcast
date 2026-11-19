package de.ronnyporsch.voidcast.ui.screen.wrapped

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlin.time.Instant

/** The time spans Wrapped can be of. */
enum class WrappedPreset {
    Last7Days,
    Last30Days,
    Last90Days,
    ThisYear,
    LastYear,
    AllTime,
    Custom,
}

/** A time span Wrapped is of; [customFrom] and [customTo] are the (inclusive) days of a [WrappedPreset.Custom] one. */
data class WrappedPeriod(
    val preset: WrappedPreset,
    val customFrom: LocalDate? = null,
    val customTo: LocalDate? = null,
) {
    /** The name to show for the period, e.g. on its button; [today] is needed for the years. */
    fun label(today: LocalDate): String = when (preset) {
        WrappedPreset.Last7Days -> "Last 7 days"
        WrappedPreset.Last30Days -> "Last 30 days"
        WrappedPreset.Last90Days -> "Last 90 days"
        WrappedPreset.ThisYear -> today.year.toString()
        WrappedPreset.LastYear -> (today.year - 1).toString()
        WrappedPreset.AllTime -> "All time"
        WrappedPreset.Custom -> if (customFrom != null && customTo != null) formatDayRange(customFrom, customTo) else "Custom"
    }

    /**
     * The start (inclusive) and end (exclusive) of the period in [timeZone], with [today] being today there. A null start
     * is the whole history, a null end is now.
     */
    fun bounds(today: LocalDate, timeZone: TimeZone): Pair<Instant?, Instant?> {
        fun start(day: LocalDate) = day.atStartOfDayIn(timeZone)
        fun daysBack(days: Int) = start(today.minus(DatePeriod(days = days - 1))) to null
        return when (preset) {
            WrappedPreset.Last7Days -> daysBack(7)
            WrappedPreset.Last30Days -> daysBack(30)
            WrappedPreset.Last90Days -> daysBack(90)
            WrappedPreset.ThisYear -> start(LocalDate(today.year, 1, 1)) to null
            WrappedPreset.LastYear -> start(LocalDate(today.year - 1, 1, 1)) to start(LocalDate(today.year, 1, 1))
            WrappedPreset.AllTime -> null to null
            WrappedPreset.Custom -> {
                val from = requireNotNull(customFrom) { "A custom period needs days" }
                val to = requireNotNull(customTo) { "A custom period needs days" }
                start(minOf(from, to)) to start(maxOf(from, to).plus(DatePeriod(days = 1)))
            }
        }
    }
}

private val MONTHS = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

/** E.g. "March 5, 2026". */
fun formatDay(day: LocalDate): String = "${MONTHS[day.month.number - 1]} ${day.day}, ${day.year}"

/** Like [formatDay], for a yyyy-MM-dd day of the server; as is if it isn't one. */
fun formatDay(isoDay: String): String = runCatching { formatDay(LocalDate.parse(isoDay)) }.getOrDefault(isoDay)

/** E.g. "Mar 5 – Apr 2, 2026", with both years if they differ. */
fun formatDayRange(from: LocalDate, to: LocalDate): String {
    fun short(day: LocalDate) = "${MONTHS[day.month.number - 1].take(3)} ${day.day}"
    return if (from.year == to.year) "${short(from)} – ${short(to)}, ${to.year}"
    else "${short(from)}, ${from.year} – ${short(to)}, ${to.year}"
}

/** E.g. "12,345". */
fun formatCount(value: Long): String = value.toString().reversed().chunked(3).joinToString(",").reversed()

fun formatCount(value: Int): String = formatCount(value.toLong())

/** "1 play", "2 plays". */
fun plural(count: Int, singular: String, plural: String = "${singular}s"): String = "${formatCount(count)} ${if (count == 1) singular else plural}"

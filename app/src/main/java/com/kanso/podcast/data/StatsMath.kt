package com.kanso.podcast.data

import com.kanso.podcast.data.db.ListeningDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Buckets for the statistics charts. Values are minutes. */
object StatsMath {
    data class Bucket(val start: LocalDate, val minutes: Long, val savedMinutes: Long)

    private fun parse(days: List<ListeningDay>) = days.mapNotNull { d -> runCatching { LocalDate.parse(d.day) }.getOrNull()?.let { it to d } }

    /** The last [count] days ending today, oldest first, with zero-filled gaps. */
    fun daily(days: List<ListeningDay>, today: LocalDate, count: Int = 7): List<Bucket> {
        val byDay = parse(days).toMap()
        return (count - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong()); val d = byDay[date]
            Bucket(date, (d?.listenedMs ?: 0) / 60_000, (d?.savedMs ?: 0) / 60_000)
        }
    }

    /** The last [count] Monday-based weeks ending with the current week, oldest first. */
    fun weekly(days: List<ListeningDay>, today: LocalDate, count: Int = 8): List<Bucket> {
        val thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val grouped = parse(days).groupBy { (date, _) -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
        return (count - 1 downTo 0).map { back ->
            val start = thisWeek.minusWeeks(back.toLong()); val list = grouped[start].orEmpty().map { it.second }
            Bucket(start, list.sumOf { it.listenedMs } / 60_000, list.sumOf { it.savedMs } / 60_000)
        }
    }
}

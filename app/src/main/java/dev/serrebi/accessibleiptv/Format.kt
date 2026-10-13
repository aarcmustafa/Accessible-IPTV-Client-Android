package dev.serrebi.accessibleiptv

import java.text.DateFormat
import java.util.Date

object Format {
	fun time(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
	fun date(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
	fun dateTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

	/** "Title, 18:00 to 18:30" — one phrase a screen reader reads cleanly. */
	fun programme(p: Programme): String = "${p.title}, ${time(p.start)} to ${time(p.stop)}"

	/** Joins phrases with ". " without doubling the stop after times like "8:00 p.m.". */
	fun sentences(parts: List<String>): String =
		parts.filter { it.isNotBlank() }.map { it.trim() }.reduceOrNull { a, b -> "${a.trimEnd('.')}. $b" }.orEmpty()
}

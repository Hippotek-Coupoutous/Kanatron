package com.kanatron.app.core

import java.math.BigDecimal

/** Parses seconds, MM:SS, or HH:MM:SS into exact decimal milliseconds. */
fun parseExactTimeMillis(text: String): BigDecimal? = runCatching {
    val parts = text.trim().split(':')
    require(parts.size in 1..3)
    require(parts.all { it.matches(Regex("[0-9]+(?:\\.[0-9]+)?")) })
    val seconds = BigDecimal(parts.last())
    require(seconds.signum() >= 0 && (parts.size == 1 || seconds < BigDecimal.valueOf(60)))
    val millis = seconds.multiply(THOUSAND)
    val minutes = if (parts.size >= 2) parts[parts.size - 2].toLong() else 0L
    val hours = if (parts.size == 3) parts[0].toLong() else 0L
    require(minutes >= 0L && (parts.size < 3 || minutes < 60L) && hours >= 0L)
    millis.add(BigDecimal.valueOf(Math.addExact(
        Math.multiplyExact(hours, 3_600_000L),
        Math.multiplyExact(minutes, 60_000L)
    )))
}.getOrNull()

private val THOUSAND = BigDecimal.valueOf(1_000L)
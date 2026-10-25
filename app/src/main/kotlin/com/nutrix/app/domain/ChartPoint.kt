package com.nutrix.app.domain

/** A point of a daily series: x is the epoch day. */
data class ChartPoint(val epochDay: Long, val value: Double)

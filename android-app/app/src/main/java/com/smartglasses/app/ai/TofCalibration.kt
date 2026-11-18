package com.smartglasses.app.ai

import kotlin.math.abs

/**
 * ToF calibration from the Diagnostics walk-through: the tester holds a card at
 * known distances (tape measure) and the glasses measure each. Fits
 * true = scale * measured + offset by least squares. One point gives an offset only.
 */
object TofCalibration {
    data class Point(val trueMm: Int, val measuredMm: Int)
    data class Fit(val scale: Float, val offsetMm: Int, val maxResidualMm: Int)

    fun fit(points: List<Point>): Fit? {
        if (points.isEmpty()) return null
        if (points.size == 1 || points.map { it.measuredMm }.distinct().size == 1) {
            val off = points.map { it.trueMm - it.measuredMm }.average().toInt()
            return Fit(1f, off, points.maxOf { abs(it.trueMm - (it.measuredMm + off)) })
        }
        val n = points.size.toDouble()
        val mx = points.sumOf { it.measuredMm.toDouble() } / n
        val my = points.sumOf { it.trueMm.toDouble() } / n
        val sxx = points.sumOf { (it.measuredMm - mx) * (it.measuredMm - mx) }
        val sxy = points.sumOf { (it.measuredMm - mx) * (it.trueMm - my) }
        val scale = sxy / sxx
        val offset = my - scale * mx
        val residual = points.maxOf { abs(it.trueMm - (scale * it.measuredMm + offset)) }
        return Fit(scale.toFloat(), offset.toInt(), residual.toInt())
    }
}

package com.tyquawn.atc

import android.graphics.*
import kotlin.math.*

/** Small vector icon set drawn with Canvas paths (no emoji). Size s = icon box in px. */
object Icons {
    private val st = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fi = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    val names = setOf("speaker", "mute", "pause", "play", "restart", "ground", "radar", "lock", "check", "info", "back", "chev",
        "close", "star", "plane", "help", "left", "right", "up", "down", "minus", "plus", "gear", "expand", "collapse", "cross")

    fun draw(c: Canvas, name: String, x: Float, y: Float, s: Float, col: Int) {
        st.color = col; fi.color = col; st.strokeWidth = max(1.5f, s * 0.11f)
        val h = s / 2
        c.save(); c.translate(x, y)
        path.reset()
        when (name) {
            "speaker", "mute" -> {
                path.moveTo(-h * 0.8f, -h * 0.3f); path.lineTo(-h * 0.35f, -h * 0.3f); path.lineTo(h * 0.1f, -h * 0.75f)
                path.lineTo(h * 0.1f, h * 0.75f); path.lineTo(-h * 0.35f, h * 0.3f); path.lineTo(-h * 0.8f, h * 0.3f); path.close(); c.drawPath(path, fi)
                if (name == "speaker") { c.drawArc(-h * 0.3f, -h * 0.5f, h * 0.6f, h * 0.5f, -50f, 100f, false, st); c.drawArc(-h * 0.3f, -h * 0.85f, h * 0.95f, h * 0.85f, -50f, 100f, false, st) }
                else { c.drawLine(h * 0.35f, -h * 0.35f, h * 0.9f, h * 0.35f, st); c.drawLine(h * 0.9f, -h * 0.35f, h * 0.35f, h * 0.35f, st) }
            }
            "pause" -> { c.drawRoundRect(-h * 0.6f, -h * 0.75f, -h * 0.15f, h * 0.75f, 2f, 2f, fi); c.drawRoundRect(h * 0.15f, -h * 0.75f, h * 0.6f, h * 0.75f, 2f, 2f, fi) }
            "play" -> { path.moveTo(-h * 0.5f, -h * 0.75f); path.lineTo(h * 0.75f, 0f); path.lineTo(-h * 0.5f, h * 0.75f); path.close(); c.drawPath(path, fi) }
            "restart" -> { c.drawArc(-h * 0.7f, -h * 0.7f, h * 0.7f, h * 0.7f, -60f, 300f, false, st)
                path.moveTo(h * 0.15f, -h * 0.95f); path.lineTo(h * 0.6f, -h * 0.6f); path.lineTo(h * 0.1f, -h * 0.3f); c.drawPath(path, st) }
            "ground" -> { // runway + taxiway
                c.drawRoundRect(-h * 0.85f, -h * 0.2f, h * 0.85f, h * 0.2f, 2f, 2f, fi)
                c.drawLine(-h * 0.4f, h * 0.2f, -h * 0.4f, h * 0.8f, st); c.drawLine(h * 0.4f, h * 0.2f, h * 0.4f, h * 0.8f, st); c.drawLine(-h * 0.4f, h * 0.8f, h * 0.4f, h * 0.8f, st)
                c.drawLine(0f, -h * 0.75f, 0f, -h * 0.45f, st)
            }
            "radar" -> { c.drawCircle(0f, 0f, h * 0.8f, st); c.drawCircle(0f, 0f, h * 0.35f, st); c.drawLine(0f, 0f, h * 0.57f, -h * 0.57f, st) }
            "lock" -> { c.drawRoundRect(-h * 0.6f, -h * 0.1f, h * 0.6f, h * 0.8f, 3f, 3f, fi); c.drawArc(-h * 0.4f, -h * 0.8f, h * 0.4f, h * 0.2f, 180f, 180f, false, st)
                c.drawLine(-h * 0.4f, -h * 0.3f, -h * 0.4f, -h * 0.1f, st); c.drawLine(h * 0.4f, -h * 0.3f, h * 0.4f, -h * 0.1f, st) }
            "check" -> { path.moveTo(-h * 0.65f, 0f); path.lineTo(-h * 0.15f, h * 0.5f); path.lineTo(h * 0.7f, -h * 0.5f); c.drawPath(path, st) }
            "info" -> { c.drawCircle(0f, 0f, h * 0.85f, st); c.drawLine(0f, -h * 0.05f, 0f, h * 0.45f, st); c.drawCircle(0f, -h * 0.4f, st.strokeWidth * 0.7f, fi) }
            "help" -> { c.drawCircle(0f, 0f, h * 0.85f, st); c.drawArc(-h * 0.3f, -h * 0.55f, h * 0.3f, h * 0.05f, 180f, 230f, false, st)
                c.drawLine(0f, h * 0.05f, 0f, h * 0.2f, st); c.drawCircle(0f, h * 0.48f, st.strokeWidth * 0.7f, fi) }
            "back", "left", "collapse" -> { path.moveTo(h * 0.3f, -h * 0.6f); path.lineTo(-h * 0.3f, 0f); path.lineTo(h * 0.3f, h * 0.6f); c.drawPath(path, st) }
            "chev", "right", "expand" -> { path.moveTo(-h * 0.3f, -h * 0.6f); path.lineTo(h * 0.3f, 0f); path.lineTo(-h * 0.3f, h * 0.6f); c.drawPath(path, st) }
            "up" -> { path.moveTo(-h * 0.6f, h * 0.3f); path.lineTo(0f, -h * 0.3f); path.lineTo(h * 0.6f, h * 0.3f); c.drawPath(path, st) }
            "down" -> { path.moveTo(-h * 0.6f, -h * 0.3f); path.lineTo(0f, h * 0.3f); path.lineTo(h * 0.6f, -h * 0.3f); c.drawPath(path, st) }
            "close", "cross" -> { c.drawLine(-h * 0.55f, -h * 0.55f, h * 0.55f, h * 0.55f, st); c.drawLine(h * 0.55f, -h * 0.55f, -h * 0.55f, h * 0.55f, st) }
            "minus" -> c.drawLine(-h * 0.6f, 0f, h * 0.6f, 0f, st)
            "plus" -> { c.drawLine(-h * 0.6f, 0f, h * 0.6f, 0f, st); c.drawLine(0f, -h * 0.6f, 0f, h * 0.6f, st) }
            "star" -> { for (i in 0 until 10) { val r = if (i % 2 == 0) h * 0.9f else h * 0.38f; val a = Math.toRadians(i * 36.0 - 90)
                    if (i == 0) path.moveTo((cos(a) * r).toFloat(), (sin(a) * r).toFloat()) else path.lineTo((cos(a) * r).toFloat(), (sin(a) * r).toFloat()) }
                path.close(); c.drawPath(path, fi) }
            "plane" -> { c.rotate(45f); c.scale(s / 16f, s / 16f)
                path.moveTo(0f, -7f); path.lineTo(1.2f, -2f); path.lineTo(7f, 1.5f); path.lineTo(7f, 3f); path.lineTo(1.2f, 1.5f); path.lineTo(0.8f, 5f)
                path.lineTo(3f, 6.5f); path.lineTo(3f, 7.5f); path.lineTo(0f, 6.8f); path.lineTo(-3f, 7.5f); path.lineTo(-3f, 6.5f); path.lineTo(-0.8f, 5f)
                path.lineTo(-1.2f, 1.5f); path.lineTo(-7f, 3f); path.lineTo(-7f, 1.5f); path.lineTo(-1.2f, -2f); path.close(); c.drawPath(path, fi) }
            "gear" -> { c.drawCircle(0f, 0f, h * 0.3f, st)
                for (i in 0 until 8) { val a = Math.toRadians(i * 45.0); c.drawLine((cos(a) * h * 0.55f).toFloat(), (sin(a) * h * 0.55f).toFloat(), (cos(a) * h * 0.85f).toFloat(), (sin(a) * h * 0.85f).toFloat(), st) }
                c.drawCircle(0f, 0f, h * 0.6f, st) }
        }
        c.restore()
    }
}

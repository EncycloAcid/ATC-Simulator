package com.tyquawn.atc

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.*
import kotlin.random.Random

class MainActivity : Activity() {
    lateinit var game: GameView
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        game = GameView(this); setContentView(game)
    }
    override fun onPause() { super.onPause(); game.autoPause() }
}

const val RADIUS = 40f      // scope radius nm
const val TIME_SCALE = 4f   // sim seconds per real second

data class Fix(val name: String, val x: Float, val y: Float, val exit: Boolean)
data class Runway(val name: String, val tx: Float, val ty: Float, val hdg: Float) // threshold, landing heading

class Plane(val cs: String, val type: String, val arrival: Boolean) {
    var x = 0f; var y = 0f; var hdg = 0f; var alt = 0f; var spd = 0f
    var tHdg = 0f; var tAlt = 0f; var tSpd = 0f
    var exitFix: Fix? = null; var exitAlt = 0f
    var ils: Runway? = null; var onLoc = false
    var holding = false; var direct: Fix? = null
    var ground = false; var rollDone = false
    val trail = ArrayList<PointF>(); var trailT = 0f
    var conflict = false; var done = false
}

fun norm(a: Float): Float { var h = a % 360f; if (h < 0) h += 360f; return h }
fun angDiff(a: Float, b: Float): Float { var d = norm(b - a); if (d > 180) d -= 360; return d } // b - a signed
fun bearing(x0: Float, y0: Float, x1: Float, y1: Float) = norm(Math.toDegrees(atan2((x1 - x0).toDouble(), (y1 - y0).toDouble())).toFloat())

class GameView(ctx: Context) : View(ctx) {
    enum class S { TITLE, PLAY, PAUSE, OVER }
    var state = S.TITLE
    val prefs = ctx.getSharedPreferences("atc", Context.MODE_PRIVATE)
    var high = prefs.getInt("high", 0)
    val tone = try { ToneGenerator(AudioManager.STREAM_ALARM, 60) } catch (e: Exception) { null }
    val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?

    // y axis: north positive
    val runways = listOf(Runway("27L", 3f, -0.6f, 270f), Runway("27R", 3f, 0.6f, 270f))
    val fixes = listOf(
        Fix("MERIT", 0f, 38f, true), Fix("BETTE", 38f, 0f, true), Fix("COATE", 0f, -38f, true), Fix("ROBUC", -38f, 0f, true),
        Fix("KOPPY", 18f, 0f, false), Fix("ZALPO", 12f, 14f, false), Fix("HAARP", 12f, -14f, false),
        Fix("DIXIE", -14f, 20f, false), Fix("LENDY", -16f, -18f, false), Fix("CAMRN", 26f, 22f, false), Fix("GREKI", 26f, -22f, false))
    val exits get() = fixes.filter { it.exit }

    val planes = ArrayList<Plane>()
    var sel: Plane? = null
    var score = 0; var landed = 0; var exited = 0; var simT = 0f
    var spawnT = 0f; var depT = 20f; var sweep = 0f; var lastFrame = 0L
    var msg = ""; var msgT = 0f; var overReason = ""
    var alarmT = 0f
    val usedCs = HashSet<String>()

    // layout
    var cx = 0f; var cy = 0f; var scale = 1f; var panelX = 0f
    val btns = ArrayList<Pair<RectF, String>>()

    val pScope = Paint(Paint.ANTI_ALIAS_FLAG)
    val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }

    fun start() {
        planes.clear(); usedCs.clear(); sel = null; score = 0; landed = 0; exited = 0; simT = 0f
        spawnT = 3f; depT = 25f; state = S.PLAY; lastFrame = 0L; flash("Welcome to Approach. Land arrivals on 27L/27R.")
    }
    fun autoPause() { if (state == S.PLAY) state = S.PAUSE }
    fun flash(m: String) { msg = m; msgT = 5f }

    val airlines = listOf("DAL", "UAL", "AAL", "SWA", "JBU", "ASA", "FFT", "NKS", "SKW", "RPA", "FDX", "UPS")
    fun callsign(): String {
        while (true) {
            val al = airlines.random()
            val n = when (Random.nextInt(3)) { 0 -> Random.nextInt(10, 100); 1 -> Random.nextInt(100, 1000); else -> Random.nextInt(1000, 3000) }
            val c = "$al$n"; if (usedCs.add(c)) return c
        }
    }
    fun acType(cs: String) = when {
        cs.startsWith("SWA") -> listOf("B737", "B738", "B38M").random()
        cs.startsWith("FDX") || cs.startsWith("UPS") -> listOf("B763", "B752", "MD11").random()
        cs.startsWith("SKW") || cs.startsWith("RPA") -> listOf("CRJ9", "E175", "E170").random()
        else -> listOf("B738", "A320", "A321", "B739", "B77W", "A333", "B752", "A20N").random()
    }

    fun spawnArrival() {
        val p = Plane(callsign(), "", true).let { Plane(it.cs, acType(it.cs), true) }
        val a = Random.nextFloat() * 360f
        val r = RADIUS - 0.5f
        p.x = sin(Math.toRadians(a.toDouble())).toFloat() * r; p.y = cos(Math.toRadians(a.toDouble())).toFloat() * r
        // avoid spawning on top of someone
        if (planes.any { hypot(it.x - p.x, it.y - p.y) < 6f }) return
        p.hdg = norm(bearing(p.x, p.y, 0f, 0f) + Random.nextFloat() * 30 - 15); p.tHdg = p.hdg
        p.alt = (Random.nextInt(7, 14) * 1000).toFloat(); p.tAlt = p.alt
        p.spd = 280f; p.tSpd = 280f
        planes.add(p); flash("${p.cs} with you, ${fmtAlt(p.alt)}, information Alpha")
    }
    fun spawnDeparture() {
        val rw = runways.random()
        if (planes.any { it.ground || (!it.arrival && it.alt < 2000 && hypot(it.x - rw.tx, it.y - rw.ty) < 4f) }) return
        if (planes.any { it.arrival && it.onLoc && it.ils == rw && hypot(it.x - rw.tx, it.y - rw.ty) < 5f }) return
        val cs = callsign(); val p = Plane(cs, acType(cs), false)
        p.x = rw.tx; p.y = rw.ty; p.hdg = rw.hdg; p.tHdg = rw.hdg
        p.alt = 0f; p.tAlt = 5000f; p.spd = 0f; p.tSpd = 250f; p.ground = true
        p.exitFix = exits.random(); p.exitAlt = (Random.nextInt(10, 18) * 1000).toFloat()
        planes.add(p); flash("${p.cs} rolling ${rw.name}, exit ${p.exitFix!!.name} at ${fmtAlt(p.exitAlt)}")
    }
    fun fmtAlt(a: Float) = if (a >= 18000) "FL${(a / 100).roundToInt()}" else "${(a / 100).roundToInt() * 100} ft"

    fun update(dtReal: Float) {
        val dt = dtReal * TIME_SCALE
        simT += dt; sweep = norm(sweep + dtReal * 90f)
        if (msgT > 0) msgT -= dtReal
        if (alarmT > 0) alarmT -= dtReal
        val minutes = simT / 60f
        val arrInt = max(35f, 110f - minutes * 6f)      // sim seconds between arrivals
        val depInt = max(45f, 130f - minutes * 6f)
        val maxPlanes = min(14, 3 + (minutes / 1.5f).toInt())
        spawnT -= dt; depT -= dt
        if (spawnT <= 0 && planes.size < maxPlanes) { spawnArrival(); spawnT = arrInt * (0.7f + Random.nextFloat() * 0.6f) }
        if (depT <= 0 && planes.size < maxPlanes) { spawnDeparture(); depT = depInt * (0.7f + Random.nextFloat() * 0.6f) }

        for (p in planes) movePlane(p, dt)
        planes.removeAll { it.done }
        if (sel?.done == true) sel = null

        // separation
        var newConflict = false
        for (p in planes) p.conflict = false
        for (i in planes.indices) for (j in i + 1 until planes.size) {
            val a = planes[i]; val b = planes[j]
            if (a.alt < 300 || b.alt < 300) continue
            val d = hypot(a.x - b.x, a.y - b.y); val v = abs(a.alt - b.alt)
            if (d < 0.5f && v < 400f) { gameOver("Midair: ${a.cs} and ${b.cs}"); return }
            if (d < 3f && v < 1000f) { a.conflict = true; b.conflict = true; newConflict = true }
        }
        if (newConflict) {
            score -= 0  // per-second penalty applied below
            score -= (dt * 1f).roundToInt().coerceAtLeast(0)
            if (alarmT <= 0) {
                alarmT = 2f
                tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
                try { vib?.vibrate(VibrationEffect.createOneShot(150, 120)) } catch (_: Exception) {}
            }
        }
    }

    fun movePlane(p: Plane, dt: Float) {
        // trail
        p.trailT -= dt
        if (p.trailT <= 0 && !p.ground) { p.trail.add(PointF(p.x, p.y)); if (p.trail.size > 6) p.trail.removeAt(0); p.trailT = 12f }

        if (p.ground) { // takeoff roll
            p.spd += 6f * dt
            if (p.spd >= 150f) { p.ground = false; p.alt = 50f }
        }
        // guidance
        val ils = p.ils
        if (ils != null) {
            val rh = Math.toRadians(ils.hdg.toDouble())
            val ux = sin(rh).toFloat(); val uy = cos(rh).toFloat()   // landing direction
            val dx = p.x - ils.tx; val dy = p.y - ils.ty
            val along = -(dx * ux + dy * uy)    // distance before threshold (positive on approach side)
            val cross = dx * uy - dy * ux       // lateral offset
            if (!p.onLoc) {
                if (along in 0.5f..22f && abs(cross) < 1.2f && abs(angDiff(p.hdg, ils.hdg)) < 50f) {
                    p.onLoc = true; p.holding = false; p.direct = null; flash("${p.cs} established ILS ${ils.name}")
                }
            }
            if (p.onLoc) {
                p.tHdg = norm(ils.hdg + (cross * 35f).coerceIn(-30f, 30f))
                val gs = along * 318f
                p.tSpd = if (along < 6) 140f else min(p.tSpd, 180f)
                if (p.alt > gs) p.tAlt = max(0f, gs)
                if (along < 0.3f) {
                    if (p.alt < 400f && p.spd <= 190f) { landPlane(p, ils); return }
                    goAround(p, "too high/fast"); return
                }
                if (p.alt - gs > 2500f && along < 4f) { goAround(p, "unstable approach"); return }
            }
        } else if (p.holding) {
            p.tHdg = norm(p.hdg + 90f)
        } else p.direct?.let { f -> p.tHdg = bearing(p.x, p.y, f.x, f.y) }

        if (!p.ground) {
            val d = angDiff(p.hdg, p.tHdg)
            val rate = 3f * dt   // standard rate turn
            p.hdg = norm(p.hdg + d.coerceIn(-rate, rate))
            val vr = if (p.tAlt > p.alt) 2200f / 60f else (if (p.onLoc) 1200f else 1800f) / 60f
            val ad = p.tAlt - p.alt
            p.alt += ad.coerceIn(-vr * dt, vr * dt)
            val accel = 2f * dt
            p.spd += (p.tSpd - p.spd).coerceIn(-accel, accel)
        }
        val nm = p.spd / 3600f * dt
        val hr = Math.toRadians(p.hdg.toDouble())
        p.x += sin(hr).toFloat() * nm; p.y += cos(hr).toFloat() * nm

        if (hypot(p.x, p.y) > RADIUS + 0.5f) leaveScope(p)
    }

    fun landPlane(p: Plane, rw: Runway) {
        p.done = true; landed++; score += 100; flash("${p.cs} landed ${rw.name} +100")
    }
    fun goAround(p: Plane, why: String) {
        p.ils = null; p.onLoc = false; p.tAlt = 3000f; p.tSpd = 210f; p.tHdg = p.hdg
        score -= 50; flash("${p.cs} going around ($why) -50")
    }
    fun leaveScope(p: Plane) {
        p.done = true
        if (p.arrival) { score -= 150; flash("${p.cs} left your airspace -150"); return }
        val f = p.exitFix!!
        val dist = hypot(p.x - f.x, p.y - f.y)
        if (dist < 6f && abs(p.alt - p.exitAlt) <= 500f) { exited++; score += 75; flash("${p.cs} handed off at ${f.name} +75") }
        else if (dist < 6f) { score -= 40; flash("${p.cs} wrong altitude at ${f.name} -40") }
        else { score -= 100; flash("${p.cs} wrong exit -100") }
    }
    fun gameOver(r: String) {
        overReason = r; state = S.OVER
        tone?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 1000)
        try { vib?.vibrate(VibrationEffect.createOneShot(600, 255)) } catch (_: Exception) {}
        if (score > high) { high = score; prefs.edit().putInt("high", high).apply() }
    }

    // ---------- drawing ----------
    fun sx(x: Float) = cx + x * scale
    fun sy(y: Float) = cy - y * scale

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else min(0.1f, (now - lastFrame) / 1e9f)
        lastFrame = now
        if (state == S.PLAY) update(dt) else sweep = norm(sweep + dt * 90f)

        val w = width.toFloat(); val h = height.toFloat()
        val landscape = w > h
        panelX = if (landscape) w * 0.68f else w
        val scopeH = if (landscape) h else h * 0.62f
        cx = panelX / 2f; cy = scopeH / 2f
        scale = min(panelX, scopeH) / 2f / RADIUS * 0.97f
        val dp = resources.displayMetrics.density
        c.drawColor(Color.rgb(2, 14, 6))
        drawScope(c, dp)
        btns.clear()
        when (state) {
            S.TITLE -> drawTitle(c, w, h, dp)
            S.PLAY, S.PAUSE -> { drawPlanes(c, dp); drawPanel(c, w, h, dp, landscape, scopeH); if (state == S.PAUSE) drawPause(c, w, h, dp) }
            S.OVER -> { drawPlanes(c, dp); drawOver(c, w, h, dp) }
        }
        postInvalidateOnAnimation()
    }

    fun drawScope(c: Canvas, dp: Float) {
        val r = RADIUS * scale
        pScope.style = Paint.Style.FILL
        pScope.shader = RadialGradient(cx, cy, r, Color.rgb(6, 40, 16), Color.rgb(2, 20, 8), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, pScope); pScope.shader = null
        pScope.style = Paint.Style.STROKE; pScope.strokeWidth = 1f * dp
        pScope.color = Color.argb(110, 60, 200, 90)
        for (i in 1..4) c.drawCircle(cx, cy, r * i / 4f, pScope)
        pText.color = Color.argb(140, 60, 200, 90); pText.textSize = 9 * dp
        for (i in 1..4) c.drawText("${(RADIUS * i / 4).toInt()}", cx + 3 * dp, cy - r * i / 4f + 11 * dp, pText)
        pScope.color = Color.argb(50, 60, 200, 90)
        for (a in 0 until 360 step 30) { val rr = Math.toRadians(a.toDouble()); c.drawLine(cx, cy, cx + sin(rr).toFloat() * r, cy - cos(rr).toFloat() * r, pScope) }
        // sweep
        val sr = Math.toRadians(sweep.toDouble())
        val sw = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for (k in 0 until 12) {
            sw.color = Color.argb(40 - k * 3, 80, 255, 120)
            c.drawArc(cx - r, cy - r, cx + r, cy + r, sweep - 90f - k * 3f - 3f, 3f, true, sw)
        }
        pScope.color = Color.argb(200, 120, 255, 150); pScope.strokeWidth = 1.5f * dp
        c.drawLine(cx, cy, cx + sin(sr).toFloat() * r, cy - cos(sr).toFloat() * r, pScope)
        // runways + extended centerlines
        for (rw in runways) {
            pScope.color = Color.argb(70, 200, 200, 200); pScope.strokeWidth = 1f * dp
            pScope.pathEffect = DashPathEffect(floatArrayOf(6 * dp, 6 * dp), 0f)
            c.drawLine(sx(rw.tx), sy(rw.ty), sx(rw.tx + 15f), sy(rw.ty), pScope); pScope.pathEffect = null
            for (m in 5..15 step 5) c.drawLine(sx(rw.tx + m), sy(rw.ty) - 3 * dp, sx(rw.tx + m), sy(rw.ty) + 3 * dp, pScope)
            pScope.color = Color.WHITE; pScope.strokeWidth = 3.5f * dp
            c.drawLine(sx(rw.tx), sy(rw.ty), sx(rw.tx - 2.2f), sy(rw.ty), pScope)
            pText.color = Color.LTGRAY; pText.textSize = 9 * dp
            c.drawText(rw.name, sx(rw.tx) + 3 * dp, sy(rw.ty) + (if (rw.ty < 0) 12 else -4) * dp, pText)
        }
        // fixes
        for (f in fixes) {
            val px = sx(f.x); val py = sy(f.y); val s = 5 * dp
            pScope.strokeWidth = 1.2f * dp
            pScope.color = if (f.exit) Color.rgb(90, 190, 255) else Color.argb(160, 160, 220, 160)
            val path = Path().apply { moveTo(px, py - s); lineTo(px + s, py + s * 0.8f); lineTo(px - s, py + s * 0.8f); close() }
            c.drawPath(path, pScope)
            pText.color = pScope.color; pText.textSize = 9 * dp
            val tx = if (f.x > 20) px - 36 * dp else px + 7 * dp
            c.drawText(f.name, tx, py + (if (f.y > 20) 16 else -6) * dp, pText)
        }
    }

    fun drawPlanes(c: Canvas, dp: Float) {
        val pp = Paint(Paint.ANTI_ALIAS_FLAG)
        for (p in planes) {
            val px = sx(p.x); val py = sy(p.y)
            val col = when { p.conflict -> Color.rgb(255, 70, 60); p == sel -> Color.rgb(255, 255, 120); p.arrival -> Color.rgb(120, 255, 140); else -> Color.rgb(110, 200, 255) }
            pp.color = col; pp.style = Paint.Style.FILL
            for ((i, t) in p.trail.withIndex()) { pp.alpha = 60 + i * 25; c.drawCircle(sx(t.x), sy(t.y), 1.6f * dp, pp) }
            pp.alpha = 255
            c.drawRect(px - 3.5f * dp, py - 3.5f * dp, px + 3.5f * dp, py + 3.5f * dp, pp)
            // velocity leader 1 min
            pp.style = Paint.Style.STROKE; pp.strokeWidth = 1.2f * dp
            val hr = Math.toRadians(p.hdg.toDouble()); val lead = p.spd / 60f * scale
            c.drawLine(px, py, px + sin(hr).toFloat() * lead, py - cos(hr).toFloat() * lead, pp)
            if (p.conflict) { pp.strokeWidth = 2f * dp; c.drawCircle(px, py, 1.5f * scale, pp) }   // 3nm dia ring
            if (p == sel) { pp.color = Color.YELLOW; pp.strokeWidth = 1.5f * dp; c.drawCircle(px, py, 14 * dp, pp) }
            // data block
            pText.color = col; pText.textSize = 10.5f * dp
            val bx = px + 9 * dp; val by = py - 14 * dp
            c.drawLine(px + 3 * dp, py - 3 * dp, bx - 1 * dp, by + 2 * dp, pp.apply { color = col; strokeWidth = 0.8f * dp })
            val altS = "%03d".format((p.alt / 100).roundToInt())
            val arrow = when { p.tAlt > p.alt + 50 -> "↑"; p.tAlt < p.alt - 50 -> "↓"; else -> " " }
            c.drawText(p.cs + if (p.onLoc) " ILS" else if (p.holding) " HLD" else "", bx, by, pText)
            c.drawText("$altS$arrow${"%03d".format((p.tAlt / 100).roundToInt())} ${(p.spd / 10).roundToInt()}", bx, by + 11 * dp, pText)
            val third = if (p.arrival) "${p.type} ARR" else "${p.type} ${p.exitFix!!.name.take(3)}${(p.exitAlt / 100).roundToInt()}"
            c.drawText(third, bx, by + 22 * dp, pText)
        }
    }

    fun button(c: Canvas, r: RectF, label: String, id: String, dp: Float, on: Boolean = false, accent: Int = Color.rgb(40, 110, 60)) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (on) Color.rgb(200, 170, 40) else accent
        c.drawRoundRect(r, 8 * dp, 8 * dp, p)
        p.style = Paint.Style.STROKE; p.color = Color.argb(160, 160, 255, 170); p.strokeWidth = 1 * dp
        c.drawRoundRect(r, 8 * dp, 8 * dp, p)
        pText.color = Color.WHITE; pText.textSize = min(15 * dp, r.height() * 0.42f); pText.textAlign = Paint.Align.CENTER
        c.drawText(label, r.centerX(), r.centerY() + pText.textSize * 0.35f, pText); pText.textAlign = Paint.Align.LEFT
        btns.add(r to id)
    }

    fun drawPanel(c: Canvas, w: Float, h: Float, dp: Float, landscape: Boolean, scopeH: Float) {
        val left = if (landscape) panelX else 0f; val top = if (landscape) 0f else scopeH
        val pw = w - left; val ph = h - top
        val bg = Paint().apply { color = Color.rgb(8, 22, 12) }
        c.drawRect(left, top, w, h, bg)
        val pad = 8 * dp
        pText.textSize = 13 * dp; pText.color = Color.rgb(160, 255, 170)
        c.drawText("SCORE $score   HI $high", left + pad, top + 20 * dp, pText)
        pText.textSize = 11 * dp; pText.color = Color.rgb(120, 200, 130)
        c.drawText("Landed $landed  Handoffs $exited  T+${(simT / 60).toInt()}m", left + pad, top + 36 * dp, pText)
        button(c, RectF(w - pad - 64 * dp, top + 6 * dp, w - pad, top + 36 * dp), "II", "pause", dp)
        // message bar on scope
        if (msgT > 0) {
            pText.textSize = 12 * dp; pText.color = Color.argb((min(1f, msgT) * 255).toInt(), 255, 240, 160)
            c.drawText(msg, 8 * dp, scopeH - 10 * dp, pText)
        }
        val p = sel
        var y = top + 46 * dp
        if (p == null) {
            pText.textSize = 12 * dp; pText.color = Color.rgb(150, 220, 160)
            val lines = listOf("Tap an aircraft to select.", "", "Green = arrival: vector it onto", "the dashed final (east of field),", "below ~5000 ft, then CLEARED ILS.",
                "", "Blue = departure: send to its", "exit fix (blue ▲) at the listed", "altitude. Data block line 3 shows", "e.g. BET140 = BETTE at 14,000.",
                "", "Drag from a selected plane to", "point its heading. Keep 3 nm", "or 1000 ft apart.")
            for (l in lines) { c.drawText(l, left + pad, y + 12 * dp, pText); y += 15 * dp }
            return
        }
        pText.textSize = 14 * dp; pText.color = Color.YELLOW
        c.drawText("${p.cs} ${p.type} ${if (p.arrival) "ARR" else "DEP→${p.exitFix!!.name} ${fmtAlt(p.exitAlt)}"}", left + pad, y + 12 * dp, pText)
        y += 22 * dp
        val rows = 6; val gap = 5 * dp
        val bh = min(52 * dp, (h - y - pad - gap * (rows - 1)) / rows)
        val cols3 = (pw - 2 * pad - 2 * gap) / 3
        fun row3(a: Triple<String, String, String?>, b: Triple<String, String, String?>, cc: Triple<String, String, String?>) {
            val items = listOf(a, b, cc)
            for ((i, it) in items.withIndex()) {
                val r = RectF(left + pad + i * (cols3 + gap), y, left + pad + i * (cols3 + gap) + cols3, y + bh)
                if (it.third == "label") {
                    pText.textSize = min(14 * dp, bh * 0.36f); pText.color = Color.WHITE; pText.textAlign = Paint.Align.CENTER
                    c.drawText(it.first, r.centerX(), r.centerY() - 2 * dp, pText)
                    pText.textSize = min(11 * dp, bh * 0.28f); pText.color = Color.rgb(150, 220, 160)
                    c.drawText(it.second, r.centerX(), r.centerY() + pText.textSize + 1 * dp, pText); pText.textAlign = Paint.Align.LEFT
                } else button(c, r, it.first, it.second, dp)
            }
            y += bh + gap
        }
        row3(Triple("◀ 10°", "h-10", null), Triple("HDG %03d".format(p.tHdg.roundToInt().let { if (it == 0) 360 else it }), "now %03d".format(p.hdg.roundToInt()), "label"), Triple("10° ▶", "h+10", null))
        row3(Triple("▼ 1000", "a-", null), Triple("ALT ${(p.tAlt / 100).roundToInt()}", "now ${(p.alt / 100).roundToInt()}", "label"), Triple("▲ 1000", "a+", null))
        row3(Triple("− 10 kt", "s-", null), Triple("SPD ${p.tSpd.roundToInt()}", "now ${p.spd.roundToInt()}", "label"), Triple("+ 10 kt", "s+", null))
        val cols2 = (pw - 2 * pad - gap) / 2
        if (p.arrival) {
            button(c, RectF(left + pad, y, left + pad + cols2, y + bh), "ILS 27L", "ils0", dp, p.ils == runways[0], Color.rgb(30, 80, 130))
            button(c, RectF(left + pad + cols2 + gap, y, w - pad, y + bh), "ILS 27R", "ils1", dp, p.ils == runways[1], Color.rgb(30, 80, 130))
        } else {
            button(c, RectF(left + pad, y, left + pad + cols2, y + bh), "DIRECT ${p.exitFix!!.name}", "direct", dp, p.direct != null, Color.rgb(30, 80, 130))
            button(c, RectF(left + pad + cols2 + gap, y, w - pad, y + bh), "CLIMB ${(p.exitAlt / 100).roundToInt()}", "climbx", dp, false, Color.rgb(30, 80, 130))
        }
        y += bh + gap
        button(c, RectF(left + pad, y, left + pad + cols2, y + bh), "HOLD", "hold", dp, p.holding, Color.rgb(110, 70, 30))
        button(c, RectF(left + pad + cols2 + gap, y, w - pad, y + bh), "DESELECT", "desel", dp, false, Color.rgb(60, 60, 60))
    }

    fun overlay(c: Canvas, w: Float, h: Float) { c.drawRect(0f, 0f, w, h, Paint().apply { color = Color.argb(170, 0, 0, 0) }) }
    fun center(c: Canvas, t: String, y: Float, size: Float, col: Int) {
        pText.textSize = size; pText.color = col; pText.textAlign = Paint.Align.CENTER; c.drawText(t, width / 2f, y, pText); pText.textAlign = Paint.Align.LEFT
    }
    fun drawTitle(c: Canvas, w: Float, h: Float, dp: Float) {
        overlay(c, w, h)
        center(c, "APPROACH CONTROL", h * 0.25f, 34 * dp, Color.rgb(140, 255, 160))
        center(c, "Vector arrivals to the ILS. Hand off departures at their fix.", h * 0.25f + 30 * dp, 13 * dp, Color.rgb(180, 230, 190))
        center(c, "High score: $high", h * 0.25f + 54 * dp, 14 * dp, Color.YELLOW)
        button(c, RectF(w / 2 - 110 * dp, h * 0.55f, w / 2 + 110 * dp, h * 0.55f + 60 * dp), "START SHIFT", "start", dp)
    }
    fun drawPause(c: Canvas, w: Float, h: Float, dp: Float) {
        overlay(c, w, h)
        center(c, "PAUSED", h * 0.3f, 32 * dp, Color.WHITE)
        button(c, RectF(w / 2 - 110 * dp, h * 0.42f, w / 2 + 110 * dp, h * 0.42f + 56 * dp), "RESUME", "resume", dp)
        button(c, RectF(w / 2 - 110 * dp, h * 0.42f + 70 * dp, w / 2 + 110 * dp, h * 0.42f + 126 * dp), "QUIT", "quit", dp, false, Color.rgb(110, 40, 40))
    }
    fun drawOver(c: Canvas, w: Float, h: Float, dp: Float) {
        overlay(c, w, h)
        center(c, "GAME OVER", h * 0.22f, 34 * dp, Color.rgb(255, 90, 80))
        center(c, overReason, h * 0.22f + 30 * dp, 15 * dp, Color.WHITE)
        center(c, "Score $score   Landed $landed   Handoffs $exited", h * 0.22f + 58 * dp, 15 * dp, Color.rgb(160, 255, 170))
        center(c, if (score >= high && score > 0) "NEW HIGH SCORE!" else "High score: $high", h * 0.22f + 82 * dp, 15 * dp, Color.YELLOW)
        button(c, RectF(w / 2 - 110 * dp, h * 0.6f, w / 2 + 110 * dp, h * 0.6f + 56 * dp), "NEW SHIFT", "start", dp)
    }

    // ---------- input ----------
    var dragging = false
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                for ((r, id) in btns) if (r.contains(x, y)) { press(id); return true }
                if (state != S.PLAY) return true
                if (x > panelX && width > height) return true
                val s = sel
                if (s != null && hypot(sx(s.x) - x, sy(s.y) - y) < 30 * resources.displayMetrics.density) { dragging = true; return true }
                val near = planes.minByOrNull { hypot(sx(it.x) - x, sy(it.y) - y) }
                sel = if (near != null && hypot(sx(near.x) - x, sy(near.y) - y) < 45 * resources.displayMetrics.density) near else sel
            }
            MotionEvent.ACTION_MOVE -> if (dragging) sel?.let { p ->
                val px = sx(p.x); val py = sy(p.y)
                if (hypot(x - px, y - py) > 20 * resources.displayMetrics.density) {
                    val b = norm(Math.toDegrees(atan2((x - px).toDouble(), (py - y).toDouble())).toFloat())
                    clearNav(p); p.tHdg = norm((b / 5f).roundToInt() * 5f)
                }
            }
            MotionEvent.ACTION_UP -> dragging = false
        }
        return true
    }
    fun clearNav(p: Plane) { p.holding = false; p.direct = null; if (p.ils != null) { p.ils = null; p.onLoc = false } }

    fun press(id: String) {
        when (id) {
            "start" -> { start(); return }
            "pause" -> { state = S.PAUSE; return }
            "resume" -> { state = S.PLAY; lastFrame = 0L; return }
            "quit" -> { if (score > high) { high = score; prefs.edit().putInt("high", high).apply() }; state = S.TITLE; return }
        }
        val p = sel ?: return
        when (id) {
            "h-10" -> { clearNav(p); p.tHdg = norm(p.tHdg - 10) }
            "h+10" -> { clearNav(p); p.tHdg = norm(p.tHdg + 10) }
            "a-" -> { if (!p.onLoc) p.tAlt = max(2000f, p.tAlt - 1000) }
            "a+" -> { if (!p.onLoc) p.tAlt = min(24000f, p.tAlt + 1000) }
            "s-" -> p.tSpd = max(if (p.arrival) 160f else 210f, p.tSpd - 10)
            "s+" -> p.tSpd = min(340f, p.tSpd + 10)
            "ils0", "ils1" -> { val rw = runways[id.last() - '0']; p.holding = false; p.direct = null; p.ils = rw; p.onLoc = false; flash("${p.cs} cleared ILS ${rw.name}, maintain ${fmtAlt(p.tAlt)} until established") }
            "direct" -> { p.holding = false; p.direct = p.exitFix; flash("${p.cs} proceed direct ${p.exitFix!!.name}") }
            "climbx" -> { p.tAlt = p.exitAlt; flash("${p.cs} climb and maintain ${fmtAlt(p.exitAlt)}") }
            "hold" -> { p.holding = !p.holding; if (p.holding) { p.direct = null; p.ils = null; p.onLoc = false; flash("${p.cs} hold present position, right turns") } else p.tHdg = p.hdg }
            "desel" -> sel = null
        }
    }
}

package com.tyquawn.atc

import android.content.Context
import android.graphics.*
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import kotlin.math.*
import kotlin.random.Random

const val RADIUS = 40f
const val TIME_SCALE = 4f

enum class Nav { HDG, ROUTE, DIRECT, HOLD }

class Plane(val cs: String, val type: String, val arrival: Boolean) {
    var x = 0f; var y = 0f; var hdg = 0f; var alt = 0f; var spd = 0f
    var tHdg = 0f; var tAlt = 0f; var tSpd = 0f; var turnDir = 0
    var nav = Nav.HDG
    var star: Star? = null; var routeIdx = 0; var descendVia = false
    var navFix: Fix? = null
    var holdInbound = -1f; var holdPhase = 0; var holdTimer = 0f
    var exitFix: Fix? = null; var exitAlt = 0f
    var ils: RwyEnd? = null; var onLoc = false
    var ground = false; var checkedIn = false
    val trail = ArrayList<PointF>(); var trailT = 0f
    var conflict = false; var done = false
}

fun norm(a: Float): Float { var h = a % 360f; if (h < 0) h += 360f; return h }
fun angDiff(a: Float, b: Float): Float { var d = norm(b - a); if (d > 180) d -= 360; return d }
fun bearing(x0: Float, y0: Float, x1: Float, y1: Float) = norm(Math.toDegrees(atan2((x1 - x0).toDouble(), (y1 - y0).toDouble())).toFloat())
fun altTxt(a: Float): String { val r = (a / 100).roundToInt() * 100; return if (r >= 18000) "FL%03d".format(r / 100) else "%,d".format(r) }
fun hdgTxt(h: Float) = "%03d".format(norm(h).roundToInt().let { if (it == 0) 360 else it })

class GameView(ctx: Context) : View(ctx) {
    enum class S { TITLE, SELECT, PLAY, PAUSE, OVER }
    var state = S.TITLE
    val prefs = ctx.getSharedPreferences("atc", Context.MODE_PRIVATE)
    val radio = Radio(ctx).also { it.voice = prefs.getBoolean("voice", true) }
    val tone = try { ToneGenerator(AudioManager.STREAM_ALARM, 60) } catch (e: Exception) { null }
    val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
    val dp = resources.displayMetrics.density

    var ap: Airport = AIRPORTS[0]
    fun hiKey() = "high_${ap.icao}"
    var high = 0

    val planes = ArrayList<Plane>()
    var sel: Plane? = null
    var score = 0; var landed = 0; var exited = 0; var simT = 0f
    var spawnT = 0f; var depT = 0f; var sweep = 0f; var lastFrame = 0L
    var msg = ""; var msgT = 0f; var overReason = ""; var alarmT = 0f
    val usedCs = HashSet<String>()
    var windDir = 270f; var windSpd = 10f; var windT = 0f
    var active: List<RwyEnd> = emptyList()
    var pick: String? = null   // "direct" | "hold" waiting for fix tap

    // pending (debounced) vector instructions
    var pendP: Plane? = null; var pendT = 0f
    var pendHdg = false; var pendAlt = false; var pendSpd = false; var pendHdgFrom = 0f; var pendAltFrom = 0f; var pendSpdFrom = 0f

    var cx = 0f; var cy = 0f; var scale = 1f; var panelX = 0f; var scopeH = 0f
    val btns = ArrayList<Pair<RectF, String>>()

    val cond = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = cond }
    val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    val green = Color.rgb(80, 255, 130)

    fun shutdown() { radio.shutdown() }
    fun autoPause() { if (state == S.PLAY) { state = S.PAUSE; radio.stop() } }
    fun flash(m: String) { msg = m; msgT = 5f }

    // ---------- game setup ----------
    fun start(a: Airport) {
        ap = a; high = prefs.getInt(hiKey(), 0)
        planes.clear(); usedCs.clear(); sel = null; score = 0; landed = 0; exited = 0; simT = 0f; pick = null; pendP = null
        synchronized(radio.log) { radio.log.clear() }
        windDir = (Random.nextInt(36) * 10).toFloat(); windSpd = Random.nextInt(6, 16).toFloat()
        windT = Random.nextInt(240, 420).toFloat()
        active = computeActive()
        spawnT = 3f; depT = 30f; state = S.PLAY; lastFrame = 0L
        radio.say(2, "${ap.radio} information Alpha. Wind ${hdgTxt(windDir)} at ${windSpd.toInt()}. Landing runways ${active.joinToString(", ") { it.name }}.")
    }

    fun headwind(e: RwyEnd) = windSpd * cos(Math.toRadians((windDir - e.hdg).toDouble())).toFloat()
    fun computeActive(): List<RwyEnd> {
        val best = ap.ends.maxOf { cos(Math.toRadians((windDir - it.hdg).toDouble())) }
        return ap.ends.filter { val c = cos(Math.toRadians((windDir - it.hdg).toDouble())); c > 0.05 && c >= best - 0.35 }.take(4)
    }

    fun callsign(): String {
        val list = if (ap.icao.startsWith("E")) AIRLINES_UK else AIRLINES_US
        while (true) {
            val al = list.random()
            val n = when (Random.nextInt(3)) { 0 -> Random.nextInt(10, 100); 1 -> Random.nextInt(100, 1000); else -> Random.nextInt(1000, 3000) }
            val c = "$al$n"; if (usedCs.add(c)) return c
        }
    }
    fun acType(cs: String) = when {
        cs.startsWith("SWA") -> listOf("B737", "B738", "B38M").random()
        cs.startsWith("FDX") || cs.startsWith("UPS") -> listOf("B763", "B752", "MD11").random()
        cs.startsWith("SKW") || cs.startsWith("RPA") -> listOf("CRJ9", "E175").random()
        cs.startsWith("EZY") -> listOf("A319", "A320", "A20N").random()
        cs.startsWith("UAE") -> listOf("A388", "B77W").random()
        cs.startsWith("BAW") || cs.startsWith("VIR") -> listOf("A320", "A321", "B772", "A35K", "B789").random()
        else -> listOf("B738", "A320", "A321", "B739", "B77W", "A333", "B752", "A20N").random()
    }

    fun spawnArrival() {
        val st = ap.stars.random(); val f0 = st.fixes[0]
        if (planes.any { hypot(it.x - f0.x, it.y - f0.y) < 7f && abs(it.alt - st.alts[0]) < 2000 }) return
        val cs = callsign(); val p = Plane(cs, acType(cs), true)
        val outR = Math.toRadians(bearing(0f, 0f, f0.x, f0.y).toDouble())
        p.x = sin(outR).toFloat() * (RADIUS - 0.5f); p.y = cos(outR).toFloat() * (RADIUS - 0.5f)
        p.star = st; p.routeIdx = 0; p.nav = Nav.ROUTE; p.descendVia = true
        p.hdg = bearing(p.x, p.y, f0.x, f0.y); p.tHdg = p.hdg
        p.alt = st.alts[0] + 1000f; p.tAlt = st.alts[0]; p.spd = 290f; p.tSpd = 280f
        planes.add(p)
        radio.say(1, "${ap.radio} approach, $cs, descending via the ${st.name} arrival, ${altTxt(p.tAlt)}, information Alpha.", low = true)
    }
    fun spawnDeparture() {
        val rw = active.random()
        if (planes.any { it.ground || (!it.arrival && it.alt < 2500 && hypot(it.x - rw.tx, it.y - rw.ty) < 5f) }) return
        if (planes.any { it.arrival && it.onLoc && it.ils == rw && hypot(it.x - rw.tx, it.y - rw.ty) < 6f }) return
        val cs = callsign(); val p = Plane(cs, acType(cs), false)
        p.x = rw.tx; p.y = rw.ty; p.hdg = rw.hdg; p.tHdg = rw.hdg
        p.alt = 0f; p.tAlt = 5000f; p.spd = 0f; p.tSpd = 250f; p.ground = true
        p.exitFix = ap.exits.random(); p.exitAlt = (Random.nextInt(10, 18) * 1000).toFloat()
        planes.add(p); flash("$cs rolling runway ${rw.name}, exit ${p.exitFix!!.name} at ${altTxt(p.exitAlt)}")
    }

    // ---------- simulation ----------
    fun update(dtReal: Float) {
        val dt = dtReal * TIME_SCALE
        simT += dt; sweep = norm(sweep + dtReal * 90f)
        if (msgT > 0) msgT -= dtReal
        if (alarmT > 0) alarmT -= dtReal
        if (pendP != null) { pendT -= dtReal; if (pendT <= 0) flushPending() }
        val minutes = simT / 60f
        val arrInt = max(35f, 110f - minutes * 6f); val depInt = max(45f, 130f - minutes * 6f)
        val maxPlanes = min(14, 3 + (minutes / 1.5f).toInt())
        spawnT -= dt; depT -= dt
        if (spawnT <= 0 && planes.size < maxPlanes) { spawnArrival(); spawnT = arrInt * (0.7f + Random.nextFloat() * 0.6f) }
        if (depT <= 0 && planes.size < maxPlanes) { spawnDeparture(); depT = depInt * (0.7f + Random.nextFloat() * 0.6f) }
        windT -= dt
        if (windT <= 0) shiftWind()

        for (p in planes) movePlane(p, dt)
        planes.removeAll { it.done }
        if (sel?.done == true) sel = null

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
            score -= dt.roundToInt()
            if (alarmT <= 0) {
                alarmT = 2f
                tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
                try { vib?.vibrate(VibrationEffect.createOneShot(150, 120)) } catch (_: Exception) {}
            }
        }
    }

    fun shiftWind() {
        windT = Random.nextInt(240, 480).toFloat()
        windDir = norm(windDir + (if (Random.nextBoolean()) 1 else -1) * Random.nextInt(3, 13) * 10f)
        windSpd = Random.nextInt(4, 22).toFloat()
        val na = computeActive()
        if (na.map { it.name } != active.map { it.name }) {
            active = na
            radio.say(2, "Attention all aircraft, runway change. Wind ${hdgTxt(windDir)} at ${windSpd.toInt()}. Now landing runways ${active.joinToString(", ") { it.name }}.")
            flash("RUNWAY CHANGE: landing ${active.joinToString(" ") { it.name }}")
        } else flash("Wind now ${hdgTxt(windDir)}/${windSpd.toInt()}")
    }

    fun movePlane(p: Plane, dt: Float) {
        p.trailT -= dt
        if (p.trailT <= 0 && !p.ground) { p.trail.add(PointF(p.x, p.y)); if (p.trail.size > 6) p.trail.removeAt(0); p.trailT = 12f }
        if (p.ground) { p.spd += 6f * dt; if (p.spd >= 150f) { p.ground = false; p.alt = 50f } }
        if (!p.arrival && !p.checkedIn && p.alt > 900f) {
            p.checkedIn = true
            radio.say(1, "${ap.radio} departure, ${p.cs}, passing ${altTxt(p.alt)} for ${altTxt(p.tAlt)}.", low = true)
        }

        val ils = p.ils
        var ilsGuided = false
        if (ils != null) {
            val rh = Math.toRadians(ils.hdg.toDouble()); val ux = sin(rh).toFloat(); val uy = cos(rh).toFloat()
            val dx = p.x - ils.tx; val dy = p.y - ils.ty
            val along = -(dx * ux + dy * uy); val cross = dx * uy - dy * ux
            if (!p.onLoc && along in 0.5f..22f && abs(cross) < 1.2f && abs(angDiff(p.hdg, ils.hdg)) < 50f) {
                p.onLoc = true; p.nav = Nav.HDG; p.turnDir = 0; flash("${p.cs} established ILS ${ils.name}")
            }
            if (p.onLoc) {
                ilsGuided = true
                p.tHdg = norm(ils.hdg + (cross * 35f).coerceIn(-30f, 30f))
                val gs = along * 318f
                p.tSpd = if (along < 6) 140f else min(p.tSpd, 180f)
                if (p.alt > gs) p.tAlt = max(0f, gs)
                if (along < 0.3f) {
                    when {
                        headwind(ils) < -5f -> goAround(p, "tailwind ${(-headwind(ils)).roundToInt()} knots")
                        p.alt < 400f && p.spd <= 190f -> { p.done = true; landed++; score += 100; flash("${p.cs} landed ${ils.name} +100"); radio.say(0, "${p.cs}, welcome to ${ap.radio}, contact ground point niner.", low = true) }
                        else -> goAround(p, "unstable")
                    }
                    return
                }
                if (p.alt - gs > 2500f && along < 4f) { goAround(p, "too high"); return }
            }
        }
        if (!ilsGuided) when (p.nav) {
            Nav.HDG -> {}
            Nav.ROUTE -> {
                val st = p.star!!
                val f = st.fixes[p.routeIdx]
                p.tHdg = bearing(p.x, p.y, f.x, f.y)
                if (hypot(p.x - f.x, p.y - f.y) < 1.2f) {
                    p.routeIdx++
                    if (p.descendVia && p.routeIdx < st.alts.size) p.tAlt = st.alts[p.routeIdx]
                    if (p.routeIdx >= st.fixes.size) {
                        p.routeIdx = st.fixes.size; startHold(p, f)
                        radio.say(1, "${p.cs}, entering published hold at ${f.name}, request vectors.", low = true)
                    } else if (p.routeIdx == st.fixes.size - 1) p.tSpd = min(p.tSpd, 250f)
                }
            }
            Nav.DIRECT -> {
                val f = p.navFix!!
                p.tHdg = bearing(p.x, p.y, f.x, f.y)
                if (p.arrival && hypot(p.x - f.x, p.y - f.y) < 1.2f) {
                    val st = p.star; val idx = st?.fixes?.indexOf(f) ?: -1
                    if (st != null && idx >= 0 && idx < st.fixes.size - 1) { p.routeIdx = idx + 1; p.nav = Nav.ROUTE }
                    else if (st != null && idx == st.fixes.size - 1) { p.routeIdx = st.fixes.size; startHold(p, f) }
                    else p.nav = Nav.HDG
                }
            }
            Nav.HOLD -> {
                val f = p.navFix!!
                val brg = bearing(p.x, p.y, f.x, f.y); val dist = hypot(p.x - f.x, p.y - f.y)
                p.tSpd = min(p.tSpd, 230f)
                when (p.holdPhase) {
                    0 -> { p.tHdg = brg; p.turnDir = 0
                        if (dist < 0.8f) { if (p.holdInbound < 0) p.holdInbound = p.hdg; p.holdPhase = 1; p.holdTimer = 60f } }
                    1 -> { p.tHdg = norm(p.holdInbound + 180f); p.turnDir = 1
                        if (abs(angDiff(p.hdg, p.tHdg)) < 5f) { p.holdTimer -= dt; if (p.holdTimer <= 0) p.holdPhase = 2 } }
                    2 -> { p.tHdg = norm(p.holdInbound); p.turnDir = 1
                        if (abs(angDiff(p.hdg, brg)) < 25f) { p.holdPhase = 0; p.turnDir = 0 } }
                }
            }
        }

        if (!p.ground) {
            val rate = 3f * dt
            val d = when (p.turnDir) { 1 -> norm(p.tHdg - p.hdg).let { if (it > 358f) 0f else it }; -1 -> -norm(p.hdg - p.tHdg).let { if (it > 358f) 0f else it }; else -> angDiff(p.hdg, p.tHdg) }
            p.hdg = norm(p.hdg + d.coerceIn(-rate, rate))
            val vr = if (p.tAlt > p.alt) 2200f / 60f else (if (p.onLoc) 1200f else 1800f) / 60f
            p.alt += (p.tAlt - p.alt).coerceIn(-vr * dt, vr * dt)
            p.spd += (p.tSpd - p.spd).coerceIn(-2f * dt, 2f * dt)
        }
        val nm = p.spd / 3600f * dt; val hr = Math.toRadians(p.hdg.toDouble())
        p.x += sin(hr).toFloat() * nm; p.y += cos(hr).toFloat() * nm
        if (hypot(p.x, p.y) > RADIUS + 0.5f) leaveScope(p)
    }

    fun startHold(p: Plane, f: Fix) { p.nav = Nav.HOLD; p.navFix = f; p.holdPhase = 0; p.holdInbound = -1f; p.turnDir = 0 }

    fun goAround(p: Plane, why: String) {
        p.ils = null; p.onLoc = false; p.nav = Nav.HDG; p.tAlt = 3000f; p.tSpd = 210f; p.tHdg = p.hdg; p.descendVia = false
        score -= 50; flash("${p.cs} go-around ($why) -50")
        radio.say(1, "${p.cs}, going around, $why.")
        radio.say(0, "${p.cs}, roger, fly runway heading, climb and maintain 3,000.")
    }
    fun leaveScope(p: Plane) {
        p.done = true
        if (p.arrival) { score -= 150; flash("${p.cs} left your airspace -150"); return }
        val f = p.exitFix!!; val dist = hypot(p.x - f.x, p.y - f.y)
        if (dist < 6f && abs(p.alt - p.exitAlt) <= 500f) { exited++; score += 75; flash("${p.cs} handed off at ${f.name} +75"); radio.say(0, "${p.cs}, contact center, good day.", low = true) }
        else if (dist < 6f) { score -= 40; flash("${p.cs} wrong altitude at ${f.name} -40") }
        else { score -= 100; flash("${p.cs} wrong exit -100") }
    }
    fun gameOver(r: String) {
        overReason = r; state = S.OVER; radio.stop()
        tone?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 1000)
        try { vib?.vibrate(VibrationEffect.createOneShot(600, 255)) } catch (_: Exception) {}
        if (score > high) { high = score; prefs.edit().putInt(hiKey(), high).apply() }
    }

    // ---------- radio phrasing ----------
    fun beginPending(p: Plane) {
        if (pendP != null && pendP != p) flushPending()
        if (pendP == null) { pendP = p; pendHdg = false; pendAlt = false; pendSpd = false; pendHdgFrom = p.hdg; pendAltFrom = p.alt; pendSpdFrom = p.spd }
        pendT = 1.3f
    }
    fun flushPending() {
        val p = pendP ?: return; pendP = null
        if (p.done) return
        val ca = ArrayList<String>(); val rb = ArrayList<String>()
        if (pendHdg) {
            val d = angDiff(pendHdgFrom, p.tHdg); val dir = if (d < 0) "left" else "right"
            ca += "turn $dir heading ${hdgTxt(p.tHdg)}"; rb += "${dir.replaceFirstChar { it.uppercase() }} ${hdgTxt(p.tHdg)}"
        }
        if (pendAlt) {
            val a = altTxt(p.tAlt)
            when {
                p.tAlt < pendAltFrom - 50 -> { ca += "descend and maintain $a"; rb += "down to $a" }
                p.tAlt > pendAltFrom + 50 -> { ca += "climb and maintain $a"; rb += "up to $a" }
                else -> { ca += "maintain $a"; rb += "maintain $a" }
            }
        }
        if (pendSpd) {
            val s = p.tSpd.roundToInt()
            ca += (if (p.tSpd < pendSpdFrom) "reduce speed to $s" else "increase speed to $s"); rb += "$s knots"
        }
        if (ca.isEmpty()) return
        radio.say(0, "${p.cs}, ${ca.joinToString(", ")}.")
        radio.say(1, "${rb.joinToString(", ")}, ${p.cs}.")
    }
    fun call(p: Plane, atc: String, rb: String) { flushPending(); radio.say(0, "${p.cs}, $atc."); radio.say(1, "$rb, ${p.cs}.") }

    // ---------- drawing ----------
    fun sx(x: Float) = cx + x * scale
    fun sy(y: Float) = cy - y * scale

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else min(0.1f, (now - lastFrame) / 1e9f)
        lastFrame = now
        if (state == S.PLAY) update(dt) else sweep = norm(sweep + dt * 90f)
        val w = width.toFloat(); val h = height.toFloat(); val landscape = w > h
        panelX = if (landscape) w * 0.66f else w
        scopeH = if (landscape) h else h * 0.58f
        cx = panelX / 2f; cy = scopeH / 2f
        scale = min(panelX, scopeH) / 2f / RADIUS * 0.97f
        c.drawColor(Color.rgb(1, 8, 4))
        drawScope(c)
        btns.clear()
        when (state) {
            S.TITLE -> drawTitle(c, w, h)
            S.SELECT -> drawSelect(c, w, h)
            S.PLAY, S.PAUSE -> { drawPlanes(c); drawHud(c); drawPanel(c, w, h, landscape); if (state == S.PAUSE) drawPause(c, w, h) }
            S.OVER -> { drawPlanes(c); drawOver(c, w, h) }
        }
        postInvalidateOnAnimation()
    }

    fun glow(p: Paint, col: Int, r: Float) { p.setShadowLayer(r * dp, 0f, 0f, col) }

    fun drawScope(c: Canvas) {
        val r = RADIUS * scale
        pFill.shader = RadialGradient(cx, cy, r, intArrayOf(Color.rgb(8, 46, 20), Color.rgb(4, 28, 12), Color.rgb(2, 14, 6)), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, pFill); pFill.shader = null
        pLine.strokeWidth = 1f * dp; pLine.color = Color.argb(90, 60, 220, 100); pLine.clearShadowLayer()
        for (i in 1..4) c.drawCircle(cx, cy, r * i / 4f, pLine)
        pLine.color = Color.argb(160, 80, 255, 130); pLine.strokeWidth = 2f * dp; glow(pLine, Color.argb(160, 40, 255, 100), 6f)
        c.drawCircle(cx, cy, r, pLine); pLine.clearShadowLayer()
        // compass ticks
        pLine.strokeWidth = 1f * dp
        for (a in 0 until 360 step 10) {
            val rr = Math.toRadians(a.toDouble()); val len = if (a % 30 == 0) 9 * dp else 4 * dp
            pLine.color = Color.argb(140, 80, 255, 130)
            c.drawLine(cx + sin(rr).toFloat() * r, cy - cos(rr).toFloat() * r, cx + sin(rr).toFloat() * (r - len), cy - cos(rr).toFloat() * (r - len), pLine)
        }
        pText.textSize = 9 * dp; pText.color = Color.argb(130, 80, 230, 120); pText.textAlign = Paint.Align.LEFT
        for (i in 1..4) c.drawText("${(RADIUS * i / 4).toInt()}", cx + 3 * dp, cy - r * i / 4f + 11 * dp, pText)
        // sweep
        val sw = Paint(Paint.ANTI_ALIAS_FLAG)
        sw.shader = SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(70, 60, 255, 120)), floatArrayOf(0f, 0.85f, 1f))
        c.save(); c.rotate(sweep - 90f, cx, cy); c.drawCircle(cx, cy, r, sw); c.restore()
        val sr = Math.toRadians(sweep.toDouble())
        pLine.color = Color.argb(220, 150, 255, 170); pLine.strokeWidth = 1.5f * dp; glow(pLine, Color.argb(200, 60, 255, 120), 5f)
        c.drawLine(cx, cy, cx + sin(sr).toFloat() * r, cy - cos(sr).toFloat() * r, pLine); pLine.clearShadowLayer()

        // STAR routes
        pLine.strokeWidth = 1f * dp; pLine.color = Color.argb(60, 170, 170, 255)
        pLine.pathEffect = DashPathEffect(floatArrayOf(3 * dp, 5 * dp), 0f)
        for (st in ap.stars) for (i in 0 until st.fixes.size - 1) c.drawLine(sx(st.fixes[i].x), sy(st.fixes[i].y), sx(st.fixes[i + 1].x), sy(st.fixes[i + 1].y), pLine)
        pLine.pathEffect = null
        // runways
        for (e in ap.ends) {
            val isAct = active.contains(e)
            val rh = Math.toRadians(e.hdg.toDouble()); val ux = sin(rh).toFloat(); val uy = cos(rh).toFloat()
            if (isAct) {
                pLine.color = Color.argb(110, 120, 220, 255); pLine.strokeWidth = 1f * dp
                pLine.pathEffect = DashPathEffect(floatArrayOf(6 * dp, 5 * dp), 0f)
                c.drawLine(sx(e.tx), sy(e.ty), sx(e.tx - ux * 15f), sy(e.ty - uy * 15f), pLine); pLine.pathEffect = null
                for (m in 5..15 step 5) { val mx = sx(e.tx - ux * m); val my = sy(e.ty - uy * m); c.drawCircle(mx, my, 1.5f * dp, pLine) }
                pText.textSize = 9 * dp; pText.color = Color.rgb(140, 220, 255)
                c.drawText(e.name, sx(e.tx - ux * 2.2f) - 8 * dp, sy(e.ty - uy * 2.2f) + 3 * dp, pText)
            }
            pLine.color = Color.rgb(225, 225, 225); pLine.strokeWidth = 3f * dp
            c.drawLine(sx(e.tx), sy(e.ty), sx(e.tx + ux * e.len), sy(e.ty + uy * e.len), pLine)
        }
        // fixes
        pText.textSize = 8.5f * dp
        val exitNames = ap.exits.map { it.name }.toSet()
        for (f in ap.fixes) {
            val px = sx(f.x); val py = sy(f.y); val s = 4.5f * dp
            val ex = f.name in exitNames
            pLine.strokeWidth = 1.2f * dp
            pLine.color = if (ex) Color.rgb(100, 190, 255) else Color.argb(190, 190, 230, 190)
            if (ex) glow(pLine, Color.argb(160, 60, 160, 255), 4f)
            val path = Path().apply { moveTo(px, py - s); lineTo(px + s, py + s * 0.8f); lineTo(px - s, py + s * 0.8f); close() }
            c.drawPath(path, pLine); pLine.clearShadowLayer()
            pText.color = pLine.color
            val tw = pText.measureText(f.name)
            val tx = if (px + tw + 8 * dp > cx + RADIUS * scale) px - tw - 6 * dp else px + 6 * dp
            c.drawText(f.name, tx, if (py < cy - RADIUS * scale + 12 * dp) py + 14 * dp else py - 4 * dp, pText)
        }
        if (pick != null) {
            pText.textSize = 13 * dp; pText.color = Color.YELLOW; pText.textAlign = Paint.Align.CENTER
            c.drawText(if (pick == "hold") "TAP A FIX TO HOLD AT" else "TAP A FIX TO PROCEED DIRECT", cx, cy + RADIUS * scale * 0.55f, pText); pText.textAlign = Paint.Align.LEFT
        }
    }

    val planePath = Path().apply {
        moveTo(0f, -7f); lineTo(1.2f, -2f); lineTo(7f, 1.5f); lineTo(7f, 3f); lineTo(1.2f, 1.5f); lineTo(0.8f, 5f)
        lineTo(3f, 6.5f); lineTo(3f, 7.5f); lineTo(0f, 6.8f); lineTo(-3f, 7.5f); lineTo(-3f, 6.5f); lineTo(-0.8f, 5f)
        lineTo(-1.2f, 1.5f); lineTo(-7f, 3f); lineTo(-7f, 1.5f); lineTo(-1.2f, -2f); close()
    }

    fun drawPlanes(c: Canvas) {
        for (p in planes) {
            val px = sx(p.x); val py = sy(p.y)
            val col = when { p.conflict -> Color.rgb(255, 70, 60); p == sel -> Color.rgb(255, 240, 110); p.arrival -> Color.rgb(110, 255, 150); else -> Color.rgb(110, 200, 255) }
            pFill.color = col
            for ((i, t) in p.trail.withIndex()) { pFill.alpha = 50 + i * 30; c.drawCircle(sx(t.x), sy(t.y), 1.5f * dp, pFill) }
            pFill.alpha = 255
            pLine.color = col; pLine.strokeWidth = 1.1f * dp
            val hr = Math.toRadians(p.hdg.toDouble()); val lead = p.spd / 60f * scale
            c.drawLine(px, py, px + sin(hr).toFloat() * lead, py - cos(hr).toFloat() * lead, pLine)
            c.save(); c.translate(px, py); c.rotate(p.hdg); c.scale(dp * 1.1f, dp * 1.1f)
            glow(pFill, col, 3f); c.drawPath(planePath, pFill); pFill.clearShadowLayer(); c.restore()
            if (p.conflict) { pLine.strokeWidth = 2f * dp; glow(pLine, col, 6f); c.drawCircle(px, py, 1.5f * scale, pLine); pLine.clearShadowLayer() }
            if (p == sel) {
                pLine.color = Color.YELLOW; pLine.strokeWidth = 1.5f * dp
                c.drawCircle(px, py, 15 * dp, pLine)
                val tf = when { p.nav == Nav.ROUTE -> p.star?.fixes?.getOrNull(p.routeIdx); p.nav == Nav.DIRECT || p.nav == Nav.HOLD -> p.navFix; else -> null }
                if (tf != null) { pLine.color = Color.argb(120, 255, 255, 120); pLine.pathEffect = DashPathEffect(floatArrayOf(4 * dp, 4 * dp), 0f); c.drawLine(px, py, sx(tf.x), sy(tf.y), pLine); pLine.pathEffect = null }
            }
            // data block
            val bx = px + 11 * dp; val by = py - 16 * dp
            pLine.color = col; pLine.strokeWidth = 0.8f * dp
            c.drawLine(px + 4 * dp, py - 4 * dp, bx - 1 * dp, by + 3 * dp, pLine)
            pText.textSize = 11 * dp; pText.color = col; glow(pText, Color.argb(200, 0, 0, 0), 2f)
            val tag = when { p.onLoc -> " LOC"; p.ils != null -> " ILS"; p.nav == Nav.HOLD -> " HLD"; p.nav == Nav.ROUTE -> " " + (p.star?.name ?: ""); else -> "" }
            c.drawText(p.cs + tag, bx, by, pText)
            val arrow = when { p.tAlt > p.alt + 50 -> "↑"; p.tAlt < p.alt - 50 -> "↓"; else -> " " }
            c.drawText("%03d%s%03d %d".format((p.alt / 100).roundToInt(), arrow, (p.tAlt / 100).roundToInt(), (p.spd / 10).roundToInt()), bx, by + 11.5f * dp, pText)
            val third = if (p.arrival) "${p.type} ARR" else "${p.type} ${p.exitFix!!.name.take(3)}${(p.exitAlt / 100).roundToInt()}"
            c.drawText(third, bx, by + 23 * dp, pText); pText.clearShadowLayer()
        }
    }

    fun drawHud(c: Canvas) {
        // wind box (top-right of scope)
        val bw = 92 * dp; val bx = panelX - bw - 6 * dp; val by = 6 * dp
        pFill.color = Color.argb(170, 0, 20, 8); c.drawRoundRect(bx, by, bx + bw, by + 46 * dp, 8 * dp, 8 * dp, pFill)
        pText.textSize = 11 * dp; pText.color = green
        c.drawText("WIND ${hdgTxt(windDir)}/${windSpd.toInt()}", bx + 6 * dp, by + 15 * dp, pText)
        pText.textSize = 9.5f * dp; pText.color = Color.rgb(140, 220, 255)
        c.drawText("LDG " + active.joinToString(" ") { it.name }, bx + 6 * dp, by + 30 * dp, pText)
        pText.color = Color.argb(180, 200, 255, 200); c.drawText(ap.icao, bx + 6 * dp, by + 42 * dp, pText)
        // wind arrow (points where wind blows to)
        val ar = Math.toRadians((windDir + 180f).toDouble()); val ax = bx + bw - 14 * dp; val ay = by + 30 * dp; val L = 10 * dp
        pLine.color = green; pLine.strokeWidth = 2f * dp
        val ex = ax + sin(ar).toFloat() * L; val ey = ay - cos(ar).toFloat() * L
        c.drawLine(ax - sin(ar).toFloat() * L, ay + cos(ar).toFloat() * L, ex, ey, pLine)
        c.drawCircle(ex, ey, 2.5f * dp, pFill.apply { color = green })
        // radio transcript strip (top-left)
        val lines = synchronized(radio.log) { radio.log.takeLast(4) }
        val tw = panelX - bw - 18 * dp
        pFill.color = Color.argb(150, 0, 14, 6); c.drawRoundRect(6 * dp, 6 * dp, 6 * dp + tw, 6 * dp + (8 + 13 * 4) * dp, 8 * dp, 8 * dp, pFill)
        pText.textSize = 10.5f * dp
        for ((i, l) in lines.withIndex()) {
            pText.color = when (l.who) { 0 -> Color.rgb(255, 230, 140); 1 -> Color.rgb(170, 255, 190); else -> Color.rgb(140, 210, 255) }
            pText.alpha = 130 + 125 * (i + 1) / lines.size
            val pre = when (l.who) { 0 -> "ATC  "; 1 -> "PLT  "; else -> "ATIS " }
            var t = pre + l.text
            while (pText.measureText(t) > tw - 12 * dp && t.length > 4) t = t.dropLast(2)
            c.drawText(t, 12 * dp, 6 * dp + (17 + 13 * i) * dp, pText)
        }
        pText.alpha = 255
        if (msgT > 0) {
            pText.textSize = 12.5f * dp; pText.color = Color.argb((min(1f, msgT) * 255).toInt(), 255, 240, 160)
            c.drawText(msg, 10 * dp, scopeH - 10 * dp, pText)
        }
    }

    fun button(c: Canvas, r: RectF, label: String, id: String, on: Boolean = false, accent: Int = Color.rgb(28, 92, 50)) {
        val top = if (on) Color.rgb(230, 190, 50) else accent
        val bot = Color.rgb(Color.red(top) / 2, Color.green(top) / 2, Color.blue(top) / 2)
        pFill.shader = LinearGradient(0f, r.top, 0f, r.bottom, top, bot, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, 10 * dp, 10 * dp, pFill); pFill.shader = null
        pLine.color = Color.argb(120, 200, 255, 210); pLine.strokeWidth = 1 * dp
        c.drawRoundRect(r, 10 * dp, 10 * dp, pLine)
        pText.color = if (on) Color.BLACK else Color.WHITE
        pText.textSize = min(15 * dp, r.height() * 0.4f); pText.textAlign = Paint.Align.CENTER
        var t = label; while (pText.measureText(t) > r.width() - 6 * dp && pText.textSize > 8 * dp) pText.textSize -= 1 * dp
        c.drawText(t, r.centerX(), r.centerY() + pText.textSize * 0.35f, pText); pText.textAlign = Paint.Align.LEFT
        btns.add(r to id)
    }

    fun drawPanel(c: Canvas, w: Float, h: Float, landscape: Boolean) {
        val left = if (landscape) panelX else 0f; val top = if (landscape) 0f else scopeH
        val pw = w - left
        pFill.shader = LinearGradient(left, 0f, w, 0f, Color.rgb(6, 20, 12), Color.rgb(10, 28, 18), Shader.TileMode.CLAMP)
        c.drawRect(left, top, w, h, pFill); pFill.shader = null
        pLine.color = Color.argb(120, 80, 255, 130); pLine.strokeWidth = 1 * dp
        if (landscape) c.drawLine(left, 0f, left, h, pLine) else c.drawLine(0f, top, w, top, pLine)
        val pad = 8 * dp; val gap = 5 * dp
        pText.textSize = 14 * dp; pText.color = green
        c.drawText("SCORE $score", left + pad, top + 19 * dp, pText)
        pText.textSize = 10.5f * dp; pText.color = Color.rgb(130, 200, 140)
        c.drawText("HI $high  ▪ LDG $landed ▪ OUT $exited ▪ ${(simT / 60).toInt()}m", left + pad, top + 35 * dp, pText)
        button(c, RectF(w - pad - 44 * dp, top + 5 * dp, w - pad, top + 38 * dp), "II", "pause")
        button(c, RectF(w - pad - 44 * dp - gap - 56 * dp, top + 5 * dp, w - pad - 44 * dp - gap, top + 38 * dp), if (radio.voice) "🔊" else "🔇", "voice", radio.voice)
        val p = sel
        var y = top + 44 * dp
        if (p == null) {
            pText.textSize = 11.5f * dp; pText.color = Color.rgb(160, 225, 170)
            val lines = listOf("Tap an aircraft to select.", "", "GREEN arrivals fly their STAR", "(dashed) then hold at the last fix.",
                "Vector them onto an active final", "(blue dashes) & clear the ILS.", "", "BLUE departures: send to exit", "fix (blue ▲) at the altitude in",
                "their tag (BET140 = BETTE 14,000).", "", "Drag from a selected plane to", "set heading. Keep 3 nm / 1000 ft.", "Watch the wind: runways change!")
            for (l in lines) { c.drawText(l, left + pad, y + 12 * dp, pText); y += 14.5f * dp }
            return
        }
        pText.textSize = 13.5f * dp; pText.color = Color.YELLOW
        var hdr = "${p.cs} ${p.type} " + if (p.arrival) (p.star?.name ?: "ARR") else "→${p.exitFix!!.name} ${altTxt(p.exitAlt)}"
        while (pText.measureText(hdr) > pw - 2 * pad) hdr = hdr.dropLast(1)
        c.drawText(hdr, left + pad, y + 12 * dp, pText)
        y += 20 * dp
        val rows = 6
        val bh = min(54 * dp, (h - y - pad - gap * (rows - 1)) / rows)
        val c3 = (pw - 2 * pad - 2 * gap) / 3
        fun cell(i: Int, n: Int): RectF { val cw = (pw - 2 * pad - (n - 1) * gap) / n; return RectF(left + pad + i * (cw + gap), y, left + pad + i * (cw + gap) + cw, y + bh) }
        fun label(r: RectF, a: String, b: String) {
            pText.textAlign = Paint.Align.CENTER; pText.textSize = min(15 * dp, bh * 0.36f); pText.color = Color.WHITE
            c.drawText(a, r.centerX(), r.centerY() - 1 * dp, pText)
            pText.textSize = min(11 * dp, bh * 0.27f); pText.color = Color.rgb(150, 220, 160)
            c.drawText(b, r.centerX(), r.centerY() + pText.textSize + 2 * dp, pText); pText.textAlign = Paint.Align.LEFT
        }
        button(c, cell(0, 3), "◀ 10°", "h-10"); label(cell(1, 3), "HDG ${hdgTxt(p.tHdg)}", "now ${hdgTxt(p.hdg)}"); button(c, cell(2, 3), "10° ▶", "h+10"); y += bh + gap
        button(c, cell(0, 3), "▼ 1000", "a-"); label(cell(1, 3), "ALT ${(p.tAlt / 100).roundToInt()}", "now ${(p.alt / 100).roundToInt()}"); button(c, cell(2, 3), "▲ 1000", "a+"); y += bh + gap
        button(c, cell(0, 3), "− 10 kt", "s-"); label(cell(1, 3), "SPD ${p.tSpd.roundToInt()}", "now ${p.spd.roundToInt()}"); button(c, cell(2, 3), "+ 10 kt", "s+"); y += bh + gap
        val blue = Color.rgb(28, 74, 128)
        if (p.arrival) {
            val n = active.size.coerceAtLeast(1)
            for ((i, e) in active.withIndex()) button(c, cell(i, n), "ILS ${e.name}", "ils:${e.name}", p.ils == e, blue)
        } else {
            button(c, cell(0, 2), "DCT ${p.exitFix!!.name}", "dctexit", p.nav == Nav.DIRECT && p.navFix == p.exitFix, blue)
            button(c, cell(1, 2), "CLIMB ${(p.exitAlt / 100).roundToInt()}", "climbx", false, blue)
        }
        y += bh + gap
        val amber = Color.rgb(120, 76, 24)
        button(c, cell(0, 3), "HOLD @FIX", "hold", p.nav == Nav.HOLD || pick == "hold", amber)
        button(c, cell(1, 3), "DCT FIX", "direct", pick == "direct", amber)
        if (p.arrival) button(c, cell(2, 3), "RESUME", "resume_route", p.nav == Nav.ROUTE, amber)
        else button(c, cell(2, 3), "RWY HDG", "rwyhdg", false, amber)
        y += bh + gap
        button(c, cell(0, 1), "DESELECT", "desel", false, Color.rgb(55, 60, 58))
    }

    fun overlay(c: Canvas, w: Float, h: Float) { pFill.color = Color.argb(185, 0, 0, 0); c.drawRect(0f, 0f, w, h, pFill) }
    fun center(c: Canvas, t: String, y: Float, size: Float, col: Int, glowIt: Boolean = false) {
        pText.textSize = size; pText.color = col; pText.textAlign = Paint.Align.CENTER
        if (glowIt) glow(pText, col, 10f)
        c.drawText(t, width / 2f, y, pText); pText.clearShadowLayer(); pText.textAlign = Paint.Align.LEFT
    }
    fun drawTitle(c: Canvas, w: Float, h: Float) {
        overlay(c, w, h)
        center(c, "APPROACH CONTROL", h * 0.24f, 36 * dp, Color.rgb(140, 255, 160), true)
        center(c, "Radar ATC simulator ▪ v0.2", h * 0.24f + 26 * dp, 13 * dp, Color.rgb(170, 220, 180))
        center(c, "Vector arrivals to the ILS, hand off departures, mind the wind.", h * 0.24f + 46 * dp, 13 * dp, Color.rgb(170, 220, 180))
        button(c, RectF(w / 2 - 120 * dp, h * 0.55f, w / 2 + 120 * dp, h * 0.55f + 58 * dp), "START SHIFT", "toselect")
        button(c, RectF(w / 2 - 120 * dp, h * 0.55f + 68 * dp, w / 2 + 120 * dp, h * 0.55f + 112 * dp), if (radio.voice) "VOICE: ON" else "VOICE: OFF", "voice", radio.voice)
    }
    fun drawSelect(c: Canvas, w: Float, h: Float) {
        overlay(c, w, h)
        center(c, "SELECT AIRPORT", 44 * dp, 26 * dp, Color.rgb(140, 255, 160), true)
        val n = AIRPORTS.size; val cols = if (w > h) 2 else 1
        val rowsN = (n + cols - 1) / cols
        val cw = min(320 * dp, (w - 3 * 16 * dp) / cols); val ch = min(84 * dp, (h - 80 * dp - 16 * dp * rowsN) / rowsN)
        val x0 = (w - (cw * cols + 16 * dp * (cols - 1))) / 2
        for ((i, a) in AIRPORTS.withIndex()) {
            val col = i % cols; val row = i / cols
            val r = RectF(x0 + col * (cw + 16 * dp), 64 * dp + row * (ch + 14 * dp), x0 + col * (cw + 16 * dp) + cw, 64 * dp + row * (ch + 14 * dp) + ch)
            button(c, r, "", "ap:$i")
            pText.textSize = min(20 * dp, ch * 0.32f); pText.color = Color.WHITE; c.drawText(a.icao, r.left + 14 * dp, r.top + ch * 0.38f, pText)
            pText.textSize = min(12 * dp, ch * 0.2f); pText.color = Color.rgb(190, 235, 200)
            c.drawText(a.city, r.left + 14 * dp, r.top + ch * 0.62f, pText)
            c.drawText("${a.ends.size / 2} runways ▪ HI ${prefs.getInt("high_${a.icao}", 0)}", r.left + 14 * dp, r.top + ch * 0.85f, pText)
        }
        button(c, RectF(12 * dp, 12 * dp, 80 * dp, 48 * dp), "◀", "totitle", false, Color.rgb(55, 60, 58))
    }
    fun drawPause(c: Canvas, w: Float, h: Float) {
        overlay(c, w, h)
        center(c, "PAUSED", h * 0.28f, 32 * dp, Color.WHITE)
        button(c, RectF(w / 2 - 110 * dp, h * 0.4f, w / 2 + 110 * dp, h * 0.4f + 54 * dp), "RESUME", "resume")
        button(c, RectF(w / 2 - 110 * dp, h * 0.4f + 64 * dp, w / 2 + 110 * dp, h * 0.4f + 118 * dp), "QUIT", "quit", false, Color.rgb(120, 40, 40))
    }
    fun drawOver(c: Canvas, w: Float, h: Float) {
        overlay(c, w, h)
        center(c, "GAME OVER", h * 0.2f, 34 * dp, Color.rgb(255, 90, 80), true)
        center(c, overReason, h * 0.2f + 30 * dp, 15 * dp, Color.WHITE)
        center(c, "${ap.icao}  ▪  Score $score  ▪  Landed $landed  ▪  Handoffs $exited", h * 0.2f + 56 * dp, 15 * dp, Color.rgb(160, 255, 170))
        center(c, if (score >= high && score > 0) "NEW ${ap.icao} HIGH SCORE!" else "${ap.icao} high score: $high", h * 0.2f + 80 * dp, 15 * dp, Color.YELLOW)
        button(c, RectF(w / 2 - 230 * dp, h * 0.62f, w / 2 - 10 * dp, h * 0.62f + 54 * dp), "NEW SHIFT", "again")
        button(c, RectF(w / 2 + 10 * dp, h * 0.62f, w / 2 + 230 * dp, h * 0.62f + 54 * dp), "CHANGE AIRPORT", "toselect", false, Color.rgb(28, 74, 128))
    }

    // ---------- input ----------
    var dragging = false
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                for ((r, id) in btns.reversed()) if (r.contains(x, y)) { press(id); return true }
                if (state != S.PLAY) return true
                if (width > height && x > panelX) return true
                if (width <= height && y > scopeH) return true
                val s = sel
                if (pick != null && s != null) {
                    val f = ap.fixes.minByOrNull { hypot(sx(it.x) - x, sy(it.y) - y) }
                    if (f != null && hypot(sx(f.x) - x, sy(f.y) - y) < 45 * dp) {
                        if (pick == "hold") {
                            startHold(s, f); s.ils = null; s.onLoc = false
                            call(s, "hold at ${f.name} as published, right turns, expect further clearance", "Hold at ${f.name}")
                        } else {
                            s.nav = Nav.DIRECT; s.navFix = f; s.turnDir = 0; if (s.onLoc) { s.ils = null; s.onLoc = false }
                            call(s, "proceed direct ${f.name}", "Direct ${f.name}")
                        }
                    }
                    pick = null; return true
                }
                if (s != null && hypot(sx(s.x) - x, sy(s.y) - y) < 30 * dp) { dragging = true; return true }
                val near = planes.minByOrNull { hypot(sx(it.x) - x, sy(it.y) - y) }
                if (near != null && hypot(sx(near.x) - x, sy(near.y) - y) < 45 * dp) { if (near != sel) flushPending(); sel = near }
            }
            MotionEvent.ACTION_MOVE -> if (dragging) sel?.let { p ->
                val px = sx(p.x); val py = sy(p.y)
                if (hypot(x - px, y - py) > 22 * dp) {
                    val b = norm(Math.toDegrees(atan2((x - px).toDouble(), (py - y).toDouble())).toFloat())
                    setHeading(p, norm((b / 5f).roundToInt() * 5f))
                }
            }
            MotionEvent.ACTION_UP -> dragging = false
        }
        return true
    }

    fun setHeading(p: Plane, h: Float) {
        beginPending(p); pendHdg = true
        p.nav = Nav.HDG; p.turnDir = 0
        if (p.onLoc) { p.ils = null; p.onLoc = false }
        p.tHdg = h
    }

    fun press(id: String) {
        when {
            id == "toselect" -> { state = S.SELECT; return }
            id == "totitle" -> { state = S.TITLE; return }
            id.startsWith("ap:") -> { start(AIRPORTS[id.substring(3).toInt()]); return }
            id == "again" -> { start(ap); return }
            id == "pause" -> { state = S.PAUSE; radio.stop(); return }
            id == "resume" -> { state = S.PLAY; lastFrame = 0L; return }
            id == "voice" -> { radio.voice = !radio.voice; if (!radio.voice) radio.stop(); prefs.edit().putBoolean("voice", radio.voice).apply(); return }
            id == "quit" -> { if (score > high) { high = score; prefs.edit().putInt(hiKey(), high).apply() }; radio.stop(); state = S.TITLE; return }
        }
        val p = sel ?: return
        when {
            id == "h-10" -> setHeading(p, norm(p.tHdg - 10))
            id == "h+10" -> setHeading(p, norm(p.tHdg + 10))
            id == "a-" -> if (!p.onLoc) { beginPending(p); pendAlt = true; p.descendVia = false; p.tAlt = max(2000f, p.tAlt - 1000) }
            id == "a+" -> if (!p.onLoc) { beginPending(p); pendAlt = true; p.descendVia = false; p.tAlt = min(24000f, p.tAlt + 1000) }
            id == "s-" -> { beginPending(p); pendSpd = true; p.tSpd = max(if (p.arrival) 160f else 210f, p.tSpd - 10) }
            id == "s+" -> { beginPending(p); pendSpd = true; p.tSpd = min(340f, p.tSpd + 10) }
            id.startsWith("ils:") -> {
                val rw = active.firstOrNull { it.name == id.substring(4) } ?: return
                p.ils = rw; p.onLoc = false
                if (p.nav == Nav.HOLD) { p.nav = Nav.HDG; p.turnDir = 0 }
                call(p, "maintain ${altTxt(p.tAlt)} until established, cleared ILS runway ${rw.name} approach", "Cleared ILS ${rw.name}")
            }
            id == "dctexit" -> { val f = p.exitFix!!; p.nav = Nav.DIRECT; p.navFix = f; p.turnDir = 0; call(p, "proceed direct ${f.name}", "Direct ${f.name}") }
            id == "climbx" -> { val a = altTxt(p.exitAlt); p.tAlt = p.exitAlt; call(p, "climb and maintain $a", "Up to $a") }
            id == "rwyhdg" -> setHeading(p, p.hdg)
            id == "hold" -> pick = if (pick == "hold") null else "hold"
            id == "direct" -> pick = if (pick == "direct") null else "direct"
            id == "resume_route" -> {
                val st = p.star ?: return
                if (p.routeIdx >= st.fixes.size) { radio.say(1, "${p.cs}, we're at the end of the ${st.name}, say vectors."); return }
                p.nav = Nav.ROUTE; p.turnDir = 0; p.ils = null; p.onLoc = false; p.descendVia = true
                call(p, "resume the ${st.name} arrival, descend via the arrival", "Resume ${st.name}, descend via")
            }
            id == "desel" -> { flushPending(); sel = null; pick = null }
        }
    }
}

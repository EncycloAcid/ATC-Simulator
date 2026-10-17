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
enum class G { AIR, GATE, TAXI, HOLD_SHORT, LINEUP, TAKEOFF, ROLLOUT, EXITED, PARKED }

class Plane(val cs: String, val type: String, val arrival: Boolean) {
    var x = 0f; var y = 0f; var hdg = 0f; var alt = 0f; var spd = 0f
    var tHdg = 0f; var tAlt = 0f; var tSpd = 0f; var turnDir = 0
    var nav = Nav.HDG
    var star: Star? = null; var routeIdx = 0; var descendVia = false
    var navFix: Fix? = null
    var holdInbound = -1f; var holdPhase = 0; var holdTimer = 0f
    var exitFix: Fix? = null; var exitAlt = 0f
    var ils: RwyEnd? = null; var onLoc = false
    var checkedIn = false
    var gs = G.AIR; var gRwy: RwyEnd? = null; var onRwy = false; val path = ArrayList<Int>(); var gNode = -1
    var gate = -1; var held = false; var waiting = false; var clearedTO = false; var parkedT = 0f; var pendingSid = false
    val heavy get() = isHeavy(type)
    val trail = ArrayList<PointF>(); var trailT = 0f
    var conflict = false; var done = false
}

fun norm(a: Float): Float { var h = a % 360f; if (h < 0) h += 360f; return h }
fun angDiff(a: Float, b: Float): Float { var d = norm(b - a); if (d > 180) d -= 360; return d }
fun bearing(x0: Float, y0: Float, x1: Float, y1: Float) = norm(Math.toDegrees(atan2((x1 - x0).toDouble(), (y1 - y0).toDouble())).toFloat())
fun altTxt(a: Float): String { val r = (a / 100).roundToInt() * 100; return if (r >= 18000) "FL%03d".format(r / 100) else "%,d".format(r) }
fun hdgTxt(h: Float) = "%03d".format(norm(h).roundToInt().let { if (it == 0) 360 else it })

class GameView(ctx: Context) : View(ctx) {
    enum class S { TITLE, SELECT, CAREER, PLAY, PAUSE, OVER }
    enum class M { FREE, CAREER, TUTORIAL }
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
    var pick: String? = null
    var mode = M.FREE; var traffic = 1f; var trafficIdx = 1; var level: Level? = null; var shiftWon = false
    var career = prefs.getInt("career", 0)
    lateinit var gnd: GroundNet; var sids: Map<String, Star> = emptyMap()
    var groundView = false; var ox = 0f; var oy = 0f
    val lastDepT = HashMap<Int, Float>(); val lastDepHeavy = HashMap<Int, Boolean>()
    var tut = 0; var tutPlane: Plane? = null; var tutDep: Plane? = null   // "direct" | "hold" waiting for fix tap

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
    fun start(a: Airport, m: M = mode, lvl: Level? = null) {
        ap = a; mode = m; level = lvl; shiftWon = false; high = prefs.getInt(hiKey(), 0)
        traffic = when (m) { M.CAREER -> lvl!!.traffic; M.TUTORIAL -> 0f; else -> listOf(0.7f, 1f, 1.5f)[trafficIdx] }
        gnd = GroundNet(a); sids = buildSids(a); lastDepT.clear(); lastDepHeavy.clear(); groundView = false
        tut = 0; tutPlane = null; tutDep = null
        planes.clear(); usedCs.clear(); sel = null; score = 0; landed = 0; exited = 0; simT = 0f; pick = null; pendP = null
        synchronized(radio.log) { radio.log.clear() }
        windDir = (Random.nextInt(36) * 10).toFloat(); windSpd = Random.nextInt(6, 16).toFloat()
        windT = Random.nextInt(240, 420).toFloat()
        if (m == M.TUTORIAL) { windDir = a.ends[1].hdg; windSpd = 8f; windT = 1e9f }
        active = computeActive()
        spawnT = 3f; depT = 20f; state = S.PLAY; lastFrame = 0L
        if (m == M.TUTORIAL) spawnTutorialArrival()
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
    fun freeGates() = gnd.gates.indices.filter { i -> planes.none { it.gate == i } }
    fun spawnDeparture(): Plane? {
        val free = freeGates(); if (free.size <= 1) return null
        val gi = free.random(); val g = gnd.gates[gi]
        val cs = callsign(); val p = Plane(cs, acType(cs), false)
        p.x = g.x; p.y = g.y; p.hdg = bearing(gnd.apron.x, gnd.apron.y, g.x, g.y); p.tHdg = p.hdg
        p.alt = 0f; p.tAlt = 7000f; p.spd = 0f; p.tSpd = 250f
        p.gs = G.GATE; p.gNode = g.id; p.gate = gi
        p.exitFix = ap.exits.random(); p.exitAlt = (Random.nextInt(10, 18) * 1000).toFloat(); p.star = sids[p.exitFix!!.name]
        planes.add(p)
        radio.say(1, "${ap.radio} ground, $cs at gate ${gi + 1}, ready to push and taxi, ${p.star?.name} departure, information Alpha.", low = true)
        return p
    }
    fun spawnTutorialArrival() {
        val e = active[0]; val rh = Math.toRadians(e.hdg.toDouble())
        val cs = "BAW117"; usedCs.add(cs); val p = Plane(cs, "A320", true)
        p.x = e.tx - sin(rh).toFloat() * 20f + cos(rh).toFloat() * 6f; p.y = e.ty - cos(rh).toFloat() * 20f - sin(rh).toFloat() * 6f
        p.hdg = e.hdg; p.tHdg = p.hdg; p.alt = 6000f; p.tAlt = 6000f; p.spd = 210f; p.tSpd = 210f
        planes.add(p); tutPlane = p
        radio.say(1, "${ap.radio} approach, $cs, 6,000, information Alpha.")
    }

    // ---------- simulation ----------
    fun update(dtReal: Float) {
        val dt = dtReal * TIME_SCALE
        simT += dt; sweep = norm(sweep + dtReal * 90f)
        if (msgT > 0) msgT -= dtReal
        if (alarmT > 0) alarmT -= dtReal
        if (pendP != null) { pendT -= dtReal; if (pendT <= 0) flushPending() }
        val minutes = simT / 60f
        if (mode != M.TUTORIAL) {
            val arrInt = max(35f, 120f - minutes * 5f) / traffic; val depInt = max(45f, 140f - minutes * 5f) / traffic
            val maxPlanes = min(16, ((3 + minutes / 1.5f) * traffic).toInt().coerceAtLeast(2))
            val air = planes.count { it.gs == G.AIR }
            spawnT -= dt; depT -= dt
            if (spawnT <= 0 && air < maxPlanes) { spawnArrival(); spawnT = arrInt * (0.7f + Random.nextFloat() * 0.6f) }
            if (depT <= 0 && planes.count { !it.arrival } < maxPlanes) { spawnDeparture(); depT = depInt * (0.7f + Random.nextFloat() * 0.6f) }
        } else tutorialTick()
        level?.let { lv ->
            if (!shiftWon && landed >= lv.land && exited >= lv.out) { shiftWon = true; finishShift(true); return }
            if (simT >= lv.minutes * 60f) { finishShift(false); return }
        }
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
        val gp = planes.filter { it.gs != G.AIR && it.gs != G.PARKED }
        for (i in gp.indices) for (j in i + 1 until gp.size) {
            val a = gp[i]; val b = gp[j]
            val d = hypot(a.x - b.x, a.y - b.y)
            val aMov = a.gs == G.TAXI && !a.held && !a.waiting; val bMov = b.gs == G.TAXI && !b.held && !b.waiting
            if (d < 0.02f && (aMov || bMov || a.gs == G.TAKEOFF || b.gs == G.TAKEOFF || a.gs == G.ROLLOUT || b.gs == G.ROLLOUT)) { gameOver("Ground collision: ${a.cs} and ${b.cs}"); return }
            if (d < 0.06f && aMov && bMov) { a.conflict = true; b.conflict = true; newConflict = true }
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
        if (p.gs != G.AIR) { moveGround(p, dt); return }
        if (p.trailT <= 0) { p.trail.add(PointF(p.x, p.y)); if (p.trail.size > 6) p.trail.removeAt(0); p.trailT = 12f }
        if (!p.arrival && !p.checkedIn && p.alt > 900f) {
            p.checkedIn = true
            radio.say(1, "${ap.radio} departure, ${p.cs}, passing ${altTxt(p.alt)} for ${altTxt(p.tAlt)}" + (if (p.pendingSid) ", ${p.star?.name} departure." else "."), low = true)
        }
        if (p.pendingSid && p.alt > 1500f) { p.pendingSid = false; if (p.star != null) { p.nav = Nav.ROUTE; p.routeIdx = 0 } }

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
                    val ph = physOf(ils)
                    when {
                        headwind(ils) < -5f -> goAround(p, "tailwind ${(-headwind(ils)).roundToInt()} knots")
                        rwyOccupied(ph, p) -> { goAround(p, "runway occupied"); score -= 100; flash("RUNWAY INCURSION on ${ils.name} -150") }
                        p.alt < 400f && p.spd <= 190f -> {
                            p.gs = G.ROLLOUT; p.gRwy = ils; p.onRwy = true; p.alt = 0f; p.tAlt = 0f; p.spd = 140f; p.hdg = ils.hdg
                            p.ils = null; p.onLoc = false; p.x = ils.tx; p.y = ils.ty; p.trail.clear()
                            landed++; score += 100; flash("${p.cs} landed ${ils.name} +100")
                            radio.say(0, "${p.cs}, exit ${gnd.exits[ph]?.label} when able, contact ground.", low = true)
                        }
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
                    if (p.routeIdx >= st.fixes.size && !p.arrival) { p.nav = Nav.HDG; p.tHdg = p.hdg }
                    else if (p.routeIdx >= st.fixes.size) {
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

        run {
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

    fun physOf(e: RwyEnd) = ap.ends.indexOf(e) / 2
    fun rwyOccupied(ph: Int, except: Plane?) = planes.any { it != except && it.onRwy && it.gRwy != null && physOf(it.gRwy!!) == ph }
    fun shortFinal(ph: Int) = planes.any { val e = it.ils; it.onLoc && e != null && physOf(e) == ph && hypot(it.x - e.tx, it.y - e.ty) < 3f }
    fun wakeWait(p: Plane, ph: Int): Float {
        val t = lastDepT[ph] ?: return 0f
        val need = if (lastDepHeavy[ph] == true) (if (p.heavy) 90f else 120f) else 60f
        return max(0f, need - (simT - t))
    }

    fun stepToward(p: Plane, tx: Float, ty: Float, kt: Float, dt: Float): Boolean {
        val d = hypot(tx - p.x, ty - p.y); val step = kt / 3600f * dt
        if (d < 0.003f) return true
        p.hdg = bearing(p.x, p.y, tx, ty); p.spd = kt
        if (step >= d) { p.x = tx; p.y = ty; return true }
        p.x += (tx - p.x) / d * step; p.y += (ty - p.y) / d * step; return false
    }

    fun moveGround(p: Plane, dt: Float) {
        when (p.gs) {
            G.TAXI -> {
                if (p.held || p.path.isEmpty()) { p.spd = 0f; return }
                val n = gnd.node(p.path[0])
                val brg = bearing(p.x, p.y, n.x, n.y)
                p.waiting = planes.any { q -> q != p && q.gs != G.AIR && q.gs != G.TAKEOFF && q.gs != G.ROLLOUT &&
                    (q.gs != G.TAXI || q.held || q.waiting) && hypot(q.x - p.x, q.y - p.y) < 0.07f &&
                    abs(angDiff(brg, bearing(p.x, p.y, q.x, q.y))) < 35f }
                if (p.waiting) { p.spd = 0f; return }
                if (stepToward(p, n.x, n.y, 20f, dt)) {
                    p.gNode = n.id; p.path.removeAt(0)
                    if (p.path.isEmpty()) arriveNode(p, n)
                }
            }
            G.LINEUP -> {
                val e = p.gRwy!!
                if (stepToward(p, e.tx, e.ty, 12f, dt)) {
                    p.hdg = e.hdg; p.spd = 0f
                    if (p.clearedTO) { p.gs = G.TAKEOFF }
                }
            }
            G.TAKEOFF -> {
                val e = p.gRwy!!; p.hdg = e.hdg
                p.spd += 6f * dt
                val hr = Math.toRadians(p.hdg.toDouble()); val nm = p.spd / 3600f * dt
                p.x += sin(hr).toFloat() * nm; p.y += cos(hr).toFloat() * nm
                if (p.spd >= 150f) {
                    p.gs = G.AIR; p.onRwy = false; p.alt = 50f; p.tAlt = 7000f; p.tSpd = 250f; p.tHdg = e.hdg; p.nav = Nav.HDG
                    p.pendingSid = true; p.gate = -1; score += 25
                }
            }
            G.ROLLOUT -> {
                val e = p.gRwy!!
                p.spd = max(25f, p.spd - 4f * dt)
                val hr = Math.toRadians(p.hdg.toDouble()); val nm = p.spd / 3600f * dt
                p.x += sin(hr).toFloat() * nm; p.y += cos(hr).toFloat() * nm
                if (hypot(p.x - e.tx, p.y - e.ty) >= e.len * 0.5f) {
                    val ex = gnd.exits[physOf(e)]!!
                    p.gs = G.TAXI; p.path.clear(); p.path.add(ex.id)
                }
            }
            G.PARKED -> { p.parkedT -= dt; if (p.parkedT <= 0) p.done = true }
            else -> { p.spd = 0f }
        }
    }

    fun arriveNode(p: Plane, n: TNode) {
        p.spd = 0f
        when (n.kind) {
            TNode.HS -> { p.gs = G.HOLD_SHORT; p.hdg = bearing(p.x, p.y, n.end!!.tx, n.end.ty)
                radio.say(1, "${ap.radio} tower, ${p.cs}, holding short runway ${n.label}, ready for departure.", low = true) }
            TNode.EXIT -> { p.gs = G.EXITED; p.onRwy = false
                radio.say(1, "${ap.radio} ground, ${p.cs}, clear of the runway at ${n.label}, request taxi to the gate.", low = true) }
            TNode.GATE -> { p.gs = G.PARKED; p.parkedT = 40f; score += 25; flash("${p.cs} parked at gate ${n.label} +25") }
            else -> { p.gs = G.TAXI }
        }
    }

    /** route a ground plane to node target; returns taxiway names or null */
    fun taxiTo(p: Plane, target: TNode): List<String>? {
        val start = if (p.gs == G.TAXI && p.path.isNotEmpty()) p.path[0] else p.gNode
        if (start < 0) return null
        val r = gnd.route(start, target.id) ?: return null
        p.path.clear(); p.path.addAll(if (p.gs == G.TAXI && start != p.gNode) r else r.drop(1))
        p.held = false; p.waiting = false
        if (p.path.isEmpty()) { arriveNode(p, target); return emptyList() }
        p.gs = G.TAXI
        val nm = gnd.names((if (p.gNode >= 0) listOf(p.gNode) else emptyList()) + p.path)
        return nm
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
    fun finishShift(win: Boolean) {
        val lv = level ?: return
        val idx = CAREER.indexOf(lv)
        overReason = if (win) "SHIFT COMPLETE — objectives met!" else "Shift over — objectives not met"
        if (win && idx == career) { career++; prefs.edit().putInt("career", career).apply()
            overReason += "  Promoted to ${rankName()}" }
        state = S.OVER; radio.stop()
        if (score > high) { high = score; prefs.edit().putInt(hiKey(), high).apply() }
    }
    fun rankName() = if (career == 0) "Student" else CAREER[min(career, CAREER.size) - 1].rank + if (career >= CAREER.size) " ★" else ""

    val tutText = listOf(
        "Welcome! Tap the green aircraft BAW117 (lower part of the scope) to select it.",
        "Turn it toward the blue dashed final: tap ◀/▶ 10° or drag from the plane. Aim to cross the dashed line at about 30°.",
        "Descend: tap ▼1000 until the target ALT reads 40 (4,000 ft). The pilot reads it back.",
        "Now tap ILS for the runway whose dashed final it will cross. It will capture the localizer and glideslope.",
        "Good. Watch it land (you can keep vectoring if it misses the final)…",
        "It's rolling out. Tap the GND button (top of the panel) to open the ground view.",
        "When it's clear of the runway, select it and tap TAXI GATE. Arrivals park at gates.",
        "A departure is at a gate. Select it and tap TAXI to an active runway (or tap a yellow hold-short bar).",
        "When it's holding short, tap CLEARED T/O (or LINE UP first). No takeoff while someone's on short final!",
        "Airborne! Tap RADAR to go back. It flies its SID. Select it, tap HOLD @FIX, then tap any fix.",
        "Tutorial complete! You know select, heading, altitude, ILS, taxi, takeoff and holds. Tap the ✕ to finish.")
    fun tutorialTick() {
        val a = tutPlane; val d = tutDep
        val ok = when (tut) {
            0 -> sel != null && sel == a
            1 -> a != null && a.nav == Nav.HDG && abs(angDiff(a.tHdg, active[0].hdg)) > 5f
            2 -> a != null && a.tAlt <= 4000f
            3 -> a != null && a.ils != null
            4 -> a != null && a.gs != G.AIR
            5 -> groundView
            6 -> a != null && (a.gs == G.TAXI && a.path.isNotEmpty() && gnd.node(a.path.last()).kind == TNode.GATE || a.gs == G.PARKED || a.done)
            7 -> d != null && (d.gs == G.TAXI || d.gs == G.HOLD_SHORT)
            8 -> d != null && (d.gs == G.TAKEOFF || d.gs == G.AIR)
            9 -> d != null && d.nav == Nav.HOLD
            else -> false
        }
        if (a != null && a.done && tut <= 4 && a.gs == G.AIR) { tutPlane = null; spawnTutorialArrival() }
        if (ok) {
            tut++
            if (tut == 7) tutDep = spawnDeparture()
            tone?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        }
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
    fun sx(x: Float) = cx + (x - ox) * scale
    fun sy(y: Float) = cy - (y - oy) * scale
    // ---------- modern UI theme ----------
    val BG = Color.rgb(11, 14, 18); val SURF = Color.argb(238, 22, 27, 34); val SURF2 = Color.rgb(34, 41, 50)
    val ACC = Color.rgb(61, 220, 151); val TXT = Color.rgb(232, 237, 242); val MUTED = Color.rgb(138, 150, 163)
    val DANGER = Color.rgb(255, 92, 92); val WARN = Color.rgb(255, 181, 71)
    val sans = Typeface.create("sans-serif", Typeface.NORMAL); val sansMed = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val sansBold = Typeface.create("sans-serif", Typeface.BOLD)
    var lastState = S.TITLE; var stateT = 0f; var sheetAnim = 0f; var sheetCollapsed = false
    var pressedId: String? = null; var pressT = 0f
    var sheetRect = RectF()

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else min(0.1f, (now - lastFrame) / 1e9f)
        lastFrame = now
        if (state != lastState) { lastState = state; stateT = 0f }
        stateT += dt; if (pressT > 0) pressT -= dt
        if (state == S.PLAY) update(dt) else sweep = norm(sweep + dt * 90f)
        val w = width.toFloat(); val h = height.toFloat(); val landscape = w > h
        val inGame = state == S.PLAY || state == S.PAUSE || state == S.OVER
        val want = if (inGame && (sel != null || groundView)) 1f else 0f
        sheetAnim += (want - sheetAnim) * min(1f, dt * 10f)
        val sheetW = if (landscape) min(340 * dp, w * 0.42f) else w
        val sheetH = if (landscape) h else if (sheetCollapsed) 64 * dp else min(h * 0.46f, 380 * dp)
        val sheetFull = if (landscape) (if (sheetCollapsed) 72 * dp else sheetW) else sheetH
        panelX = if (landscape) w - sheetFull * sheetAnim else w
        scopeH = if (landscape) h else h - sheetFull * sheetAnim
        cx = panelX / 2f; cy = scopeH / 2f + (if (inGame) 16 * dp else 0f)
        if (groundView && inGame) {
            ox = (gnd.minX + gnd.maxX) / 2; oy = (gnd.minY + gnd.maxY) / 2
            scale = min(panelX / (gnd.maxX - gnd.minX + 0.6f), (scopeH - 130 * dp) / (gnd.maxY - gnd.minY + 0.6f))
        } else { ox = 0f; oy = 0f; scale = min(panelX, scopeH - (if (inGame) 40 * dp else 0f)) / 2f / RADIUS * 0.97f }
        c.drawColor(BG)
        if (groundView && inGame) drawGround(c) else drawScope(c)
        btns.clear()
        when (state) {
            S.TITLE -> fade(c) { drawTitle(c, w, h) }
            S.SELECT -> fade(c) { drawSelect(c, w, h) }
            S.CAREER -> fade(c) { drawCareer(c, w, h) }
            S.PLAY, S.PAUSE -> {
                drawPlanes(c); drawHud(c, w, h)
                if (sheetAnim > 0.01f) drawSheet(c, w, h, landscape, sheetW, sheetH)
                if (mode == M.TUTORIAL) drawTutorial(c)
                if (state == S.PAUSE) fade(c) { drawPause(c, w, h) }
            }
            S.OVER -> { drawPlanes(c); fade(c) { drawOver(c, w, h) } }
        }
        postInvalidateOnAnimation()
    }

    inline fun fade(c: Canvas, block: () -> Unit) {
        val a = min(1f, stateT * 5f)
        val sc = c.saveLayerAlpha(null, (a * 255).toInt())
        c.translate(0f, (1f - a) * 24 * dp); block(); c.restoreToCount(sc)
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
        pLine.color = Color.argb(55, 100, 190, 255)
        for (st in sids.values) { c.drawLine(sx(0f), sy(0f), sx(st.fixes[0].x), sy(st.fixes[0].y), pLine); c.drawLine(sx(st.fixes[0].x), sy(st.fixes[0].y), sx(st.fixes[1].x), sy(st.fixes[1].y), pLine) }
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
        for (f in ap.fixes + sids.values.map { it.fixes[0] }) {
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
            if (groundView) { if (p.gs == G.AIR && (p.alt > 3000f || hypot(p.x - ox, p.y - oy) > 6f)) continue }
            else if (p.gs != G.AIR) continue
            val px = sx(p.x); val py = sy(p.y)
            if (groundView && p.gs != G.AIR) { drawGroundPlane(c, p, px, py); continue }
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
    // ---------- ground view ----------
    fun drawGround(c: Canvas) {
        pFill.shader = RadialGradient(cx, cy, max(width, height).toFloat(), Color.rgb(16, 26, 22), BG, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill); pFill.shader = null
        // grass grid
        pLine.color = Color.argb(18, 120, 255, 170); pLine.strokeWidth = 1f
        var gx = sx(ox) % (scale * 0.25f); while (gx < panelX) { c.drawLine(gx, 0f, gx, scopeH, pLine); gx += scale * 0.25f }
        // runways
        for (k in 0 until ap.ends.size / 2) {
            val a = ap.ends[2 * k]; val b = ap.ends[2 * k + 1]
            pLine.color = Color.rgb(58, 64, 72); pLine.strokeWidth = max(10 * dp, 0.06f * scale); pLine.strokeCap = Paint.Cap.BUTT
            c.drawLine(sx(a.tx), sy(a.ty), sx(b.tx), sy(b.ty), pLine)
            pLine.color = Color.argb(200, 240, 240, 240); pLine.strokeWidth = 1.2f * dp
            pLine.pathEffect = DashPathEffect(floatArrayOf(8 * dp, 7 * dp), 0f)
            c.drawLine(sx(a.tx), sy(a.ty), sx(b.tx), sy(b.ty), pLine); pLine.pathEffect = null; pLine.strokeCap = Paint.Cap.ROUND
            for (e in listOf(a, b)) {
                val act = active.contains(e)
                pText.typeface = sansBold; pText.textSize = 11 * dp; pText.textAlign = Paint.Align.CENTER
                val rh = Math.toRadians(e.hdg.toDouble())
                val lx = sx(e.tx - sin(rh).toFloat() * 0.12f); val ly = sy(e.ty - cos(rh).toFloat() * 0.12f)
                pFill.color = if (act) ACC else SURF2; c.drawRoundRect(lx - 16 * dp, ly - 9 * dp, lx + 16 * dp, ly + 9 * dp, 9 * dp, 9 * dp, pFill)
                pText.color = if (act) BG else TXT; c.drawText(e.name, lx, ly + 4 * dp, pText)
            }
        }
        // taxiways
        for (e in gnd.edges) {
            val a = gnd.node(e.a); val b = gnd.node(e.b)
            pLine.color = if (e.name == "ramp") Color.argb(120, 230, 200, 80) else Color.rgb(214, 180, 60)
            pLine.strokeWidth = (if (e.name == "ramp") 1.5f else 2.5f) * dp
            c.drawLine(sx(a.x), sy(a.y), sx(b.x), sy(b.y), pLine)
            if (e.name != "ramp" && hypot(sx(a.x) - sx(b.x), sy(a.y) - sy(b.y)) > 28 * dp) {
                val mx = (sx(a.x) + sx(b.x)) / 2; val my = (sy(a.y) + sy(b.y)) / 2
                pFill.color = Color.rgb(20, 20, 20); c.drawRoundRect(mx - 11 * dp, my - 7 * dp, mx + 11 * dp, my + 7 * dp, 3 * dp, 3 * dp, pFill)
                pText.typeface = sansBold; pText.textSize = 9 * dp; pText.color = Color.rgb(255, 210, 70); pText.textAlign = Paint.Align.CENTER
                c.drawText(e.name, mx, my + 3 * dp, pText)
            }
        }
        // apron + gates
        pFill.color = Color.argb(60, 160, 170, 180); c.drawCircle(sx(gnd.apron.x), sy(gnd.apron.y), 0.16f * scale, pFill)
        for ((i, g) in gnd.gates.withIndex()) {
            val occ = planes.any { it.gate == i }
            val gx2 = sx(g.x); val gy = sy(g.y); val r = 9 * dp
            pFill.color = if (occ) SURF2 else Color.rgb(44, 56, 66); c.drawRoundRect(gx2 - r, gy - r, gx2 + r, gy + r, 4 * dp, 4 * dp, pFill)
            pText.typeface = sansMed; pText.textSize = 9 * dp; pText.color = if (occ) MUTED else TXT; pText.textAlign = Paint.Align.CENTER
            c.drawText(g.label, gx2, gy + 3 * dp, pText)
        }
        // hold-short bars
        for (n in gnd.hs.values) {
            val e = n.end!!; val rh = Math.toRadians(e.hdg.toDouble())
            val px = sx(n.x); val py = sy(n.y); val L = 9 * dp
            val dx = sin(rh).toFloat(); val dy = -cos(rh).toFloat(); val nx = -dy; val ny = dx
            pLine.color = Color.rgb(255, 210, 60); pLine.strokeWidth = 2 * dp
            for (o in listOf(-2.5f, 2.5f)) c.drawLine(px - dx * L + nx * o * dp, py - dy * L + ny * o * dp, px + dx * L + nx * o * dp, py + dy * L + ny * o * dp, pLine)
        }
        pText.textAlign = Paint.Align.LEFT; pText.typeface = cond
    }

    fun gsLabel(p: Plane) = when (p.gs) {
        G.GATE -> "READY PUSH"; G.TAXI -> if (p.held) "HOLD POS" else if (p.waiting) "WAITING" else "TAXI"; G.HOLD_SHORT -> "HOLD SHORT"
        G.LINEUP -> if (p.clearedTO) "T/O CLR" else "LUAW"; G.TAKEOFF -> "ROLLING"; G.ROLLOUT -> "ROLLOUT"; G.EXITED -> "NEED TAXI"
        G.PARKED -> "PARKED"; G.AIR -> ""
    }
    fun drawGroundPlane(c: Canvas, p: Plane, px: Float, py: Float) {
        val needs = p.gs == G.GATE || p.gs == G.HOLD_SHORT || p.gs == G.EXITED
        val col = when { p.conflict -> DANGER; p == sel -> Color.rgb(255, 240, 110); p.arrival -> Color.rgb(110, 255, 150); else -> Color.rgb(110, 200, 255) }
        if (needs) { pFill.color = Color.argb((90 + 70 * sin(simT * 2f)).toInt().coerceIn(0, 255), Color.red(WARN), Color.green(WARN), Color.blue(WARN)); c.drawCircle(px, py, 13 * dp, pFill) }
        pFill.color = col
        c.save(); c.translate(px, py); c.rotate(p.hdg); c.scale(dp * 1.3f, dp * 1.3f); c.drawPath(planePath, pFill); c.restore()
        if (p == sel) { pLine.color = col; pLine.strokeWidth = 1.5f * dp; c.drawCircle(px, py, 16 * dp, pLine)
            if (p.path.isNotEmpty()) { pLine.pathEffect = DashPathEffect(floatArrayOf(4 * dp, 4 * dp), 0f); var lx = px; var ly = py
                for (id in p.path) { val n = gnd.node(id); c.drawLine(lx, ly, sx(n.x), sy(n.y), pLine); lx = sx(n.x); ly = sy(n.y) }; pLine.pathEffect = null } }
        val t1 = p.cs; val t2 = gsLabel(p)
        pText.typeface = sansMed; pText.textSize = 10 * dp
        val tw = max(pText.measureText(t1), pText.measureText(t2)) + 10 * dp
        val bx = px + 12 * dp; val by = py - 26 * dp
        pFill.color = Color.argb(200, 10, 14, 18); c.drawRoundRect(bx, by, bx + tw, by + 26 * dp, 6 * dp, 6 * dp, pFill)
        pText.color = col; c.drawText(t1, bx + 5 * dp, by + 11 * dp, pText)
        pText.color = if (needs) WARN else MUTED; pText.textSize = 9 * dp; c.drawText(t2, bx + 5 * dp, by + 22 * dp, pText)
        pText.typeface = cond
    }

    // ---------- HUD ----------
    fun card(c: Canvas, r: RectF, rad: Float = 16 * dp, col: Int = SURF) {
        pFill.color = col; pFill.setShadowLayer(10 * dp, 0f, 3 * dp, Color.argb(140, 0, 0, 0))
        c.drawRoundRect(r, rad, rad, pFill); pFill.clearShadowLayer()
        pLine.color = Color.argb(28, 255, 255, 255); pLine.strokeWidth = 1 * dp; c.drawRoundRect(r, rad, rad, pLine)
    }
    fun txt(c: Canvas, t: String, x: Float, y: Float, size: Float, col: Int, tf: Typeface = sans, align: Paint.Align = Paint.Align.LEFT) {
        pText.typeface = tf; pText.textSize = size; pText.color = col; pText.textAlign = align; c.drawText(t, x, y, pText)
        pText.textAlign = Paint.Align.LEFT; pText.typeface = cond
    }
    fun iconBtn(c: Canvas, x: Float, y: Float, icon: String, id: String, on: Boolean = false, badge: Int = 0) {
        val r = RectF(x, y, x + 44 * dp, y + 44 * dp)
        val k = if (pressedId == id && pressT > 0) 0.92f else 1f
        c.save(); c.scale(k, k, r.centerX(), r.centerY())
        card(c, r, 22 * dp, if (on) ACC else SURF)
        txt(c, icon, r.centerX(), r.centerY() + 6 * dp, 16 * dp, if (on) BG else TXT, sansBold, Paint.Align.CENTER)
        if (badge > 0) { pFill.color = WARN; c.drawCircle(r.right - 6 * dp, r.top + 6 * dp, 9 * dp, pFill)
            txt(c, "$badge", r.right - 6 * dp, r.top + 9.5f * dp, 10 * dp, BG, sansBold, Paint.Align.CENTER) }
        c.restore(); btns.add(r to id)
    }

    fun drawHud(c: Canvas, w: Float, h: Float) {
        val m = 10 * dp
        // score pill
        val pillW = min(panelX - 3 * 54 * dp - 2 * m, 360 * dp)
        val pill = RectF(m, m, m + pillW, m + 44 * dp); card(c, pill, 22 * dp)
        txt(c, "$score", pill.left + 16 * dp, pill.top + 29 * dp, 20 * dp, ACC, sansBold)
        val sw = pText.apply { typeface = sansBold; textSize = 20 * dp }.measureText("$score")
        val sub = level?.let { "LDG $landed/${it.land} · OUT $exited/${it.out} · ${max(0, it.minutes - (simT / 60).toInt())}m left" }
            ?: "${ap.icao} · LDG $landed · OUT $exited · HI $high"
        txt(c, sub, pill.left + 26 * dp + sw, pill.top + 27 * dp, 11.5f * dp, MUTED, sansMed)
        // icon buttons
        val gNeed = planes.count { it.gs == G.GATE || it.gs == G.HOLD_SHORT || it.gs == G.EXITED }
        var bx = panelX - m - 44 * dp
        iconBtn(c, bx, m, "II", "pause"); bx -= 52 * dp
        iconBtn(c, bx, m, if (groundView) "◎" else "▦", "gnd", groundView, if (groundView) 0 else gNeed); bx -= 52 * dp
        iconBtn(c, bx, m, if (radio.voice) "🔊" else "🔇", "voice", false)
        // wind chip
        val wr = RectF(panelX - m - 150 * dp, m + 52 * dp, panelX - m, m + 52 * dp + 34 * dp); card(c, wr, 17 * dp)
        val ar = Math.toRadians((windDir + 180f).toDouble()); val ax = wr.left + 18 * dp; val ay = wr.centerY(); val L = 8 * dp
        pLine.color = ACC; pLine.strokeWidth = 2 * dp
        c.drawLine(ax - sin(ar).toFloat() * L, ay + cos(ar).toFloat() * L, ax + sin(ar).toFloat() * L, ay - cos(ar).toFloat() * L, pLine)
        pFill.color = ACC; c.drawCircle(ax + sin(ar).toFloat() * L, ay - cos(ar).toFloat() * L, 2.5f * dp, pFill)
        txt(c, "${hdgTxt(windDir)}/${windSpd.toInt()}kt", wr.left + 32 * dp, wr.centerY() + 4.5f * dp, 12 * dp, TXT, sansBold)
        txt(c, active.joinToString(" ") { it.name }, wr.right - 10 * dp, wr.centerY() + 4 * dp, 10.5f * dp, MUTED, sansMed, Paint.Align.RIGHT)
        // radio transcript
        val lines = synchronized(radio.log) { radio.log.takeLast(3) }
        if (lines.isNotEmpty()) {
            val tw = min(panelX - 2 * m, 460 * dp); val th = (14 + 15 * lines.size) * dp
            val tr = RectF(m, scopeH - m - th, m + tw, scopeH - m); card(c, tr, 14 * dp)
            for ((i, l) in lines.withIndex()) {
                val col = when (l.who) { 0 -> ACC; 1 -> TXT; else -> Color.rgb(140, 200, 255) }
                val tag = when (l.who) { 0 -> "ATC"; 1 -> "PLT"; else -> "ATIS" }
                val y = tr.top + (19 + 15 * i) * dp
                txt(c, tag, tr.left + 12 * dp, y, 9.5f * dp, col, sansBold)
                pText.typeface = sans; pText.textSize = 11 * dp
                var t = l.text; while (pText.measureText(t) > tw - 56 * dp && t.length > 4) t = t.dropLast(2)
                txt(c, t, tr.left + 44 * dp, y, 11 * dp, if (i == lines.size - 1) TXT else MUTED)
            }
        }
        if (msgT > 0) {
            val a = (min(1f, msgT) * 255).toInt()
            pText.typeface = sansMed; pText.textSize = 12.5f * dp
            val mw = pText.measureText(msg) + 28 * dp
            val mr = RectF(cx - mw / 2, m + 54 * dp, cx + mw / 2, m + 86 * dp)
            if (mr.left > m && mr.right < panelX - 160 * dp) {
                pFill.color = Color.argb(a * 230 / 255, 22, 27, 34); c.drawRoundRect(mr, 16 * dp, 16 * dp, pFill)
                txt(c, msg, mr.centerX(), mr.centerY() + 4.5f * dp, 12.5f * dp, Color.argb(a, 255, 230, 160), sansMed, Paint.Align.CENTER)
            } else txt(c, msg, m + 6 * dp, m + 66 * dp, 12 * dp, Color.argb(a, 255, 230, 160), sansMed)
        }
    }

    fun button(c: Canvas, r: RectF, label: String, id: String, style: Int = 0, enabled: Boolean = true) {
        // style 0 tonal, 1 primary, 2 selected, 3 danger
        val k = if (pressedId == id && pressT > 0) 0.95f else 1f
        c.save(); c.scale(k, k, r.centerX(), r.centerY())
        val rad = min(14 * dp, r.height() / 2)
        val bg = when (style) { 1 -> ACC; 2 -> Color.argb(60, Color.red(ACC), Color.green(ACC), Color.blue(ACC)); 3 -> Color.argb(70, 255, 92, 92); else -> SURF2 }
        pFill.color = bg; if (style == 1) pFill.setShadowLayer(8 * dp, 0f, 2 * dp, Color.argb(110, 61, 220, 151))
        c.drawRoundRect(r, rad, rad, pFill); pFill.clearShadowLayer()
        if (style == 2) { pLine.color = ACC; pLine.strokeWidth = 1.5f * dp; c.drawRoundRect(r, rad, rad, pLine) }
        val fg = when { !enabled -> MUTED; style == 1 -> BG; style == 2 -> ACC; style == 3 -> Color.rgb(255, 150, 150); else -> TXT }
        pText.typeface = sansMed; pText.textSize = min(14 * dp, r.height() * 0.36f)
        while (pText.measureText(label) > r.width() - 10 * dp && pText.textSize > 8 * dp) pText.textSize -= 0.5f * dp
        txt(c, label, r.centerX(), r.centerY() + pText.textSize * 0.36f, pText.textSize, fg, sansMed, Paint.Align.CENTER)
        c.restore(); btns.add(r to id)
    }

    // ---------- control sheet ----------
    fun drawSheet(c: Canvas, w: Float, h: Float, landscape: Boolean, sheetW: Float, sheetH: Float) {
        val m = 10 * dp
        val full = if (landscape) (if (sheetCollapsed) 72 * dp else sheetW) else sheetH
        val off = (1f - sheetAnim) * full
        val r = if (landscape) RectF(w - full + off + 0f, m, w - m + off, h - m) else RectF(m, h - sheetH + off, w - m, h - m + off)
        if (landscape) r.left += m * 0f
        sheetRect = r
        card(c, r, 22 * dp)
        // handle / collapse
        val p = sel
        if (landscape) {
            button(c, RectF(r.left + 10 * dp, r.top + 10 * dp, r.left + 50 * dp, r.top + 50 * dp), if (sheetCollapsed) "‹" else "›", "sheet")
            if (sheetCollapsed) { if (p != null) { c.save(); c.rotate(-90f, r.centerX(), r.centerY()); txt(c, p.cs, r.centerX(), r.centerY() + 5 * dp, 14 * dp, ACC, sansBold, Paint.Align.CENTER); c.restore() }; return }
        } else {
            pFill.color = Color.argb(80, 255, 255, 255); c.drawRoundRect(r.centerX() - 20 * dp, r.top + 7 * dp, r.centerX() + 20 * dp, r.top + 11 * dp, 2 * dp, 2 * dp, pFill)
            btns.add(RectF(r.centerX() - 60 * dp, r.top, r.centerX() + 60 * dp, r.top + 24 * dp) to "sheet")
            if (sheetCollapsed) { txt(c, p?.cs ?: (if (groundView) "Ground" else ""), r.left + 18 * dp, r.top + 42 * dp, 16 * dp, ACC, sansBold); return }
        }
        val left = r.left + 14 * dp; val right = r.right - 14 * dp
        var y = r.top + (if (landscape) 62 else 22) * dp
        if (p == null) {
            txt(c, "Ground", if (landscape) r.left + 60 * dp else left, r.top + (if (landscape) 37 else 40) * dp, 18 * dp, TXT, sansBold)
            y = r.top + (if (landscape) 74 else 62) * dp
            val hints = listOf("Tap an aircraft to select it.", "Pulsing amber = needs instructions.", "Departures: push, taxi to a hold-short", "bar, then line up / cleared for takeoff.", "Arrivals: taxi to a free gate.", "Tap a gate or hold-short bar to route", "the selected aircraft there.")
            for (l in hints) { txt(c, l, left, y, 12 * dp, MUTED); y += 18 * dp }
            return
        }
        // header
        val hx = if (landscape) r.left + 60 * dp else left
        txt(c, p.cs, hx, r.top + (if (landscape) 30 else 40) * dp, 18 * dp, TXT, sansBold)
        val chip = if (p.arrival) (p.star?.name ?: "ARR") else "${p.star?.name ?: ""} → ${p.exitFix!!.name} ${altTxt(p.exitAlt)}"
        txt(c, "${p.type}${if (p.heavy) " · HEAVY" else ""} · $chip", hx, r.top + (if (landscape) 47 else 58) * dp, 11 * dp, MUTED, sansMed)
        button(c, RectF(right - 36 * dp, r.top + (if (landscape) 12 else 18) * dp, right, r.top + (if (landscape) 48 else 54) * dp), "✕", "desel")
        y = r.top + (if (landscape) 62 else 70) * dp
        val gap = 8 * dp
        val bh = if (landscape) min(46 * dp, (r.bottom - y - 14 * dp - gap * 5) / 6) else min(42 * dp, (r.bottom - y - 12 * dp - gap * 4) / 5)
        fun row(n: Int, i: Int, span: Int = 1): RectF { val cw = (right - left - (n - 1) * gap) / n; return RectF(left + i * (cw + gap), y, left + i * (cw + gap) + cw * span + gap * (span - 1), y + bh) }
        fun stepper(label: String, value: String, now: String, minus: String, plus: String, idM: String, idP: String) {
            pFill.color = Color.argb(60, 0, 0, 0); c.drawRoundRect(RectF(left, y, right, y + bh), 14 * dp, 14 * dp, pFill)
            txt(c, label, left + 12 * dp, y + bh * 0.42f, 9.5f * dp, MUTED, sansBold)
            txt(c, value, left + 12 * dp, y + bh * 0.82f, min(17 * dp, bh * 0.42f), TXT, sansBold)
            txt(c, now, left + 78 * dp, y + bh * 0.8f, 10 * dp, MUTED, sans)
            val bw = min(58 * dp, (right - left) * 0.22f)
            button(c, RectF(right - 2 * bw - gap, y + 4 * dp, right - bw - gap, y + bh - 4 * dp), minus, idM)
            button(c, RectF(right - bw, y + 4 * dp, right - 4 * dp, y + bh - 4 * dp), plus, idP)
            y += bh + gap
        }
        if (p.gs == G.AIR) {
            stepper("HEADING", hdgTxt(p.tHdg) + "°", "now ${hdgTxt(p.hdg)}", "◀ 10", "10 ▶", "h-10", "h+10")
            stepper("ALTITUDE", "${(p.tAlt / 100).roundToInt()}", "now ${(p.alt / 100).roundToInt()}", "▼", "▲", "a-", "a+")
            stepper("SPEED", "${p.tSpd.roundToInt()} kt", "now ${p.spd.roundToInt()}", "−", "+", "s-", "s+")
            if (p.arrival) {
                val n = active.size.coerceAtLeast(1)
                for ((i, e) in active.withIndex()) button(c, row(n, i), "ILS ${e.name}", "ils:${e.name}", if (p.ils == e) 2 else 1)
            } else {
                button(c, row(2, 0), "DCT ${p.exitFix!!.name}", "dctexit", if (p.nav == Nav.DIRECT && p.navFix == p.exitFix) 2 else 1)
                button(c, row(2, 1), "CLIMB ${(p.exitAlt / 100).roundToInt()}", "climbx", 1)
            }
            y += bh + gap
            button(c, row(3, 0), "HOLD @FIX", "hold", if (p.nav == Nav.HOLD || pick == "hold") 2 else 0)
            button(c, row(3, 1), "DCT FIX", "direct", if (pick == "direct") 2 else 0)
            button(c, row(3, 2), if (p.arrival) "RESUME STAR" else "RESUME SID", "resume_route", if (p.nav == Nav.ROUTE) 2 else 0)
            return
        }
        // ground states
        txt(c, "STATUS  ${gsLabel(p)}", left, y + 14 * dp, 11 * dp, if (p.gs == G.GATE || p.gs == G.HOLD_SHORT || p.gs == G.EXITED) WARN else ACC, sansBold)
        y += 26 * dp
        val canTaxi = p.gs == G.GATE || p.gs == G.TAXI || p.gs == G.EXITED || p.gs == G.HOLD_SHORT
        if (!p.arrival && canTaxi) {
            txt(c, "TAXI TO RUNWAY", left, y + 10 * dp, 9.5f * dp, MUTED, sansBold); y += 16 * dp
            val n = active.size.coerceAtLeast(1)
            for ((i, e) in active.withIndex()) button(c, row(n, i), "→ ${e.name}", "taxi:${e.name}", if (p.path.lastOrNull() == gnd.hs[e.name]?.id || (p.gs == G.HOLD_SHORT && p.gNode == gnd.hs[e.name]?.id)) 2 else 0)
            y += bh + gap
        }
        if (p.arrival && canTaxi) { button(c, row(1, 0), "TAXI TO GATE", "taxigate", if (p.gs == G.TAXI && p.path.isNotEmpty() && gnd.node(p.path.last()).kind == TNode.GATE) 2 else 1); y += bh + gap }
        if (p.gs == G.TAXI) { button(c, row(1, 0), if (p.held) "CONTINUE TAXI" else "HOLD POSITION", if (p.held) "continue" else "holdpos", if (p.held) 1 else 3); y += bh + gap }
        if (p.gs == G.HOLD_SHORT || (p.gs == G.LINEUP && !p.clearedTO)) {
            val e = p.gRwy ?: gnd.node(p.gNode).end
            val ph = if (e != null) physOf(e) else 0
            val wake = wakeWait(p, ph).roundToInt()
            val blocked = shortFinal(ph) || rwyOccupied(ph, p)
            if (p.gs == G.HOLD_SHORT) button(c, row(2, 0), "LINE UP & WAIT", "luaw", 0, !rwyOccupied(ph, p))
            button(c, if (p.gs == G.HOLD_SHORT) row(2, 1) else row(1, 0), if (wake > 0) "CLEARED T/O · wake ${wake}s" else "CLEARED T/O", "cto", if (blocked) 0 else 1, !blocked)
            y += bh + gap
            if (blocked) txt(c, if (shortFinal(ph)) "Traffic on short final" else "Runway occupied", left, y + 10 * dp, 11 * dp, DANGER, sansMed)
            else if (wake > 0) txt(c, "Wake turbulence: wait ${wake}s after the heavy", left, y + 10 * dp, 11 * dp, WARN, sansMed)
        }
    }

    // ---------- screens ----------
    fun scrim(c: Canvas, w: Float, h: Float, a: Int = 200) { pFill.color = Color.argb(a, 8, 10, 13); c.drawRect(0f, 0f, w, h, pFill) }
    fun logo(c: Canvas, x: Float, y: Float, r: Float) {
        pFill.color = Color.rgb(14, 40, 28); c.drawCircle(x, y, r, pFill)
        pLine.color = ACC; pLine.strokeWidth = 2 * dp; c.drawCircle(x, y, r, pLine)
        pLine.color = Color.argb(90, 61, 220, 151); pLine.strokeWidth = 1 * dp; c.drawCircle(x, y, r * 0.62f, pLine); c.drawCircle(x, y, r * 0.3f, pLine)
        val a = Math.toRadians(sweep.toDouble()); pLine.color = ACC; pLine.strokeWidth = 2 * dp
        c.drawLine(x, y, x + sin(a).toFloat() * r, y - cos(a).toFloat() * r, pLine)
        pFill.color = TXT; c.save(); c.translate(x + r * 0.35f, y + r * 0.3f); c.rotate(315f); c.scale(r / 22f, r / 22f); c.drawPath(planePath, pFill); c.restore()
    }
    fun drawTitle(c: Canvas, w: Float, h: Float) {
        scrim(c, w, h, 190)
        val landscape = w > h
        val lx = if (landscape) w * 0.3f else w / 2; val ly = if (landscape) h * 0.42f else h * 0.22f
        logo(c, lx, ly, 46 * dp)
        txt(c, "Approach Control", lx, ly + 86 * dp, 28 * dp, TXT, sansBold, Paint.Align.CENTER)
        txt(c, "Radar ATC simulator · v0.3", lx, ly + 110 * dp, 13 * dp, MUTED, sansMed, Paint.Align.CENTER)
        val cw = min(320 * dp, if (landscape) w * 0.42f else w - 40 * dp)
        val x0 = if (landscape) w * 0.72f - cw / 2 else (w - cw) / 2
        var y = if (landscape) h * 0.18f else h * 0.42f
        val ch = 72 * dp
        fun menuCard(icon: String, title: String, sub: String, id: String, primary: Boolean) {
            val r = RectF(x0, y, x0 + cw, y + ch); card(c, r, 18 * dp, if (primary) Color.rgb(24, 52, 40) else SURF)
            pFill.color = if (primary) ACC else SURF2; c.drawCircle(r.left + 34 * dp, r.centerY(), 20 * dp, pFill)
            txt(c, icon, r.left + 34 * dp, r.centerY() + 6 * dp, 16 * dp, if (primary) BG else TXT, sansBold, Paint.Align.CENTER)
            txt(c, title, r.left + 66 * dp, r.centerY() - 2 * dp, 16 * dp, TXT, sansBold)
            txt(c, sub, r.left + 66 * dp, r.centerY() + 16 * dp, 11.5f * dp, MUTED, sans)
            txt(c, "›", r.right - 18 * dp, r.centerY() + 7 * dp, 22 * dp, MUTED, sans, Paint.Align.CENTER)
            btns.add(r to id); y += ch + 12 * dp
        }
        menuCard("★", "Career", "Rank: ${rankName()} · ${min(career, CAREER.size)}/${CAREER.size} shifts", "tocareer", true)
        menuCard("✈", "Free play", "Pick an airport and traffic level", "toselect", false)
        menuCard("?", "Tutorial", "Learn radar, tower and ground step by step", "tutorial", false)
        button(c, RectF(x0, y, x0 + cw, y + 44 * dp), if (radio.voice) "🔊  Voice radio on" else "🔇  Voice radio off", "voice", if (radio.voice) 2 else 0)
    }
    fun backBtn(c: Canvas, id: String) { iconBtn(c, 14 * dp, 14 * dp, "‹", id) }
    fun drawSelect(c: Canvas, w: Float, h: Float) {
        scrim(c, w, h, 215); backBtn(c, "totitle")
        txt(c, "Free play", 70 * dp, 44 * dp, 22 * dp, TXT, sansBold)
        // traffic segmented control
        val names = listOf("Light", "Normal", "Heavy"); val segW = 76 * dp
        val sx0 = w - 14 * dp - segW * 3
        card(c, RectF(sx0 - 4 * dp, 16 * dp, w - 10 * dp, 56 * dp), 20 * dp)
        for (i in 0..2) {
            val locked = i == 2 && career < 5
            val r = RectF(sx0 + i * segW, 20 * dp, sx0 + (i + 1) * segW - 4 * dp, 52 * dp)
            button(c, r, (if (locked) "🔒 " else "") + names[i], "traffic:$i", if (trafficIdx == i) 1 else 0, !locked)
        }
        val n = AIRPORTS.size; val cols = if (w > h) 2 else 1; val rowsN = (n + cols - 1) / cols
        val gap = 14 * dp
        val cw = min(360 * dp, (w - 28 * dp - gap * (cols - 1)) / cols); val ch = min(96 * dp, (h - 90 * dp - gap * rowsN) / rowsN)
        val x0 = (w - (cw * cols + gap * (cols - 1))) / 2
        for ((i, a) in AIRPORTS.withIndex()) {
            val col = i % cols; val row = i / cols
            val r = RectF(x0 + col * (cw + gap), 76 * dp + row * (ch + gap), x0 + col * (cw + gap) + cw, 76 * dp + row * (ch + gap) + ch)
            val locked = career < (UNLOCK[a.icao] ?: 0)
            card(c, r, 18 * dp)
            txt(c, a.icao, r.left + 18 * dp, r.top + ch * 0.4f, min(22 * dp, ch * 0.3f), if (locked) MUTED else TXT, sansBold)
            txt(c, a.city, r.left + 18 * dp, r.top + ch * 0.64f, 12 * dp, MUTED, sansMed)
            txt(c, if (locked) "🔒 Reach career shift ${UNLOCK[a.icao]} to unlock" else "${a.ends.size / 2} runways · best ${prefs.getInt("high_${a.icao}", 0)}", r.left + 18 * dp, r.top + ch * 0.86f, 11 * dp, if (locked) WARN else ACC, sansMed)
            if (!locked) { txt(c, "›", r.right - 20 * dp, r.centerY() + 8 * dp, 24 * dp, MUTED, sans, Paint.Align.CENTER); btns.add(r to "ap:$i") }
        }
    }
    fun drawCareer(c: Canvas, w: Float, h: Float) {
        scrim(c, w, h, 215); backBtn(c, "totitle")
        txt(c, "Career", 70 * dp, 38 * dp, 22 * dp, TXT, sansBold)
        txt(c, rankName(), 70 * dp, 56 * dp, 12 * dp, ACC, sansMed)
        val pw = min(260 * dp, w * 0.35f); val pr = RectF(w - 14 * dp - pw, 32 * dp, w - 14 * dp, 40 * dp)
        pFill.color = SURF2; c.drawRoundRect(pr, 4 * dp, 4 * dp, pFill)
        pFill.color = ACC; c.drawRoundRect(RectF(pr.left, pr.top, pr.left + pw * min(1f, career / CAREER.size.toFloat()), pr.bottom), 4 * dp, 4 * dp, pFill)
        txt(c, "${min(career, CAREER.size)}/${CAREER.size} shifts", pr.right, pr.bottom + 16 * dp, 11 * dp, MUTED, sansMed, Paint.Align.RIGHT)
        val cols = if (w > h) 2 else 1; val rowsN = (CAREER.size + cols - 1) / cols; val gap = 10 * dp
        val cw = (w - 28 * dp - gap * (cols - 1)) / cols; val ch = min(70 * dp, (h - 86 * dp - gap * rowsN) / rowsN)
        for ((i, lv) in CAREER.withIndex()) {
            val col = i % cols; val row = i / cols
            val r = RectF(14 * dp + col * (cw + gap), 76 * dp + row * (ch + gap), 14 * dp + col * (cw + gap) + cw, 76 * dp + row * (ch + gap) + ch)
            val done = i < career; val cur = i == career; val locked = i > career
            card(c, r, 16 * dp, if (cur) Color.rgb(24, 52, 40) else SURF)
            pFill.color = if (done || cur) ACC else SURF2; c.drawCircle(r.left + 26 * dp, r.centerY(), 15 * dp, pFill)
            txt(c, if (done) "✓" else if (locked) "🔒" else "${i + 1}", r.left + 26 * dp, r.centerY() + 5 * dp, 13 * dp, if (done || cur) BG else MUTED, sansBold, Paint.Align.CENTER)
            txt(c, "${lv.rank} · ${lv.icao}", r.left + 52 * dp, r.centerY() - 3 * dp, min(15 * dp, ch * 0.24f), if (locked) MUTED else TXT, sansBold)
            txt(c, "Land ${lv.land} · hand off ${lv.out} · ${lv.minutes} min · traffic ×${lv.traffic}", r.left + 52 * dp, r.centerY() + 14 * dp, 11 * dp, MUTED, sans)
            if (!locked) btns.add(r to "career:$i")
        }
    }
    fun dialog(c: Canvas, w: Float, h: Float, ht: Float): RectF {
        scrim(c, w, h, 170)
        val cw = min(400 * dp, w - 40 * dp); val r = RectF((w - cw) / 2, (h - ht) / 2, (w + cw) / 2, (h + ht) / 2)
        card(c, r, 24 * dp); return r
    }
    fun drawPause(c: Canvas, w: Float, h: Float) {
        val r = dialog(c, w, h, 250 * dp)
        txt(c, "Paused", r.centerX(), r.top + 44 * dp, 22 * dp, TXT, sansBold, Paint.Align.CENTER)
        txt(c, "${ap.icao} · score $score", r.centerX(), r.top + 66 * dp, 12 * dp, MUTED, sansMed, Paint.Align.CENTER)
        val bx = RectF(r.left + 24 * dp, r.top + 86 * dp, r.right - 24 * dp, r.top + 132 * dp)
        button(c, bx, "▶  Resume", "resume", 1); bx.offset(0f, 54 * dp)
        button(c, RectF(bx.left, bx.top, bx.centerX() - 5 * dp, bx.bottom), "↻  Restart", "again", 0)
        button(c, RectF(bx.centerX() + 5 * dp, bx.top, bx.right, bx.bottom), "Quit", "quit", 3)
    }
    fun drawOver(c: Canvas, w: Float, h: Float) {
        val r = dialog(c, w, h, 300 * dp)
        val win = shiftWon
        pFill.color = if (win) ACC else DANGER; c.drawCircle(r.centerX(), r.top + 42 * dp, 22 * dp, pFill)
        txt(c, if (win) "✓" else "!", r.centerX(), r.top + 50 * dp, 22 * dp, BG, sansBold, Paint.Align.CENTER)
        txt(c, if (win) "Shift complete" else if (level != null && !overReason.startsWith("Mid") && !overReason.startsWith("Ground")) "Shift over" else "Game over", r.centerX(), r.top + 94 * dp, 22 * dp, TXT, sansBold, Paint.Align.CENTER)
        pText.textSize = 12 * dp; var reason = overReason; while (pText.measureText(reason) > r.width() - 30 * dp && reason.length > 5) reason = reason.dropLast(2)
        txt(c, reason, r.centerX(), r.top + 116 * dp, 12 * dp, MUTED, sansMed, Paint.Align.CENTER)
        val stats = listOf("SCORE" to "$score", "LANDED" to "$landed", "HANDOFFS" to "$exited", "BEST" to "$high")
        val sw = (r.width() - 40 * dp) / 4
        for ((i, st) in stats.withIndex()) {
            val x = r.left + 20 * dp + sw * i + sw / 2
            txt(c, st.second, x, r.top + 160 * dp, 20 * dp, if (i == 0) ACC else TXT, sansBold, Paint.Align.CENTER)
            txt(c, st.first, x, r.top + 178 * dp, 9.5f * dp, MUTED, sansBold, Paint.Align.CENTER)
        }
        val by = r.bottom - 66 * dp
        if (level != null) {
            val next = CAREER.getOrNull(min(career, CAREER.size - 1))
            button(c, RectF(r.left + 20 * dp, by, r.centerX() - 5 * dp, by + 46 * dp), if (win && next != null && career < CAREER.size) "Next shift" else "Retry", if (win) "career:${min(career, CAREER.size - 1)}" else "again", 1)
            button(c, RectF(r.centerX() + 5 * dp, by, r.right - 20 * dp, by + 46 * dp), "Career", "tocareer", 0)
        } else {
            button(c, RectF(r.left + 20 * dp, by, r.centerX() - 5 * dp, by + 46 * dp), "New shift", "again", 1)
            button(c, RectF(r.centerX() + 5 * dp, by, r.right - 20 * dp, by + 46 * dp), "Menu", "totitle", 0)
        }
    }
    fun drawTutorial(c: Canvas) {
        val t = tutText[min(tut, tutText.size - 1)]
        val tw = min(panelX - 20 * dp, 440 * dp)
        pText.typeface = sansMed; pText.textSize = 13 * dp
        val words = t.split(" "); val lines = ArrayList<String>(); var cur = ""
        for (wd in words) { val nx = if (cur.isEmpty()) wd else "$cur $wd"; if (pText.measureText(nx) > tw - 70 * dp) { lines.add(cur); cur = wd } else cur = nx }
        lines.add(cur)
        val th = (40 + 18 * lines.size) * dp
        val r = RectF(10 * dp, 64 * dp, 10 * dp + tw, 64 * dp + th)
        card(c, r, 18 * dp, Color.argb(245, 20, 44, 34))
        txt(c, "TUTORIAL · STEP ${min(tut + 1, tutText.size)}/${tutText.size}", r.left + 16 * dp, r.top + 22 * dp, 10 * dp, ACC, sansBold)
        for ((i, l) in lines.withIndex()) txt(c, l, r.left + 16 * dp, r.top + (42 + 18 * i) * dp, 13 * dp, TXT, sansMed)
        button(c, RectF(r.right - 40 * dp, r.top + 8 * dp, r.right - 8 * dp, r.top + 40 * dp), "✕", "quit")
    }

    // ---------- input ----------
    var dragging = false
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                for ((r, id) in btns.reversed()) if (r.contains(x, y)) { pressedId = id; pressT = 0.12f; press(id); return true }
                if (state != S.PLAY) return true
                if (sheetAnim > 0.5f && sheetRect.contains(x, y)) return true
                val s = sel
                if (groundView) {
                    val near = planes.filter { it.gs != G.AIR || (it.alt < 3000f) }.minByOrNull { hypot(sx(it.x) - x, sy(it.y) - y) }
                    if (near != null && hypot(sx(near.x) - x, sy(near.y) - y) < 30 * dp) { if (near != sel) flushPending(); sel = near; sheetCollapsed = false; return true }
                    if (s != null && s.gs != G.AIR) {
                        val n = (gnd.gates + gnd.hs.values).minByOrNull { hypot(sx(it.x) - x, sy(it.y) - y) }
                        if (n != null && hypot(sx(n.x) - x, sy(n.y) - y) < 34 * dp) {
                            if (n.kind == TNode.GATE && s.arrival) cmdTaxiGate(s, gnd.gates.indexOf(n))
                            else if (n.kind == TNode.HS && !s.arrival) cmdTaxiRwy(s, n.label)
                        }
                    }
                    return true
                }
                if (pick != null && s != null) {
                    val f = (ap.fixes + sids.values.map { it.fixes[0] }).minByOrNull { hypot(sx(it.x) - x, sy(it.y) - y) }
                    if (f != null && hypot(sx(f.x) - x, sy(f.y) - y) < 45 * dp) {
                        if (pick == "hold") {
                            startHold(s, f); s.ils = null; s.onLoc = false; s.pendingSid = false
                            call(s, "hold at ${f.name} as published, right turns, expect further clearance", "Hold at ${f.name}")
                        } else {
                            s.nav = Nav.DIRECT; s.navFix = f; s.turnDir = 0; s.pendingSid = false; if (s.onLoc) { s.ils = null; s.onLoc = false }
                            call(s, "proceed direct ${f.name}", "Direct ${f.name}")
                        }
                    }
                    pick = null; return true
                }
                if (s != null && s.gs == G.AIR && hypot(sx(s.x) - x, sy(s.y) - y) < 30 * dp) { dragging = true; return true }
                val near = planes.filter { it.gs == G.AIR }.minByOrNull { hypot(sx(it.x) - x, sy(it.y) - y) }
                if (near != null && hypot(sx(near.x) - x, sy(near.y) - y) < 45 * dp) { if (near != sel) flushPending(); sel = near; sheetCollapsed = false }
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
        p.nav = Nav.HDG; p.turnDir = 0; p.pendingSid = false
        if (p.onLoc) { p.ils = null; p.onLoc = false }
        p.tHdg = h
    }

    fun gcall(p: Plane, atc: String, rb: String) = call(p, atc, rb)
    fun cmdTaxiRwy(p: Plane, end: String) {
        val n = gnd.hs[end] ?: return
        val wasGate = p.gs == G.GATE
        if (p.gs == G.HOLD_SHORT && p.gNode == n.id) return
        if (p.gs == G.HOLD_SHORT) p.gs = G.TAXI.also { }
        val names = taxiTo(p, n) ?: run { flash("No taxi route"); return }
        if (wasGate) p.hdg = norm(p.hdg + 180f)
        val via = if (names.isEmpty()) "" else " via ${names.joinToString(", ")}"
        gcall(p, (if (wasGate) "push back approved, " else "") + "taxi to runway $end$via, hold short of runway $end", "Taxi runway $end$via, hold short")
    }
    fun cmdTaxiGate(p: Plane, want: Int = -1) {
        val free = freeGates().toMutableList(); if (p.gate >= 0) free.add(p.gate)
        val gi = if (want >= 0 && want in free) want else free.minByOrNull { hypot(gnd.gates[it].x - p.x, gnd.gates[it].y - p.y) } ?: run { flash("No free gates"); return }
        p.gate = gi
        val names = taxiTo(p, gnd.gates[gi]) ?: run { flash("No taxi route"); return }
        val via = if (names.isEmpty()) "" else " via ${names.joinToString(", ")}"
        gcall(p, "taxi to gate ${gi + 1}$via", "Gate ${gi + 1}$via")
    }

    fun press(id: String) {
        when {
            id == "toselect" -> { state = S.SELECT; return }
            id == "tocareer" -> { state = S.CAREER; return }
            id == "totitle" -> { state = S.TITLE; return }
            id == "tutorial" -> { start(AIRPORTS.first { it.icao == "EGLL" }, M.TUTORIAL); return }
            id.startsWith("traffic:") -> { val i = id.substring(8).toInt(); if (i < 2 || career >= 5) trafficIdx = i; return }
            id.startsWith("ap:") -> { start(AIRPORTS[id.substring(3).toInt()], M.FREE); return }
            id.startsWith("career:") -> { val lv = CAREER[id.substring(7).toInt()]; start(AIRPORTS.first { it.icao == lv.icao }, M.CAREER, lv); return }
            id == "again" -> { start(ap, mode, level); return }
            id == "pause" -> { state = S.PAUSE; radio.stop(); return }
            id == "resume" -> { state = S.PLAY; lastFrame = 0L; return }
            id == "voice" -> { radio.voice = !radio.voice; if (!radio.voice) radio.stop(); prefs.edit().putBoolean("voice", radio.voice).apply(); return }
            id == "gnd" -> { groundView = !groundView; pick = null; return }
            id == "sheet" -> { sheetCollapsed = !sheetCollapsed; return }
            id == "quit" -> { if (score > high && mode != M.TUTORIAL) { high = score; prefs.edit().putInt(hiKey(), high).apply() }; radio.stop(); state = S.TITLE; return }
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
            id == "dctexit" -> { val f = p.exitFix!!; p.nav = Nav.DIRECT; p.navFix = f; p.turnDir = 0; p.pendingSid = false; call(p, "proceed direct ${f.name}", "Direct ${f.name}") }
            id == "climbx" -> { val a = altTxt(p.exitAlt); p.tAlt = p.exitAlt; call(p, "climb and maintain $a", "Up to $a") }
            id == "hold" -> pick = if (pick == "hold") null else "hold"
            id == "direct" -> pick = if (pick == "direct") null else "direct"
            id == "resume_route" -> {
                val st = p.star ?: return
                if (p.routeIdx >= st.fixes.size) { radio.say(1, "${p.cs}, we're at the end of the ${st.name}, say vectors."); return }
                p.nav = Nav.ROUTE; p.turnDir = 0; p.ils = null; p.onLoc = false; p.descendVia = p.arrival; p.pendingSid = false
                if (p.arrival) call(p, "resume the ${st.name} arrival, descend via the arrival", "Resume ${st.name}, descend via")
                else call(p, "resume the ${st.name} departure", "Resume ${st.name}")
            }
            id.startsWith("taxi:") -> cmdTaxiRwy(p, id.substring(5))
            id == "taxigate" -> cmdTaxiGate(p)
            id == "holdpos" -> { p.held = true; gcall(p, "hold position", "Holding position") }
            id == "continue" -> { p.held = false; gcall(p, "continue taxi", "Continuing") }
            id == "luaw" -> {
                val e = gnd.node(p.gNode).end ?: return; val ph = physOf(e)
                if (rwyOccupied(ph, p)) { flash("Runway ${e.name} occupied"); return }
                p.gRwy = e; p.onRwy = true; p.gs = G.LINEUP; p.clearedTO = false
                gcall(p, "runway ${e.name}, line up and wait", "Line up and wait runway ${e.name}")
            }
            id == "cto" -> {
                val e = p.gRwy ?: gnd.node(p.gNode).end ?: return; val ph = physOf(e)
                if (shortFinal(ph)) { flash("UNABLE: traffic on short final ${e.name}"); radio.say(1, "${p.cs}, holding, traffic on short final."); return }
                if (rwyOccupied(ph, p)) { flash("UNABLE: runway ${e.name} occupied"); return }
                val wake = wakeWait(p, ph)
                p.gRwy = e; p.onRwy = true; p.gs = G.LINEUP; p.clearedTO = true
                lastDepT[ph] = simT; lastDepHeavy[ph] = p.heavy
                if (wake > 0) { score -= 40; flash("WAKE TURBULENCE: ${wake.roundToInt()}s early -40") }
                gcall(p, "wind ${hdgTxt(windDir)} at ${windSpd.toInt()}, runway ${e.name}, cleared for takeoff" + (if (wake > 0) ", caution wake turbulence" else ""), "Cleared for takeoff runway ${e.name}")
            }
            id == "desel" -> { flushPending(); sel = null; pick = null }
        }
    }
}

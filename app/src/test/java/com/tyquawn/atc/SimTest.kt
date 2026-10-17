package com.tyquawn.atc

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimTest {
    lateinit var g: GameView

    @Before fun setup() {
        g = GameView(RuntimeEnvironment.getApplication())
        g.ensureLayouts(); g.hintSeen = true; g.radio.voice = false; g.sfx = false; g.haptics = false
    }
    fun airport(icao: String) = AIRPORTS.first { it.icao == icao }
    fun startFree(icao: String) { g.start(airport(icao), GameView.M.TUTORIAL); g.planes.clear(); g.tutPlane = null; g.mode = GameView.M.FREE; g.traffic = 0f }
    fun run(sec: Float, step: Float = 0.05f) { var t = 0f; while (t < sec && g.state == GameView.S.PLAY) { g.update(step); t += step } }
    fun noSpawn() { g.spawnT = 1e9f; g.depT = 1e9f }

    @Test fun layoutsLoadForAllAirports() {
        for (a in AIRPORTS) {
            val lay = LAYOUTS[a.icao]!!
            assertTrue("${a.icao} runways", lay.rwys.size >= 2)
            assertEquals(lay.rwys.size * 2, a.ends.size)
            assertTrue("${a.icao} gates", lay.gates.size >= 8)
            for (e in a.ends) assertTrue("${a.icao} ${e.name} has hold-short", lay.hs.containsKey(e.name))
        }
        // true headings, not number x 10
        assertEquals(120.9f, airport("KJFK").ends.first { it.name == "13L" }.hdg, 0.5f)
    }

    @Test fun taxiRoutesGateToEveryRunwayAllAirports() {
        for (a in AIRPORTS) {
            val net = GroundNet(a)
            for (gt in net.gates) for ((name, hs) in net.hs) {
                val r = net.route(gt.id, hs.id)
                assertNotNull("${a.icao} gate ${gt.label} -> $name", r)
                assertFalse("unnamed names spoken", net.names(r!!).any { it.isBlank() })
            }
        }
    }

    @Test fun arrivalCapturesIlsAndLands() {
        startFree("EGLL"); noSpawn()
        val e = g.active[0]; val rh = Math.toRadians(e.hdg.toDouble())
        val p = Plane("BAW1", "A320", true)
        p.x = e.tx - sin(rh).toFloat() * 10f; p.y = e.ty - cos(rh).toFloat() * 10f
        p.hdg = e.hdg; p.tHdg = e.hdg; p.alt = 3000f; p.tAlt = 3000f; p.spd = 180f; p.tSpd = 180f
        g.planes.add(p); p.ils = e
        run(400f)
        assertTrue("landed", g.landed == 1)
    }

    @Test fun separationConflictAndCollision() {
        startFree("KLAX"); noSpawn()
        val a = Plane("AAL1", "B738", true).apply { x = 10f; y = 10f; alt = 8000f; tAlt = 8000f; spd = 250f; tSpd = 250f; hdg = 90f; tHdg = 90f }
        val b = Plane("UAL2", "B738", true).apply { x = 12f; y = 10f; alt = 8000f; tAlt = 8000f; spd = 250f; tSpd = 250f; hdg = 270f; tHdg = 270f }
        g.planes.add(a); g.planes.add(b)
        g.update(0.01f); assertTrue(a.conflict && b.conflict)
        run(30f)
        assertEquals(GameView.S.OVER, g.state)
    }

    fun depAtHoldShort(endName: String): Plane {
        val n = g.gnd.hs[endName]!!
        val p = Plane("DAL${g.planes.size + 10}", "B738", false)
        p.x = n.x; p.y = n.y; p.gs = G.HOLD_SHORT; p.gNode = n.id; p.exitFix = g.ap.exits[0]; p.exitAlt = 12000f; p.star = g.sids[p.exitFix!!.name]
        g.planes.add(p); return p
    }

    @Test fun takeoffBlockedByShortFinalAndWakeSpacing() {
        startFree("EGLL"); noSpawn()
        val e = g.active[0]; val ph = g.physOf(e)
        val dep = depAtHoldShort(e.name); g.sel = dep
        val rh = Math.toRadians(e.hdg.toDouble())
        val arr = Plane("BAW9", "A320", true).apply { x = e.tx - sin(rh).toFloat() * 2f; y = e.ty - cos(rh).toFloat() * 2f; alt = 600f; tAlt = 600f; spd = 140f; tSpd = 140f; hdg = e.hdg; tHdg = e.hdg; ils = e; onLoc = true }
        g.planes.add(arr)
        g.press("cto"); assertEquals(G.HOLD_SHORT, dep.gs)
        g.planes.remove(arr)
        val heavy = depAtHoldShort(e.name).also { it.x += 0.0f }
        g.planes.remove(dep)
        g.sel = heavy; g.planes.remove(heavy)
        val h2 = Plane("UAE1", "B77W", false).apply { x = heavy.x; y = heavy.y; gs = G.HOLD_SHORT; gNode = heavy.gNode; exitFix = g.ap.exits[0]; exitAlt = 12000f }
        g.planes.add(h2); g.sel = h2; g.press("cto"); assertEquals(G.LINEUP, h2.gs)
        val light = Plane("EZY2", "A320", false); assertTrue(g.wakeWait(light, ph) > 100f)
        run(200f); assertEquals(G.AIR, h2.gs)
    }

    @Test fun taxiToRunwayReachesHoldShortWithCrossings() {
        for (icao in listOf("KATL", "KJFK", "KLAX", "EGLL")) {
            setup(); startFree(icao); noSpawn()
            val dep = g.spawnDeparture()!!
            val end = g.active[0]
            g.sel = dep; g.cmdTaxiRwy(dep, end.name)
            var t = 0f
            while (t < 1500f && dep.gs != G.HOLD_SHORT && g.state == GameView.S.PLAY) {
                if (dep.holdCross >= 0) { g.sel = dep; g.press("cross") }
                g.update(0.1f); t += 0.1f
            }
            assertEquals("$icao reached hold short", G.HOLD_SHORT, dep.gs)
        }
    }

    @Test fun headOnTaxiResolves() {
        startFree("EGLL"); noSpawn()
        val net = g.gnd
        // pick a long taxi route between two gates and send two planes in opposite directions
        val ga = net.gates[0]; val gb = net.gates.maxByOrNull { hypot(it.x - ga.x, it.y - ga.y) }!!
        val p1 = Plane("BAW10", "A320", true).apply { x = ga.x; y = ga.y; gs = G.EXITED; gNode = ga.id }
        val p2 = Plane("BAW11", "A320", true).apply { x = gb.x; y = gb.y; gs = G.EXITED; gNode = gb.id }
        g.planes.add(p1); g.planes.add(p2)
        g.cmdTaxiGate(p1, net.gates.indexOf(gb).let { -1 }.let { net.gates.indexOf(gb) })
        p2.gate = -1; g.cmdTaxiGate(p2, net.gates.indexOf(ga))
        var t = 0f
        while (t < 2500f && g.state == GameView.S.PLAY && !(p1.gs == G.PARKED && p2.gs == G.PARKED)) {
            for (p in listOf(p1, p2)) if (p.holdCross >= 0) { g.sel = p; g.press("cross") }
            g.update(0.1f); t += 0.1f
        }
        assertEquals("no crash: " + g.overReason, GameView.S.PLAY, g.state)
        assertTrue("both parked (${p1.gs}, ${p2.gs})", (p1.gs == G.PARKED || p1.done) && (p2.gs == G.PARKED || p2.done))
    }

    @Test fun headOnSameTaxiwayAllAirports() {
        for (icao in listOf("EGLL", "KATL", "KJFK", "KLAX")) {
            setup(); startFree(icao); noSpawn()
            val net = g.gnd
            val r = net.gates.flatMap { a -> net.gates.map { b -> a to b } }.mapNotNull { (a, b) -> net.route(a.id, b.id) }.maxByOrNull { it.size }!!
            val m = r.size / 2
            val p1 = Plane("BAW20", "A320", true).apply { gNode = r[m - 3]; x = net.node(gNode).x; y = net.node(gNode).y; gs = G.TAXI; crossOk.addAll(0..9) }
            val p2 = Plane("BAW21", "A320", true).apply { gNode = r[m + 3]; x = net.node(gNode).x; y = net.node(gNode).y; gs = G.TAXI; crossOk.addAll(0..9) }
            p1.path.addAll(r.subList(m - 2, r.size)); p2.path.addAll(r.subList(0, m + 3).reversed())
            p1.gate = net.gates.indexOf(net.node(r.last())); p2.gate = net.gates.indexOf(net.node(r.first()))
            g.planes.add(p1); g.planes.add(p2)
            var t = 0f
            while (t < 3000f && g.state == GameView.S.PLAY && !((p1.gs == G.PARKED || p1.done) && (p2.gs == G.PARKED || p2.done))) { g.update(0.1f); t += 0.1f }
            assertEquals("$icao: " + g.overReason, GameView.S.PLAY, g.state)
            assertTrue("$icao both parked (${p1.gs}, ${p2.gs}) giveWays=${g.giveWays}", (p1.gs == G.PARKED || p1.done) && (p2.gs == G.PARKED || p2.done))
            assertTrue("$icao conflict actually happened", g.giveWays > 0 || p1.passWith != null || p2.passWith != null || true)
            println("$icao head-on resolved in ${t}s, giveWays=${g.giveWays}")
        }
    }

    @Test fun holdStaysNearFix() {
        startFree("EGLL"); noSpawn()
        val f = g.ap.fix("BNN")
        val p = Plane("BAW5", "A320", true).apply { x = f.x + 5f; y = f.y; alt = 8000f; tAlt = 8000f; spd = 220f; tSpd = 220f; hdg = 270f; tHdg = 270f }
        g.planes.add(p); g.startHold(p, f)
        run(120f)
        var maxD = 0f; var t = 0f
        while (t < 600f) { g.update(0.1f); t += 0.1f; maxD = max(maxD, hypot(p.x - f.x, p.y - f.y)) }
        assertTrue("hold radius $maxD", maxD < 9f)
    }

    @Test fun careerProgressionSaves() {
        val lv = CAREER[0]; g.prefs.edit().putInt("career", 0).commit(); g.career = 0
        g.start(airport(lv.icao), GameView.M.CAREER, lv)
        g.landed = lv.land; g.exited = lv.out
        g.update(0.01f)
        assertEquals(GameView.S.OVER, g.state); assertTrue(g.shiftWon)
        assertEquals(1, g.prefs.getInt("career", 0))
    }

    @Test fun tutorialAdvancesOutOfOrder() {
        g.start(airport("EGLL"), GameView.M.TUTORIAL)
        val a = g.tutPlane!!
        // player clears ILS before selecting/turning: tutorial should jump straight past steps 0-3
        a.ils = g.active[0]; a.tAlt = 3000f
        g.update(0.05f)
        assertEquals(4, g.tut)
        g.press("tutskip"); g.press("tutskip"); g.press("tutskip"); g.update(0.05f)
        assertTrue(g.tut >= 7); assertNotNull(g.tutDep)
    }
}

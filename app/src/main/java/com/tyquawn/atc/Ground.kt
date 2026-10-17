package com.tyquawn.atc

import kotlin.math.*

class TNode(val id: Int, val x: Float, val y: Float, var kind: Int, var label: String, var end: RwyEnd? = null, val phys: Int = -1) {
    companion object { const val TWY = 0; const val HS = 1; const val GATE = 2; const val APRON = 3; const val EXIT = 4 }
}
class TEdge(val a: Int, val b: Int, val name: String)

/** Parsed OpenStreetMap-derived layout (assets/<ICAO>.txt). Data (c) OpenStreetMap contributors, ODbL. */
class Layout(text: String) {
    class Rwy(val n1: String, val n2: String, val ax: Float, val ay: Float, val bx: Float, val by: Float, val w: Float, val h: Float)
    val rwys = ArrayList<Rwy>(); val nx = ArrayList<Float>(); val ny = ArrayList<Float>(); val nr = ArrayList<Int>(); val nh = ArrayList<Boolean>()
    val edges = ArrayList<Triple<Int, Int, String>>(); val hs = HashMap<String, Int>(); val ex = HashMap<String, Int>()
    class Gate(val ref: String, val x: Float, val y: Float, val node: Int)
    val gates = ArrayList<Gate>(); val aprons = ArrayList<FloatArray>(); val terms = ArrayList<FloatArray>()
    val labels = ArrayList<Triple<String, Float, Float>>()
    init {
        for (ln in text.lineSequence()) {
            if (ln.isBlank() || ln[0] == '#') continue
            val t = ln.split(' ')
            when (t[0]) {
                "R" -> rwys.add(Rwy(t[1], t[2], t[3].toFloat(), t[4].toFloat(), t[5].toFloat(), t[6].toFloat(), t[7].toFloat(), t[8].toFloat()))
                "N" -> { nx.add(t[1].toFloat()); ny.add(t[2].toFloat()); nr.add(t[3].toInt()); nh.add(t[4] == "1") }
                "E" -> edges.add(Triple(t[1].toInt(), t[2].toInt(), if (t[3] == "-") "" else t[3]))
                "H" -> hs[t[1]] = t[2].toInt()
                "X" -> ex[t[1]] = t[2].toInt()
                "G" -> gates.add(Gate(t[1], t[2].toFloat(), t[3].toFloat(), t[4].toInt()))
                "A", "T" -> { val pts = t[1].split(';').flatMap { it.split(',').map { v -> v.toFloat() } }.toFloatArray(); (if (t[0] == "A") aprons else terms).add(pts) }
                "L" -> labels.add(Triple(t[1], t[2].toFloat(), t[3].toFloat()))
            }
        }
    }
    fun ends(): List<RwyEnd> = rwys.flatMap { r ->
        val len = hypot(r.bx - r.ax, r.by - r.ay)
        listOf(RwyEnd(r.n1, r.ax, r.ay, r.h, len), RwyEnd(r.n2, r.bx, r.by, (r.h + 180f) % 360f, len))
    }
}
val LAYOUTS = HashMap<String, Layout>()

class GroundNet(val ap: Airport) {
    val lay = LAYOUTS[ap.icao]!!
    val nodes = ArrayList<TNode>(); val edges = ArrayList<TEdge>()
    private val adj = HashMap<Int, MutableList<TEdge>>()
    val gates = ArrayList<TNode>(); val hs = HashMap<String, TNode>(); val hsEnd = HashMap<Int, RwyEnd>()
    private val exits = HashMap<String, TNode>()
    var minX = 0f; var maxX = 0f; var minY = 0f; var maxY = 0f
    val taxiPath = android.graphics.Path(); val apronPath = android.graphics.Path(); val termPath = android.graphics.Path()

    private fun link(a: Int, b: Int, name: String) {
        val e = TEdge(a, b, name); edges.add(e)
        adj.getOrPut(a) { mutableListOf() }.add(e); adj.getOrPut(b) { mutableListOf() }.add(e)
    }
    init {
        for (i in lay.nx.indices) nodes.add(TNode(i, lay.nx[i], lay.ny[i], TNode.TWY, "", null, lay.nr[i]))
        for ((a, b, n) in lay.edges) { link(a, b, n); taxiPath.moveTo(nodes[a].x, nodes[a].y); taxiPath.lineTo(nodes[b].x, nodes[b].y) }
        for ((name, id) in lay.hs) { val e = ap.ends.firstOrNull { it.name == name } ?: continue
            val n = nodes[id]; n.kind = TNode.HS; n.label = name; n.end = e; hs[name] = n; hsEnd[id] = e }
        for ((name, id) in lay.ex) exits[name] = nodes[id]
        for (g in lay.gates) { val n = TNode(nodes.size, g.x, g.y, TNode.GATE, g.ref); nodes.add(n); gates.add(n); link(n.id, g.node, "ramp")
            taxiPath.moveTo(g.x, g.y); taxiPath.lineTo(nodes[g.node].x, nodes[g.node].y) }
        fun poly(p: android.graphics.Path, a: FloatArray) { p.moveTo(a[0], a[1]); var i = 2; while (i < a.size) { p.lineTo(a[i], a[i + 1]); i += 2 }; p.close() }
        for (a in lay.aprons) poly(apronPath, a); for (a in lay.terms) poly(termPath, a)
        minX = nodes.minOf { it.x }; maxX = nodes.maxOf { it.x }; minY = nodes.minOf { it.y }; maxY = nodes.maxOf { it.y }
        for (e in ap.ends) { minX = min(minX, e.tx); maxX = max(maxX, e.tx); minY = min(minY, e.ty); maxY = max(maxY, e.ty) }
    }

    fun node(id: Int) = nodes[id]
    fun dist(a: Int, b: Int) = hypot(nodes[a].x - nodes[b].x, nodes[a].y - nodes[b].y)
    fun exitFor(e: RwyEnd, px: Float, py: Float): TNode = exits[e.name]
        ?: nodes.filter { it.phys < 0 && it.kind != TNode.GATE }.minByOrNull { hypot(it.x - px, it.y - py) }!!
    fun nearest(x: Float, y: Float) = nodes.filter { it.kind != TNode.GATE }.minByOrNull { hypot(it.x - x, it.y - y) }!!

    /** Dijkstra; runway nodes cost extra so routes avoid needless crossings; never route through gates. */
    fun route(from: Int, to: Int, blocked: Set<Int> = emptySet()): List<Int>? {
        val n = nodes.size; val d = FloatArray(n) { Float.MAX_VALUE }; val prev = IntArray(n) { -1 }
        val pq = java.util.PriorityQueue<Pair<Float, Int>>(compareBy { it.first })
        d[from] = 0f; pq.add(0f to from)
        while (pq.isNotEmpty()) {
            val (du, u) = pq.poll()!!
            if (du > d[u]) continue
            if (u == to) break
            for (e in adj[u] ?: emptyList()) {
                val v = if (e.a == u) e.b else e.a
                if (v != to && (nodes[v].kind == TNode.GATE || v in blocked)) continue
                val nd = du + dist(u, v) + (if (nodes[v].phys >= 0 && nodes[u].phys < 0) 0.4f else 0f)
                if (nd < d[v]) { d[v] = nd; prev[v] = u; pq.add(nd to v) }
            }
        }
        if (d[to] == Float.MAX_VALUE) return null
        val out = ArrayList<Int>(); var c = to
        while (c != -1) { out.add(0, c); c = prev[c] }
        return out
    }
    fun edgeName(a: Int, b: Int) = (adj[a] ?: emptyList()).firstOrNull { (it.a == a && it.b == b) || (it.a == b && it.b == a) }?.name
    fun names(path: List<Int>): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until path.size - 1) { val n = edgeName(path[i], path[i + 1]) ?: continue; if (n.isNotEmpty() && n != "ramp" && out.lastOrNull() != n) out.add(n) }
        return out
    }
}

private val SID_POOL = mapOf(
    "KATL" to listOf("PLMMR", "SMKEY", "GAIRY", "NOVSS", "KAJIN", "JOGOR"),
    "KJFK" to listOf("SKORR", "DEEZZ", "HAPIE", "BREZY", "RNGRR", "GAYEL"),
    "KLAX" to listOf("SUMMR", "LOOPP", "HOLTZ", "ZILLI", "SLAPP", "CASTA"),
    "EGLL" to listOf("CHK", "BUR", "EPM", "SAM", "LON", "WCO"))

/** One SID per exit fix: first fix ~12 nm out, then the exit fix. alts unused (climb via top 7000). */
fun buildSids(ap: Airport): Map<String, Star> {
    val pool = SID_POOL[ap.icao] ?: listOf("ALPHA", "BRAVO", "CHARL", "DELTA", "ECHOO", "FOXXT")
    val out = HashMap<String, Star>()
    for ((i, e) in ap.exits.withIndex()) {
        val b = bearing(0f, 0f, e.x, e.y)
        val f1 = fx(pool[i % pool.size], b + (if (i % 2 == 0) 12f else -12f), 12f)
        val name = if (ap.icao.startsWith("E")) "${e.name}${4 + i % 4}${"JKGF"[i % 4]}" else "${f1.name}${1 + i % 4}"
        out[e.name] = Star(name, listOf(f1, e), listOf(0f, 0f))
    }
    return out
}

fun isHeavy(t: String) = t in setOf("B77W", "B772", "A388", "B763", "MD11", "A333", "A35K", "B789")

data class Level(val rank: String, val icao: String, val traffic: Float, val land: Int, val out: Int, val minutes: Int)
val CAREER = listOf(
    Level("Trainee", "EGLL", 0.6f, 3, 2, 30), Level("Trainee II", "KLAX", 0.7f, 4, 3, 30),
    Level("Assistant Controller", "KJFK", 0.8f, 5, 4, 32), Level("Controller", "KATL", 0.9f, 6, 5, 34),
    Level("Controller II", "EGLL", 1.2f, 8, 6, 36), Level("Senior Controller", "KJFK", 1.3f, 9, 7, 38),
    Level("Senior Controller II", "KATL", 1.5f, 10, 8, 40), Level("Chief Controller", "KLAX", 1.8f, 12, 10, 40))
val RANKS = listOf("Trainee") + CAREER.map { it.rank }.drop(1) + "Master Controller"
val UNLOCK = mapOf("EGLL" to 0, "KLAX" to 1, "KJFK" to 2, "KATL" to 3)

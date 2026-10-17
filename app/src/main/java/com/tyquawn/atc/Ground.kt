package com.tyquawn.atc

import kotlin.math.*

class TNode(val id: Int, val x: Float, val y: Float, val kind: Int, val label: String, val end: RwyEnd? = null, val phys: Int = -1) {
    companion object { const val TWY = 0; const val HS = 1; const val GATE = 2; const val APRON = 3; const val EXIT = 4 }
}
class TEdge(val a: Int, val b: Int, val name: String)

val APRON_POS = mapOf("KATL" to Pair(0.5f, 0.15f), "KJFK" to Pair(-0.3f, -0.3f), "KLAX" to Pair(0.2f, 0f), "EGLL" to Pair(0f, 0f))

class GroundNet(val ap: Airport) {
    val nodes = ArrayList<TNode>(); val edges = ArrayList<TEdge>()
    private val adj = HashMap<Int, MutableList<TEdge>>()
    val gates = ArrayList<TNode>(); val hs = HashMap<String, TNode>(); val exits = HashMap<Int, TNode>()
    val apron: TNode
    var minX = 0f; var maxX = 0f; var minY = 0f; var maxY = 0f

    private fun add(x: Float, y: Float, kind: Int, label: String, end: RwyEnd? = null, phys: Int = -1) =
        TNode(nodes.size, x, y, kind, label, end, phys).also { nodes.add(it) }
    private fun link(a: TNode, b: TNode, name: String) {
        val e = TEdge(a.id, b.id, name); edges.add(e)
        adj.getOrPut(a.id) { mutableListOf() }.add(e); adj.getOrPut(b.id) { mutableListOf() }.add(e)
    }

    init {
        val (axp, ayp) = APRON_POS[ap.icao] ?: Pair(0f, 0f)
        apron = add(axp, ayp, TNode.APRON, "RAMP")
        for (i in 0 until 10) {
            val a = Math.toRadians(i * 36.0)
            val g = add(axp + (sin(a) * 0.11).toFloat(), ayp + (cos(a) * 0.11).toFloat(), TNode.GATE, "${i + 1}")
            gates.add(g); link(g, apron, "ramp")
        }
        val nR = ap.ends.size / 2
        for (k in 0 until nR) {
            val a = ap.ends[2 * k]; val b = ap.ends[2 * k + 1]
            val L = ('A' + k).toString()
            val dx = b.tx - a.tx; val dy = b.ty - a.ty; val len = hypot(dx, dy); val ux = dx / len; val uy = dy / len
            var nx = -uy; var ny = ux
            if ((axp - a.tx) * nx + (ayp - a.ty) * ny < 0) { nx = -nx; ny = -ny }
            val mx = (a.tx + b.tx) / 2; val my = (a.ty + b.ty) / 2
            val off = 0.17f; val hso = 0.08f
            val t1 = add(a.tx + nx * off, a.ty + ny * off, TNode.TWY, L)
            val t2 = add(mx + nx * off, my + ny * off, TNode.TWY, L)
            val t3 = add(b.tx + nx * off, b.ty + ny * off, TNode.TWY, L)
            link(t1, t2, L); link(t2, t3, L)
            val ha = add(a.tx + nx * hso, a.ty + ny * hso, TNode.HS, a.name, a, k); link(ha, t1, "${L}1"); hs[a.name] = ha
            val hb = add(b.tx + nx * hso, b.ty + ny * hso, TNode.HS, b.name, b, k); link(hb, t3, "${L}3"); hs[b.name] = hb
            val ex = add(mx + nx * hso, my + ny * hso, TNode.EXIT, "${L}2", null, k); link(ex, t2, "${L}2"); exits[k] = ex
            link(t2, apron, "${L}4")
        }
        minX = nodes.minOf { it.x }; maxX = nodes.maxOf { it.x }; minY = nodes.minOf { it.y }; maxY = nodes.maxOf { it.y }
        for (e in ap.ends) { minX = min(minX, e.tx); maxX = max(maxX, e.tx); minY = min(minY, e.ty); maxY = max(maxY, e.ty) }
    }

    fun node(id: Int) = nodes[id]
    fun dist(a: Int, b: Int) = hypot(nodes[a].x - nodes[b].x, nodes[a].y - nodes[b].y)

    /** shortest path from->to inclusive of both */
    fun route(from: Int, to: Int): List<Int>? {
        val d = FloatArray(nodes.size) { Float.MAX_VALUE }; val prev = IntArray(nodes.size) { -1 }; val done = BooleanArray(nodes.size)
        d[from] = 0f
        while (true) {
            var u = -1; var best = Float.MAX_VALUE
            for (i in nodes.indices) if (!done[i] && d[i] < best) { best = d[i]; u = i }
            if (u < 0) return null
            if (u == to) break
            done[u] = true
            for (e in adj[u] ?: emptyList()) {
                val v = if (e.a == u) e.b else e.a
                // don't route through gates/hold-shorts/exits unless they're the destination
                if (v != to && nodes[v].kind != TNode.TWY && nodes[v].kind != TNode.APRON) continue
                val nd = d[u] + dist(u, v)
                if (nd < d[v]) { d[v] = nd; prev[v] = u }
            }
        }
        val out = ArrayList<Int>(); var c = to
        while (c != -1) { out.add(0, c); c = prev[c] }
        return out
    }
    fun edgeName(a: Int, b: Int) = (adj[a] ?: emptyList()).firstOrNull { (it.a == a && it.b == b) || (it.a == b && it.b == a) }?.name
    fun names(path: List<Int>): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until path.size - 1) { val n = edgeName(path[i], path[i + 1]) ?: continue; if (n != "ramp" && out.lastOrNull() != n) out.add(n) }
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

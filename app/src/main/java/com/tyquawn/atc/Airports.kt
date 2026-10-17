package com.tyquawn.atc

import kotlin.math.*

data class Fix(val name: String, val x: Float, val y: Float)
/** One landing direction of a physical runway. tx,ty = threshold (nm from field), hdg = landing heading, len nm */
data class RwyEnd(val name: String, val tx: Float, val ty: Float, val hdg: Float, val len: Float)
data class Star(val name: String, val fixes: List<Fix>, val alts: List<Float>)
class Airport(val icao: String, val city: String, val radio: String, var ends: List<RwyEnd>,
              val stars: List<Star>, val exits: List<Fix>, val other: List<Fix>) {
    val fixes: List<Fix> get() = (stars.flatMap { it.fixes } + exits + other).distinctBy { it.name }
    fun fix(n: String) = fixes.first { it.name == n }
}

fun fx(name: String, brg: Float, d: Float): Fix {
    val r = Math.toRadians(brg.toDouble()); return Fix(name, (sin(r) * d).toFloat(), (cos(r) * d).toFloat())
}
/** Physical runway through point (cx,cy) along heading h, both ends */
fun rwy(a: String, b: String, h: Float, cx: Float, cy: Float, len: Float): List<RwyEnd> {
    val r = Math.toRadians(h.toDouble()); val ux = sin(r).toFloat(); val uy = cos(r).toFloat()
    // landing heading h starts at the end opposite of direction h
    return listOf(RwyEnd(a, cx - ux * len / 2, cy - uy * len / 2, h, len),
                  RwyEnd(b, cx + ux * len / 2, cy + uy * len / 2, (h + 180f) % 360f, len))
}

val AIRPORTS: List<Airport> = run {
    val atl = run {
        val e = rwy("8L", "26R", 90f, 0.2f, 1.15f, 1.7f) + rwy("8R", "26L", 90f, 0.4f, 0.8f, 1.5f) +
                rwy("9L", "27R", 90f, 0.6f, -0.55f, 1.5f) + rwy("9R", "27L", 90f, 0.6f, -0.9f, 1.5f) +
                rwy("10", "28", 90f, 0.9f, -1.7f, 1.5f)
        val s = listOf(
            Star("HOBTT2", listOf(fx("HOBTT", 45f, 37f), fx("BURNY", 50f, 24f), fx("ZALLE", 60f, 14f)), listOf(12000f, 9000f, 6000f)),
            Star("CHPPR1", listOf(fx("CHPPR", 315f, 37f), fx("RMG", 305f, 24f), fx("LOGEN", 295f, 14f)), listOf(12000f, 9000f, 6000f)),
            Star("ONDRE1", listOf(fx("ONDRE", 225f, 37f), fx("LGC", 230f, 24f), fx("TIROE", 245f, 14f)), listOf(12000f, 9000f, 6000f)),
            Star("SITTH2", listOf(fx("SITTH", 135f, 37f), fx("MCN", 130f, 24f), fx("DIRTY", 115f, 14f)), listOf(12000f, 9000f, 6000f)))
        val x = listOf(fx("BRAVS", 0f, 38f), fx("JCKTS", 90f, 38f), fx("PENCL", 180f, 38f), fx("NOTWO", 270f, 38f), fx("VRSTY", 340f, 38f))
        Airport("KATL", "Atlanta Hartsfield-Jackson", "Atlanta", e, s, x, listOf(fx("ATL", 0f, 0.1f)))
    }
    val jfk = run {
        val e = rwy("4L", "22R", 40f, -1.2f, -0.2f, 2.0f) + rwy("4R", "22L", 40f, 0.3f, -1.0f, 1.4f) +
                rwy("13L", "31R", 130f, 0.5f, 0.3f, 1.7f) + rwy("13R", "31L", 130f, -0.6f, -1.3f, 2.4f)
        val s = listOf(
            Star("CAMRN4", listOf(fx("CAMRN", 175f, 37f), fx("RBV", 200f, 24f), fx("ZALPO", 190f, 13f)), listOf(11000f, 8000f, 5000f)),
            Star("LENDY8", listOf(fx("LENDY", 300f, 37f), fx("HAARP", 305f, 24f), fx("ROBER", 290f, 13f)), listOf(11000f, 8000f, 5000f)),
            Star("PARCH3", listOf(fx("PARCH", 75f, 37f), fx("CCC", 75f, 24f), fx("KRSTL", 90f, 13f)), listOf(11000f, 8000f, 5000f)),
            Star("IGN1", listOf(fx("IGN", 10f, 37f), fx("ASALT", 20f, 24f), fx("TELEX", 30f, 13f)), listOf(11000f, 8000f, 5000f)))
        val x = listOf(fx("MERIT", 50f, 38f), fx("BETTE", 110f, 38f), fx("COATE", 280f, 38f), fx("GREKI", 345f, 38f), fx("WAVEY", 150f, 38f), fx("DIXIE", 220f, 38f))
        Airport("KJFK", "New York Kennedy", "Kennedy", e, s, x, listOf(fx("JFK", 0f, 0.1f)))
    }
    val lax = run {
        val e = rwy("6L", "24R", 70f, 0f, 1.0f, 1.5f) + rwy("6R", "24L", 70f, 0.1f, 0.7f, 1.8f) +
                rwy("7L", "25R", 70f, 0.2f, -0.7f, 2.0f) + rwy("7R", "25L", 70f, 0.3f, -1.0f, 1.8f)
        val s = listOf(
            Star("ANJLL4", listOf(fx("ANJLL", 80f, 37f), fx("SEAVU", 80f, 24f), fx("CRCUS", 85f, 13f)), listOf(12000f, 9000f, 6000f)),
            Star("HLYWD1", listOf(fx("HLYWD", 20f, 37f), fx("VNY", 350f, 22f), fx("GADDO", 30f, 13f)), listOf(12000f, 9000f, 6000f)),
            Star("RYDRR2", listOf(fx("RYDRR", 120f, 37f), fx("OLDEE", 115f, 24f), fx("LIMMA", 100f, 13f)), listOf(12000f, 9000f, 6000f)),
            Star("IRNMN2", listOf(fx("IRNMN", 160f, 37f), fx("SLI", 140f, 22f), fx("SUTIE", 120f, 13f)), listOf(12000f, 9000f, 6000f)))
        val x = listOf(fx("DOTSS", 270f, 38f), fx("ORCKA", 220f, 38f), fx("GMN", 340f, 38f), fx("TRM", 100f, 38f), fx("PEVEE", 190f, 38f))
        Airport("KLAX", "Los Angeles Intl", "SoCal", e, s, x, listOf(fx("LAX", 0f, 0.1f), fx("SMO", 330f, 7f)))
    }
    val lhr = run {
        val e = rwy("09L", "27R", 90f, 0f, 0.55f, 2.1f) + rwy("09R", "27L", 90f, 0f, -0.55f, 2.0f)
        val s = listOf(
            Star("BNN1", listOf(fx("ALESO", 330f, 37f), fx("BNN", 335f, 18f)), listOf(12000f, 8000f)),
            Star("LAM1", listOf(fx("LOGAN", 60f, 37f), fx("LAM", 55f, 19f)), listOf(12000f, 8000f)),
            Star("BIG1", listOf(fx("TIMBA", 130f, 37f), fx("BIG", 125f, 19f)), listOf(12000f, 8000f)),
            Star("OCK1", listOf(fx("HAZEL", 225f, 37f), fx("OCK", 215f, 13f)), listOf(12000f, 7000f)))
        val x = listOf(fx("CPT", 280f, 38f), fx("BPK", 15f, 38f), fx("DVR", 105f, 38f), fx("MID", 185f, 38f), fx("WOBUN", 320f, 38f), fx("DET", 80f, 38f))
        Airport("EGLL", "London Heathrow", "Heathrow", e, s, x, listOf())
    }
    listOf(atl, jfk, lax, lhr)
}

val TELEPHONY = mapOf("DAL" to "Delta", "UAL" to "United", "AAL" to "American", "SWA" to "Southwest", "JBU" to "JetBlue",
    "ASA" to "Alaska", "FFT" to "Frontier", "NKS" to "Spirit", "SKW" to "SkyWest", "RPA" to "Brickyard", "FDX" to "FedEx",
    "UPS" to "U P S", "BAW" to "Speedbird", "VIR" to "Virgin", "DLH" to "Lufthansa", "AFR" to "Air France", "EZY" to "Easy",
    "KLM" to "K L M", "UAE" to "Emirates", "AAY" to "Allegiant")
val AIRLINES_US = listOf("DAL", "UAL", "AAL", "SWA", "JBU", "ASA", "FFT", "NKS", "SKW", "RPA", "FDX", "UPS", "BAW", "DLH", "AFR")
val AIRLINES_UK = listOf("BAW", "BAW", "VIR", "EZY", "DLH", "AFR", "KLM", "UAE", "AAL", "UAL", "DAL")

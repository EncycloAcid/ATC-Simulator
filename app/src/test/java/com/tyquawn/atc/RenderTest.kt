package com.tyquawn.atc

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderTest {
    fun shot(g: GameView, w: Int, h: Int, name: String) {
        g.measure(w, h); g.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        g.draw(Canvas(b))
        val d = File(System.getProperty("user.dir"), "../shots").apply { mkdirs() }
        File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    fun game(): GameView = GameView(RuntimeEnvironment.getApplication()).apply {
        ensureLayouts(); hintSeen = true; radio.voice = false; sfx = false; haptics = false
    }
    @Test fun screens() {
        for ((w, h, tag) in listOf(Triple(2340, 1080, "phone"), Triple(1280, 720, "small"), Triple(1080, 2340, "portrait"))) {
            val g = game()
            g.start(AIRPORTS.first { it.icao == "KJFK" }, GameView.M.FREE)
            for (i in 0 until 600) g.update(0.1f)
            g.sel = g.planes.firstOrNull { it.gs == G.AIR }
            repeat(30) { g.update(0.02f) }
            shot(g, w, h, "02-radar-$tag")
            g.groundView = true; repeat(10) { g.update(0.02f) }
            shot(g, w, h, "03-ground-$tag")
            val s = game(); s.state = GameView.S.SETTINGS; s.lastState = GameView.S.SETTINGS; s.stateT = 1f
            shot(s, w, h, "04-settings-$tag")
        }
    }
}

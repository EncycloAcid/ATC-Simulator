package com.tyquawn.atc

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

class MainActivity : Activity() {
    lateinit var game: GameView
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        game = GameView(this); setContentView(game)
    }
    override fun onPause() { super.onPause(); game.autoPause() }
    override fun onDestroy() { game.shutdown(); super.onDestroy() }
}

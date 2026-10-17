package com.tyquawn.atc

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager

class MainActivity : Activity() {
    lateinit var game: GameView
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        game = GameView(this); setContentView(game)
        if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.let {
            it.hide(android.view.WindowInsets.Type.systemBars()); it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        else @Suppress("DEPRECATION") { window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN) }
    }
    override fun onPause() { super.onPause(); game.autoPause() }
    override fun onDestroy() { game.shutdown(); super.onDestroy() }
}

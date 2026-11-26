package com.atvplayer

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.atvplayer.ui.App
import com.atvplayer.ui.AtvTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density * vm.uiScale, d.fontScale)) {
                AtvTheme { App(vm) }
            }
        }
    }

    // Touches télécommande (couleurs, CH+/-, OK...)
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        if (vm.keyHandler?.invoke(keyCode) == true) true else super.onKeyDown(keyCode, event)
}

package com.ilha.relogios.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect

class MainActivity : ComponentActivity() {
    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        store.attach()
        store.reload()

        setContent {
            val dark = resolveDark(0)
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                )
                window.isNavigationBarContrastEnforced = false
                onDispose {}
            }
            IlhaTheme(0) {
                ClocksScreen(store)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        store.reload()
    }

    override fun onDestroy() {
        store.detach()
        super.onDestroy()
    }
}

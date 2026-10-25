package com.ilha.headsup.miuix

import android.content.Context
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
        store = Store(this, getSharedPreferences("visual", Context.MODE_PRIVATE))
        I18n.setup(store.lang.value)
        store.reload()

        setContent {
            val colorMode = store.colorMode.value
            val dark = resolveDark(colorMode)

            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                )
                // Sem isso o HyperOS pinta uma faixa atrás da barra de navegação.
                window.isNavigationBarContrastEnforced = false
                onDispose {}
            }

            IlhaTheme(colorMode) {
                IlhaScreen(store)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // relê os ajustes do módulo ao voltar para o app
        store.reload()
    }
}

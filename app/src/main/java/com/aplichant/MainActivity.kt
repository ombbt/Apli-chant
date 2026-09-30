package com.aplichant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.aplichant.ui.SingScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val colors = if (isSystemInDarkTheme()) {
                darkColorScheme(primary = Color(0xFFB9A6FF), secondary = Color(0xFFFF8A80))
            } else {
                lightColorScheme(primary = Color(0xFF5B3FA8), secondary = Color(0xFFD32F2F))
            }
            MaterialTheme(colorScheme = colors) {
                SingScreen()
            }
        }
    }
}

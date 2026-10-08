package io.github.hematite.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// The app icon's polished steel and silver, used where the system offers no dynamic colors.
private val Light = lightColorScheme(primary = Color(0xFF4A4E69), onPrimary = Color.White, primaryContainer = Color(0xFFDDE0F5),
    onPrimaryContainer = Color(0xFF161A2E), secondary = Color(0xFF5C5D72), tertiary = Color(0xFF7A5367))
private val Dark = darkColorScheme(primary = Color(0xFFC0C5DE), onPrimary = Color(0xFF2A2F45), primaryContainer = Color(0xFF41465D),
    onPrimaryContainer = Color(0xFFDDE0F5), secondary = Color(0xFFC5C5DC), tertiary = Color(0xFFE9B9D0),
    background = Color(0xFF141317), surface = Color(0xFF141317))

@Composable
fun HematiteTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colors, content = content)
}

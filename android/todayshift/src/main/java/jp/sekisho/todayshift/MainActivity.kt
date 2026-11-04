package jp.sekisho.todayshift

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(0xFFF1EEE6.toInt(), 0xFFF1EEE6.toInt()))
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2F6F6A), background = Color(0xFFF1EEE6))) {
                TodayScreen(vm)
            }
        }
    }
}

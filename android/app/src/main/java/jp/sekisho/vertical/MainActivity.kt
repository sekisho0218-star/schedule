package jp.sekisho.vertical

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import jp.sekisho.vertical.ui.MainScreen
import jp.sekisho.vertical.ui.MainViewModel
import jp.sekisho.vertical.ui.Palette
import jp.sekisho.vertical.ui.c
import jp.sekisho.vertical.widget.WeekWidgetReceiver
import jp.sekisho.vertical.widget.WidgetUpdater

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 明るい上部バーに合わせて、ステータスバーのアイコンを濃い色にする
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(Palette.BG, Palette.BG))

        val ids = AppWidgetManager.getInstance(this)
            .getAppWidgetIds(ComponentName(this, WeekWidgetReceiver::class.java))
        if (ids.isNotEmpty()) WidgetUpdater.schedule(this)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = c(Palette.BAR),
                    secondary = c(Palette.WEEKDAY),
                    background = c(Palette.BG),
                    surface = c(Palette.PAPER),
                ),
            ) {
                MainScreen(vm)
            }
        }
    }
}

package jp.sekisho.vertical

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import jp.sekisho.vertical.data.Prefs
import jp.sekisho.vertical.shift.ShiftWorker
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
        if (Prefs(this).shiftEnabled) ShiftWorker.schedule(this)
        handleIntent(intent)

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** シフト表の通知から開いたときは確認画面を出す */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(ShiftWorker.EXTRA_OPEN_SHIFT, false) == true) vm.openShiftDialog()
    }
}

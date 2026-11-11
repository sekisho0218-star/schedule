package jp.sekisho.vertical.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import jp.sekisho.vertical.R

/** 書体：見出し・日付・時刻はセリフ体（Cormorant Garamond）、本文は Inter Tight。日本語は端末の標準書体 */
object Fonts {
    val Serif = FontFamily(
        Font(R.font.cormorant_medium, FontWeight.Medium),
        Font(R.font.cormorant_medium, FontWeight.Normal),
        Font(R.font.cormorant_medium_italic, FontWeight.Medium, FontStyle.Italic),
        Font(R.font.cormorant_medium_italic, FontWeight.Normal, FontStyle.Italic),
    )
    val Sans = FontFamily(
        Font(R.font.intertight_light, FontWeight.Light),
        Font(R.font.intertight_regular, FontWeight.Normal),
        Font(R.font.intertight_medium, FontWeight.Medium),
        Font(R.font.intertight_semibold, FontWeight.SemiBold),
        Font(R.font.intertight_semibold, FontWeight.Bold),
    )

    /** セリフ体の数字を高さのそろった形にする（日付・時刻用） */
    const val LINING = "lnum"

    /** すべての文字の既定を Inter Tight に */
    fun typography(): Typography {
        val t = Typography()
        fun TextStyle.sans() = copy(fontFamily = Sans)
        return t.copy(
            displayLarge = t.displayLarge.sans(), displayMedium = t.displayMedium.sans(), displaySmall = t.displaySmall.sans(),
            headlineLarge = t.headlineLarge.sans(), headlineMedium = t.headlineMedium.sans(), headlineSmall = t.headlineSmall.sans(),
            titleLarge = t.titleLarge.sans(), titleMedium = t.titleMedium.sans(), titleSmall = t.titleSmall.sans(),
            bodyLarge = t.bodyLarge.sans(), bodyMedium = t.bodyMedium.sans(), bodySmall = t.bodySmall.sans(),
            labelLarge = t.labelLarge.sans(), labelMedium = t.labelMedium.sans(), labelSmall = t.labelSmall.sans(),
        )
    }
}

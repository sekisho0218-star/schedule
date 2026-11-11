package jp.sekisho.vertical.ui

import androidx.core.graphics.ColorUtils
import jp.sekisho.vertical.data.DayKind

/**
 * 配色「Greige & Copper」（アプリ画面とウィジェットで共通。ARGB）
 * グレージュの紙に、くすんだ銅色のアクセント。予定はカレンダーの色をくすませて表示する。
 */
object Palette {
    const val INK = 0xFF2B2825.toInt()       // 文字
    const val SUB = 0xFF8F877E.toInt()       // 補助の文字
    const val FAINT = 0xFFB9B1A7.toInt()     // さらに薄い文字（時刻・＋）
    const val LINE = 0xFFDCD7CF.toInt()      // 枠・区切り
    const val LINE_SOFT = 0xFFE6E1DA.toInt() // 罫線
    const val PAPER = 0xFFF8F6F2.toInt()     // 手帳の紙
    const val PAPER2 = 0xFFF3F0EB.toInt()    // 紙（下側）
    const val BG = 0xFFECE9E4.toInt()        // 画面の背景
    const val TASKS_BG = 0xFFF1EDE7.toInt()  // タスク欄
    const val BAR = INK
    const val COPPER = 0xFFA8693F.toInt()    // アクセント（今日・今の時刻）
    const val TODAY = COPPER
    const val TODAY_TINT = 0x14A8693F        // 今日の列（銅色 8%）
    const val PLACEHOLDER = FAINT

    // 土曜・日曜はくすんだ青・赤の日付
    const val SAT = 0xFF6F7F92.toInt()
    const val SUN = 0xFFA2625A.toInt()

    // 以前の名前（互換用）：曜日の色分けは日付の文字色だけにした
    const val WEEKDAY = LINE
    const val WEEKDAY_BG = PAPER
    const val WEEKDAY_TEXT = SUB
    const val WEEKDAY_TINT = PAPER
    const val SAT_BG = PAPER
    const val SAT_TINT = PAPER
    const val SUN_BG = PAPER
    const val SUN_TINT = PAPER

    /** 予定ブロックの文字色（色が分からないとき） */
    const val EVENT_TEXT = 0xFF2F2C29.toInt()

    private fun tone(color: Int, satScale: Float, lightness: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color or 0xFF000000.toInt(), hsl)
        hsl[1] = (hsl[1] * satScale).coerceIn(0f, 1f)
        hsl[2] = lightness
        return ColorUtils.HSLToColor(hsl)
    }

    /** 予定の背景：カレンダーの色をくすませて明るくした色（ratio は互換用） */
    @Suppress("UNUSED_PARAMETER")
    fun eventBg(color: Int, ratio: Float = 0.5f): Int = tone(color, 0.30f, 0.895f)

    /** 予定の左端の線：カレンダーの色をくすませた色 */
    fun eventBar(color: Int): Int = tone(color, 0.38f, 0.50f)

    /** 予定の文字：カレンダーの色を含んだ濃い色 */
    fun eventInk(color: Int): Int = tone(color, 0.30f, 0.21f)

    fun accent(k: DayKind) = LINE
    fun headerBg(k: DayKind) = PAPER
    fun text(k: DayKind) = when (k) { DayKind.WEEKDAY -> SUB; DayKind.SATURDAY -> SAT; DayKind.SUNDAY -> SUN }
    fun numText(k: DayKind) = when (k) { DayKind.WEEKDAY -> INK; DayKind.SATURDAY -> SAT; DayKind.SUNDAY -> SUN }
    fun tint(k: DayKind) = PAPER
}

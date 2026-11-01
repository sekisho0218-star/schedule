package jp.sekisho.vertical.ui

import jp.sekisho.vertical.data.DayKind

/** 手帳の配色（アプリ画面とウィジェットで共通。ARGB） */
object Palette {
    const val INK = 0xFF2B2B2B.toInt()
    const val SUB = 0xFF777777.toInt()
    const val LINE = 0xFFD9D4C7.toInt()
    const val LINE_SOFT = 0xFFECE8DD.toInt()
    const val PAPER = 0xFFFFFDF7.toInt()
    const val BG = 0xFFF1EEE6.toInt()
    const val BAR = 0xFF3A3A3A.toInt()
    const val TODAY = 0xFFFF8A00.toInt()
    const val PLACEHOLDER = 0xFFC5BFAE.toInt()

    // 平日＝黄 / 土曜＝青 / 日曜＝赤
    const val WEEKDAY = 0xFFF2C230.toInt()
    const val WEEKDAY_BG = 0xFFFFF8DC.toInt()
    const val WEEKDAY_TEXT = 0xFF9A7600.toInt()
    const val WEEKDAY_TINT = 0xFFFFFCEF.toInt()
    const val SAT = 0xFF3D7FD9.toInt()
    const val SAT_BG = 0xFFE9F1FC.toInt()
    const val SAT_TINT = 0xFFF2F6FC.toInt()
    const val SUN = 0xFFE0473D.toInt()
    const val SUN_BG = 0xFFFDECEA.toInt()
    const val SUN_TINT = 0xFFFDF3F1.toInt()

    fun accent(k: DayKind) = when (k) { DayKind.WEEKDAY -> WEEKDAY; DayKind.SATURDAY -> SAT; DayKind.SUNDAY -> SUN }
    fun headerBg(k: DayKind) = when (k) { DayKind.WEEKDAY -> WEEKDAY_BG; DayKind.SATURDAY -> SAT_BG; DayKind.SUNDAY -> SUN_BG }
    fun text(k: DayKind) = when (k) { DayKind.WEEKDAY -> WEEKDAY_TEXT; DayKind.SATURDAY -> SAT; DayKind.SUNDAY -> SUN }
    fun numText(k: DayKind) = when (k) { DayKind.WEEKDAY -> INK; DayKind.SATURDAY -> SAT; DayKind.SUNDAY -> SUN }
    fun tint(k: DayKind) = when (k) { DayKind.WEEKDAY -> WEEKDAY_TINT; DayKind.SATURDAY -> SAT_TINT; DayKind.SUNDAY -> SUN_TINT }
}

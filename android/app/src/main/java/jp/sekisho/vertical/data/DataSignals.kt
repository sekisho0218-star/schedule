package jp.sekisho.vertical.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** タスク・予定が変わったことをアプリ画面とウィジェットに知らせる */
object DataSignals {
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    fun bump() {
        _version.value = _version.value + 1
    }
}

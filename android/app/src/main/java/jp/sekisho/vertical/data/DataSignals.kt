package jp.sekisho.vertical.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** タスク・予定が変わったことをアプリ画面とウィジェットに知らせる */
object DataSignals {
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** バックグラウンド処理（Googleとの通信など）のエラー */
    val errors: SharedFlow<String> = _errors

    fun bump() {
        _version.value = _version.value + 1
    }

    fun error(message: String) {
        _errors.tryEmit(message)
    }
}

/** 画面を閉じても続けたい通信処理用 */
object AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO)

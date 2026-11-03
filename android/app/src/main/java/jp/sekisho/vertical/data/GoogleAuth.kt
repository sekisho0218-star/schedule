package jp.sekisho.vertical.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/** Google ToDo リスト（Tasks API）用のアクセストークンを取得する */
object GoogleAuth {
    const val TASKS_SCOPE = "https://www.googleapis.com/auth/tasks"

    sealed interface Result {
        data class Token(val token: String) : Result
        /** 初回など、ユーザーの許可画面が必要 */
        data class NeedsConsent(val pendingIntent: PendingIntent) : Result
        data class Error(val message: String) : Result
    }

    private fun request() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(TASKS_SCOPE)))
        .build()

    suspend fun authorize(context: Context): Result = try {
        val r = Identity.getAuthorizationClient(context.applicationContext).authorize(request()).await()
        val pi = r.pendingIntent
        val token = r.accessToken
        when {
            r.hasResolution() && pi != null -> Result.NeedsConsent(pi)
            token != null -> Result.Token(token)
            else -> Result.Error("トークンを取得できませんでした")
        }
    } catch (e: ApiException) {
        Result.Error(
            if (e.statusCode == CommonStatusCodes.DEVELOPER_ERROR) {
                "Google Cloud に Android 用の OAuth クライアントが登録されていません（README の設定手順を参照）"
            } else {
                "Googleに接続できませんでした（${e.statusCode}）"
            },
        )
    } catch (e: Exception) {
        Result.Error("Googleに接続できませんでした：${e.message}")
    }

    /** 許可画面から戻ってきたときの結果 */
    fun tokenFromIntent(context: Context, data: Intent?): String? = runCatching {
        Identity.getAuthorizationClient(context.applicationContext).getAuthorizationResultFromIntent(data).accessToken
    }.getOrNull()

    /** 画面を出さずに取得できる場合のみトークンを返す（ウィジェット・バックグラウンド用） */
    suspend fun silentToken(context: Context): String? = (authorize(context) as? Result.Token)?.token
}

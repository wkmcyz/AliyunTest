package com.fenbi.aliyuntest.record

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.UiThread
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private val logTree = object {
    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun d(message: String) {
        Log.d(TAG, message)
    }

    fun e(message: String) {
        Log.e(TAG, message)
    }
}

internal class ServiceController(
    private val activity: ComponentActivity, private val coroutineScope: CoroutineScope, private val logTag: String
) {

    companion object {
        private const val WAIT_SERVICE_READY_INTERVAL_MS = 100L
    }

    private var applyRecordPermissionContinuation: CancellableContinuation<Boolean>? = null

    private val resultLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            logTree.d("Media projection permission result: ${result.resultCode}")
            coroutineScope.launch {
                val success = when (result.resultCode) {
                    Activity.RESULT_OK -> {
                        result.data?.let { intent ->
                            logTree.i("Media projection permission granted")
                            HighlightRecorderService.startServiceWithProjection(
                                activity, result.resultCode, intent
                            )
                            while (!HighlightRecorderService.isRunning) {
                                delay(WAIT_SERVICE_READY_INTERVAL_MS)
                            }
                            true
                        } ?: run {
                            logTree.e("Media projection data is null")
                            false
                        }
                    }

                    else -> {
                        logTree.e("Media projection permission denied")
                        false
                    }
                }
                // 恢复协程并传递结果
                applyRecordPermissionContinuation?.resume(success)
                applyRecordPermissionContinuation = null // 清理引用
            }
        }

    /**
     * apply media projection permission and start service.
     * returns true if successfully started service, false if permission application rejected.
     *
     * must be called before [startRecord]
     */
    @UiThread
    suspend fun startService(): Boolean = suspendCancellableCoroutine { cont ->
        if (HighlightRecorderService.isRunning) {
            logTree.d("record service is running.")
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        // 检查是否有未完成的请求
        if (applyRecordPermissionContinuation != null) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }

        applyRecordPermissionContinuation = cont // 保存当前 Continuation

        // 处理协程取消
        cont.invokeOnCancellation {
            applyRecordPermissionContinuation = null
            logTree.d("Request cancelled")
        }
        logTree.d("start apply record permission")
        val mediaProjectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // 启动权限请求
        resultLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

}
package com.fenbi.aliyuntest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import com.fenbi.aliyuntest.record.HighlightRecordConfig
import com.fenbi.aliyuntest.record.HighlightRecorderService
import com.fenbi.aliyuntest.record.ServiceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

class VM(val activity: MainActivity) : ViewModel() {

    var recordStatus = MutableStateFlow("未开始")

    var recordFilePath = MutableStateFlow("")

    internal lateinit var serviceController: ServiceController

    // 创建权限请求合约
    val requestPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        when {
            isGranted -> onGranted()
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO) -> onDenied()
            else -> onPermanentDenied()
        }
    }

    /**
     * 动态申请录音权限（RECORD_AUDIO）
     * @param context 上下文（需为 FragmentActivity）
     * @param onGranted 授权成功回调
     * @param onDenied 用户拒绝且未勾选"不再询问"时的回调
     * @param onPermanentDenied 用户拒绝且勾选"不再询问"时的回调
     */
    fun requestRecordAudioPermission(
        context: Context,
    ) {
        if (context !is ComponentActivity) {
            throw IllegalArgumentException("Context must be a FragmentActivity")
        }

        // 检查当前权限状态
        val permissionStatus = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        )

        if (permissionStatus == PackageManager.PERMISSION_GRANTED) {
            onGranted()
            return
        }

        // 执行权限请求
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun start() {
        requestRecordAudioPermission(
            activity,
        )
    }

    fun onGranted() {
        activity.lifecycleScope.launch {
            serviceController.startService()
            val file = File(activity.getExternalFilesDir("record")!!, "${System.currentTimeMillis()}.mp4")
            HighlightRecorderService.startRecording(file, 1280, 720, HighlightRecordConfig())
            recordFilePath.value = file.absolutePath
            recordStatus.value = "录制中"
        }
    }

    fun onDenied() {
        recordStatus.value = "录音权限被拒绝"
    }

    fun onPermanentDenied() {
        recordStatus.value = "录音权限被拒绝，且不再询问"
    }

    fun stop() {
        HighlightRecorderService.stop()
        recordStatus.value = "录制完成"
    }
}

class MainActivity : ComponentActivity() {
    internal val serviceController: ServiceController = ServiceController(this, lifecycleScope, "ServiceController")

    val vm = VM(activity = this).also {
        it.serviceController = serviceController
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Greeting(vm)
        }
    }
}

@Composable
fun Greeting(vm: VM) {
    val recordStatus by vm.recordStatus.collectAsState()
    val recordFilePath by vm.recordFilePath.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        Text(
            text = recordStatus,
        )
        Spacer(modifier = Modifier.size(10.dp))
        Text(text = "录制文件路径：$recordFilePath")
        Spacer(modifier = Modifier.size(10.dp))
        Button(onClick = { vm.start() }) {
            Text(text = "开始录制")
        }
        Spacer(modifier = Modifier.size(10.dp))
        Button(onClick = { vm.stop() }) {
            Text(text = "停止录制")
        }
    }
}

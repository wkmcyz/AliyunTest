package com.fenbi.aliyuntest.record

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import com.fenbi.aliyuntest.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.min

const val TAG = "HighlightRecorderService"
private fun clog(message: String, params: Map<String, Any> = emptyMap()) {
    Log.i(TAG, message + " ${params.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
}


data class HighlightRecordConfig(
    var videoBitRate: Int = 20_000_000,
    var videoFrameRate: Int = 60,
    var audioSampleRate: Int = 44100,
    var audioBitRate: Int = 128_000,
)

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


class HighlightRecorderService : Service() {
    companion object {
        private const val ONE_THOUSAND = 1000
        private const val OUTPUT_TIMEOUT_WHEN_RECORDING_US = 10_000L
        private const val DRAIN_TIMEOUT_AFTER_STOP_US = 300_000L

        private const val AUDIO_CAPTURE_BUFFER_SIZE_BYTE = 2 * 1024 * 1024
        private const val AUDIO_INPUT_BUFFER_SIZE_BYTE = 2048

        private const val NOTIFICATION_ID = 441823
        private const val NOTIFICATION_CHANNEL_ID = "com.zebra.android.ai.oral"
        private const val NOTIFICATION_CHANNEL_NAME = "com.zebra.android.ai.oral"

        private var instance: HighlightRecorderService? = null

        private var startServiceTimeUs = 0L // 调用 start 的时间戳，用来统计启动耗时
        private var stopServiceTimeUs = 0L // 调用 stop 的时间戳，用来统计启动耗时

        val isRunning: Boolean
            get() = instance != null

        // 在 Activity 中启动服务时传递必要参数
        fun startServiceWithProjection(context: Context, resultCode: Int, data: Intent) {
            clog("startService")
            if (instance != null) {
                logTree.e("Service is already running.")
                return
            }
            val intent = Intent(context, HighlightRecorderService::class.java).apply {
                putExtra("result_code", resultCode)
                putExtra("data", data)
            }
            context.startService(intent)
        }

        fun startRecording(
            targetFile: File, displayWidth: Int, displayHeight: Int, recordConfig: HighlightRecordConfig
        ): Boolean {
            clog("startRecord")
            if (instance == null) {
                logTree.e("Service is not running,please start first.")
                return false
            }
            startServiceTimeUs = System.nanoTime() / ONE_THOUSAND
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                instance?.startRecordingInternal(targetFile, displayWidth, displayHeight, recordConfig)
                return true
            } else {
                clog(
                    "startRecordFailed", mapOf("reason" to "Build.VERSION.SDK_INT(${Build.VERSION.SDK_INT}) < Build.VERSION_CODES.Q")
                )
                logTree.e("Service is not running,please start first.")
                return false
            }
        }

        fun stop() {
            clog("stopRecord")
            stopServiceTimeUs = System.nanoTime() / ONE_THOUSAND
            instance?.stopRecordingInternal()
            clog("stopService")
            instance?.stopServiceCompletely()
        }
    }

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)


    // Video parameters
    private val videoMimeType = "video/avc"

    // Audio parameters
    private val audioMimeType = "audio/mp4a-latm"

    private lateinit var mediaProjection: MediaProjection
    private lateinit var videoEncoder: MediaCodec
    private lateinit var audioEncoder: MediaCodec
    private lateinit var mediaMuxer: MediaMuxer
    private lateinit var virtualDisplay: VirtualDisplay
    private lateinit var audioRecord: AudioRecord

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var isMuxerStarted = false
    private var isRecording = false

    private var videoFirstFrameTimeUs: Long = -1L
    private var audioFirstFrameTimeUs: Long = -1L
    private var startFrameTimeUs: Long = 0
    private var lastFrameTimeUs: Long = 0 // 生成的 mp4 里的最后一帧的到达时间，仅做记录用。
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotification()
        val resultCode = intent?.getIntExtra("result_code", Activity.RESULT_CANCELED)
        val data: Intent? = intent?.getParcelableExtra("data")

        if (resultCode != null && data != null) {
            mediaProjection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(resultCode, data)
        }
        return START_STICKY
    }


    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotification() {
        createNotificationChannel(this)
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID).setSmallIcon(R.drawable.ic_launcher_background)
            .setContentTitle("recordService").setContentText("recording").setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE).setShowWhen(true).build()
        val notificationManager = this.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(
            NOTIFICATION_ID, notification
        )
        startForeground(
            NOTIFICATION_ID,
            notification,
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
        )
        channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val manager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startRecordingInternal(
        targetFile: File, displayWidth: Int, displayHeight: Int, recordConfig: HighlightRecordConfig
    ) {
        if (isRecording) {
            return
        }
        logTree.i("Start recording. targetFile:${targetFile.absolutePath}; \nrecordConfig:${recordConfig}")
        isRecording = true
        setupVideoEncoder(displayWidth, displayHeight, recordConfig)
        setupAudioCapture(recordConfig)
        setupMuxer(targetFile)
        startEncodingLoops()
    }

    private fun setupVideoEncoder(displayWidth: Int, displayHeight: Int, recordConfig: HighlightRecordConfig) {
        clog(
            "startVideoEncoder", mapOf("displayWidth" to displayWidth, "displayHeight" to displayHeight, "recordConfig" to recordConfig)
        )
        logTree.d("Video size: ${displayWidth}x${displayHeight}")
        val format = MediaFormat.createVideoFormat(videoMimeType, displayWidth, displayHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, recordConfig.videoBitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, recordConfig.videoFrameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        videoEncoder = MediaCodec.createEncoderByType(videoMimeType).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            // 当使用 MediaCodec.createInputSurface()（例如 Camera 预览数据直接输入编码器）时，编码器会自动生成时间戳。
            createInputSurface().let { surface ->
                virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenRecorder",
                    displayWidth,
                    displayHeight,
                    resources.displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null
                )
                logTree.d("Virtual display created: $virtualDisplay")
            }
            start()
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setupAudioCapture(recordConfig: HighlightRecordConfig) {
        clog(
            "setupAudioCapture", mapOf("recordConfig" to recordConfig)
        )
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection).addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()

        val format = MediaFormat.createAudioFormat(audioMimeType, recordConfig.audioSampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, recordConfig.audioBitRate)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }

        audioEncoder = MediaCodec.createEncoderByType(audioMimeType).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        val bufferSize = AUDIO_CAPTURE_BUFFER_SIZE_BYTE

        audioRecord = AudioRecord.Builder().setAudioPlaybackCaptureConfig(config).setAudioFormat(
            AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(recordConfig.audioSampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        ).setBufferSizeInBytes(bufferSize).build()

        audioRecord.startRecording()
    }

    private fun setupMuxer(targetFile: File) {
        clog("setupMuxer", mapOf("targetFile" to targetFile))
        mediaMuxer = MediaMuxer(targetFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        logTree.d("Output file: ${targetFile.absolutePath}")
    }

    private fun startEncodingLoops() = coroutineScope.launch {
        clog("startEncodingLoops")
        launch { processVideoOutput() }
        launch { processAudioInput() }
        launch { processAudioOutput() }
    }

    private suspend fun processVideoOutput() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isRecording) {
            when (val outputBufferId = videoEncoder.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_WHEN_RECORDING_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    videoTrackIndex = mediaMuxer.addTrack(videoEncoder.outputFormat)
                    startMuxerIfReady()
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> yield()
                else -> if (outputBufferId >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // 忽略编码器的配置帧
                        videoEncoder.releaseOutputBuffer(outputBufferId, false)
                        continue
                    }
                    // 记录首帧时间戳
                    if (videoFirstFrameTimeUs == -1L) {
                        videoFirstFrameTimeUs = bufferInfo.presentationTimeUs
                        waitVideoAudioFirstFrame()
                    }
                    handleEncodedData(videoTrackIndex, videoEncoder, outputBufferId, bufferInfo)
                }
            }
        }
    }

    private suspend fun processAudioInput() {
        // 使用与编码器输入缓冲区匹配的大小
        val buffer = ByteBuffer.allocateDirect(AUDIO_INPUT_BUFFER_SIZE_BYTE)

        while (isRecording) {
            val bytesRead = audioRecord.read(buffer, buffer.remaining())
            if (bytesRead > 0) {
                buffer.flip() // 设置读取范围（position=0, limit=bytesRead）

                // 分段写入编码器
                var remainingBytes = bytesRead
                while (remainingBytes > 0 && isRecording) {
                    val inputBufferId = audioEncoder.dequeueInputBuffer(DRAIN_TIMEOUT_AFTER_STOP_US)
                    if (inputBufferId >= 0) {
                        val encoderBuffer = audioEncoder.getInputBuffer(inputBufferId)
                        encoderBuffer?.let {
                            val chunkSize = min(remainingBytes, it.remaining())

                            // 复制有效数据到编码器缓冲区
                            val oldLimit = buffer.limit()
                            buffer.limit(buffer.position() + chunkSize) // ⬅️ 限制复制范围
                            it.put(buffer)
                            buffer.limit(oldLimit) // 恢复原始limit

                            remainingBytes -= chunkSize
                            audioEncoder.queueInputBuffer(
                                inputBufferId, 0, chunkSize, System.nanoTime() / ONE_THOUSAND - startFrameTimeUs, 0
                            )
                        }
                    } else {
                        yield() // 等待编码器准备好
                    }
                }
                buffer.compact() //保留未处理数据
            } else {
                buffer.clear() // 无数据时重置
            }
        }
    }

    private suspend fun processAudioOutput() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isRecording) {
            when (val outputBufferId = audioEncoder.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_WHEN_RECORDING_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    audioTrackIndex = mediaMuxer.addTrack(audioEncoder.outputFormat)
                    logTree.d("Audio output format changed. track index: ${audioTrackIndex}; format: ${audioEncoder.outputFormat}")
                    startMuxerIfReady()
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> yield()
                else -> if (outputBufferId >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // 忽略编码器的配置帧
                        audioEncoder.releaseOutputBuffer(outputBufferId, false)
                        continue
                    }
                    // 记录首帧时间戳
                    if (audioFirstFrameTimeUs == -1L) {
                        audioFirstFrameTimeUs = bufferInfo.presentationTimeUs
                        waitVideoAudioFirstFrame()
                    }
                    handleEncodedData(audioTrackIndex, audioEncoder, outputBufferId, bufferInfo)
                }
            }
        }
    }

    @Suppress("MaxLineLength")
    private suspend fun waitVideoAudioFirstFrame() {
        while (audioFirstFrameTimeUs == -1L || videoFirstFrameTimeUs == -1L) {
            yield()
        }
        startFrameTimeUs = min(videoFirstFrameTimeUs, audioFirstFrameTimeUs) // 初始化基准时间
        logTree.d(
            "first frame timestamp us: $startFrameTimeUs , videoFirstFrameTimeUs：${videoFirstFrameTimeUs}; audioFirstFrameTimeUs : ${audioFirstFrameTimeUs}"
        )
        logTree.i("Starting consumes time : ${(startFrameTimeUs - startServiceTimeUs) / ONE_THOUSAND}ms.")
    }

    @Synchronized
    private fun startMuxerIfReady() {
        // 在启动muxer时记录起始时间
        if (!isMuxerStarted && videoTrackIndex != -1 && audioTrackIndex != -1) {
            clog("startMuxer")
            mediaMuxer.start()
            isMuxerStarted = true
        }
    }

    private fun handleEncodedData(
        trackIndex: Int, codec: MediaCodec, bufferId: Int, bufferInfo: MediaCodec.BufferInfo
    ) {
        if (!isMuxerStarted) return
        bufferInfo.presentationTimeUs -= startFrameTimeUs // 校准时间戳
        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 && bufferInfo.presentationTimeUs < 0L) {
            // 忽略负时间戳的关键帧
            logTree.e("Negative timestamp: ${bufferInfo.presentationTimeUs}; track index: $trackIndex; bufferFlag: ${bufferInfo.flags}")
            return
        }
        lastFrameTimeUs = System.nanoTime() / ONE_THOUSAND

        codec.getOutputBuffer(bufferId)?.let { buffer ->
            mediaMuxer.writeSampleData(trackIndex, buffer, bufferInfo)
        }
        codec.releaseOutputBuffer(bufferId, false)
    }

    private fun stopRecordingInternal() {
        if (!isRecording) {
            return
        }
        logTree.d("Stop recording. this:${this.hashCode()}")
        isRecording = false
        coroutineScope.cancel()

        // 停止输入源
        virtualDisplay.release()
        mediaProjection.stop()
        audioRecord.stop()

        // 发送视频编码结束信号
        videoEncoder.signalEndOfInputStream()

        // 处理剩余数据
        drainVideoEncoder()
        drainAudioEncoder()
        logTree.i("Stopping consumes time : ${(lastFrameTimeUs - stopServiceTimeUs) / ONE_THOUSAND}ms.")

        // 释放资源
        audioRecord.release()
        if (isMuxerStarted) {
            mediaMuxer.stop()
            mediaMuxer.release()
        }
        videoEncoder.stop()
        videoEncoder.release()
        audioEncoder.stop()
        audioEncoder.release()
        logTree.i("Recording stopped.")
    }

    private fun drainVideoEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputBufferId = videoEncoder.dequeueOutputBuffer(bufferInfo, DRAIN_TIMEOUT_AFTER_STOP_US)
            when {
                outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                outputBufferId >= 0 -> handleEncodedData(videoTrackIndex, videoEncoder, outputBufferId, bufferInfo)
            }
        }
    }

    private fun drainAudioEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputBufferId = audioEncoder.dequeueOutputBuffer(bufferInfo, DRAIN_TIMEOUT_AFTER_STOP_US)
            when {
                outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                outputBufferId >= 0 -> handleEncodedData(audioTrackIndex, audioEncoder, outputBufferId, bufferInfo)
            }
        }
    }

    fun stopServiceCompletely() {
        if (isRecording) {
            stopRecordingInternal()
        }
        if (::mediaProjection.isInitialized) {
            mediaProjection.stop()
        }
        instance = null
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        clog("onDestroy")
        instance = null
        logTree.i("Service destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

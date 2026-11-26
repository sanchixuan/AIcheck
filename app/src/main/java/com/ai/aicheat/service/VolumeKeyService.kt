package com.ai.aicheat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ai.aicheat.MainActivity
import com.ai.aicheat.util.RootUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

/**
 * 音量键监听服务
 * 使用Root权限监听全局按键事件
 */
class VolumeKeyService : LifecycleService() {
    
    enum class State { STOPPED, STARTING, RUNNING, STOPPING }

    companion object {
        private const val TAG = "VolumeKeyService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "volume_key_service"
        
        // 按键事件码
        private const val KEY_VOLUME_DOWN = "KEY_VOLUMEDOWN"
        private const val KEY_VOLUME_UP = "KEY_VOLUMEUP"
        
        private val mutableState = MutableStateFlow(State.STOPPED)
        val state = mutableState.asStateFlow()
        private val mutableResult = MutableStateFlow("")
        val lastResult = mutableResult.asStateFlow()

        fun start(context: Context) {
            if (mutableState.value != State.STOPPED) return
            mutableState.value = State.STARTING
            val intent = Intent(context, VolumeKeyService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: RuntimeException) {
                mutableState.value = State.STOPPED
                throw e
            }
        }

        fun stop(context: Context) {
            mutableState.value = State.STOPPING
            mutableResult.value = ""
            if (!context.stopService(Intent(context, VolumeKeyService::class.java))) {
                mutableState.value = State.STOPPED
            }
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private var keyMonitorJob: Job? = null
    @Volatile private var monitorProcess: Process? = null
    private var isProcessing = false  // 防止重复触发
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        startKeyMonitor()
        
        // 同时启动悬浮窗服务
        startService(Intent(this, OverlayService::class.java))
        
        mutableState.value = State.RUNNING
        Log.d(TAG, "VolumeKeyService started")
    }
    
    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY  // 被杀后重启
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "后台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持应用后台运行"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("服务运行中")
            .setContentText("正在监听...")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    
    /**
     * 启动按键监听
     * 使用getevent命令监听底层输入事件
     */
    private fun startKeyMonitor() {
        keyMonitorJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting key monitor...")
                
                // 使用su执行getevent监听所有输入设备
                val process = Runtime.getRuntime().exec("su")
                monitorProcess = process
                
                if (!isActive) {
                    process.destroy()
                    return@launch
                }
                val os = DataOutputStream(process.outputStream)
                // getevent -l 会输出可读的事件名称
                os.writeBytes("getevent -l\n")
                os.flush()
                
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                
                var lastVolumeDownTime = 0L
                var lastVolumeUpTime = 0L
                val debounceTime = 500L  // 防抖时间
                
                while (isActive) {
                    val line = reader.readLine() ?: break
                    
                    // 检测音量下键按下事件
                    if (line.contains(KEY_VOLUME_DOWN) && line.contains("DOWN")) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastVolumeDownTime > debounceTime) {
                            lastVolumeDownTime = currentTime
                            Log.d(TAG, "Volume DOWN pressed")
                            onVolumeDownPressed()
                        }
                    }
                    
                    // 检测音量上键按下事件
                    if (line.contains(KEY_VOLUME_UP) && line.contains("DOWN")) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastVolumeUpTime > debounceTime) {
                            lastVolumeUpTime = currentTime
                            Log.d(TAG, "Volume UP pressed")
                            lifecycleScope.launch {
                                if (state.value == State.RUNNING) onVolumeUpPressed()
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Key monitor stopped")
            } finally {
                monitorProcess?.destroy()
                if (isActive) stopSelf()
            }
        }
    }
    
    /**
     * 音量下键按下 - 截图并发送AI
     */
    private fun onVolumeDownPressed() {
        lifecycleScope.launch {
            if (isProcessing || state.value != State.RUNNING) return@launch
            isProcessing = true
            try {
                // 显示处理提示
                OverlayService.showText(this@VolumeKeyService, "正在处理...")
                
                // 短暂延迟，避免截到按键动画
                delay(100)
                
                // 截图
                Log.d(TAG, "Taking screenshot...")
                val bitmap = RootUtils.takeScreenshotAsBitmap()
                
                if (state.value != State.RUNNING) {
                    bitmap?.recycle()
                    return@launch
                }
                if (bitmap == null) {
                    OverlayService.showText(this@VolumeKeyService, "截图失败")
                    return@launch
                }
                try {
                    OverlayService.showText(this@VolumeKeyService, "正在分析...")
                    val reply = AIService.analyzeScreenshot(bitmap)
                    if (state.value != State.RUNNING) return@launch
                    reply.onSuccess { text ->
                        mutableResult.value = text
                        OverlayService.showText(this@VolumeKeyService, text)
                    }.onFailure { error ->
                        OverlayService.showText(this@VolumeKeyService, error.message ?: "请求失败")
                    }
                } finally {
                    bitmap.recycle()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (state.value == State.RUNNING) OverlayService.showText(this@VolumeKeyService, "处理失败")
            } finally {
                isProcessing = false
            }
        }
    }
    
    /**
     * 音量上键按下 - 切换悬浮窗可见性
     */
    private fun onVolumeUpPressed() {
        OverlayService.hideText(this)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        keyMonitorJob?.cancel()
        try {
            monitorProcess?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying process", e)
        }
        stopService(Intent(this, OverlayService::class.java))
        mutableResult.value = ""
        mutableState.value = State.STOPPED
        Log.d(TAG, "VolumeKeyService stopped")
    }
}

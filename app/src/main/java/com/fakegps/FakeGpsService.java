package com.fakegps

import android.app.*
import android.content.*
import android.graphics.*
import android.net.*
import android.net.VpnService
import android.os.*
import android.provider.Settings
import android.util.*
import android.view.*
import android.widget.*
import androidx.core.app.*
import java.io.*
import kotlin.math.*

/**
 * FakeGpsService - 虚拟定位服务
 * 核心方案: addTestProvider (无需 root，适用于 Android 6-10)
 * 备用方案: VpnService 网络劫持 (Android 11+)
 */
class FakeGpsService : Service(), Runnable {

    companion object {
        const val ACTION_START = "com.fakegps.START"
        const val ACTION_STOP  = "com.fakegps.STOP"
        const val ACTION_UPDATE = "com.fakegps.UPDATE"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        private const val PROVIDER_NAME = "FakeGpsProvider"
        private const val NOTIFY_ID = 2001
        private const val CHANNEL_ID = "fake_gps_channel"

        var isRunning = false
            private set

        fun stop(context: Context) {
            context.startService(Intent(context, FakeGpsService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    var fakeLat = 0.0
    var fakeLon = 0.0
    private var fakeAlt = 0.0
    private var fakeAccuracy = 5f

    private var locationManager: android.location.LocationManager? = null
    private var vpnThread: Thread? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var floatingView: View? = null
    private var windowManager: WindowManager? = null

    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                fakeLat = intent.getDoubleExtra(EXTRA_LAT, fakeLat)
                fakeLon = intent.getDoubleExtra(EXTRA_LON, fakeLon)
                updateLocation(fakeLat, fakeLon)
                return START_STICKY
            }
            else -> {
                fakeLat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                fakeLon = intent.getDoubleExtra(EXTRA_LON, 0.0)
            }
        }

        startForeground(NOTIFY_ID, buildNotification("定位保护中..."))
        startFloatingBall()
        startFakeProvider()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopFakeProvider()
        stopFloatingBall()
        isRunning = false
    }

    // ===================== addTestProvider 方案 =====================
    // Android 6-10: 直接用 LocationManager.addTestProvider
    // Android 11+: addTestProvider 需要 WRITE_SECURE_SETTINGS (通常需要 root)
    //              但部分设备允许通过 adb pm grant 安装后直接使用

    private fun startFakeProvider() {
        val lm = locationManager ?: return

        try {
            // 先尝试删除旧的 test provider
            try {
                lm.removeTestProvider(PROVIDER_NAME)
            } catch (e: Exception) { }

            // 添加测试 Provider
            val properties = android.location.provider.ProviderProperties.Builder()
                .setHasMonetaryCost(false)
                .setSupportsAltitude(true)
                .setSupportsSpeed(true)
                .setSupportsBearing(true)
                .setAccuracy(android.location.provider.ProviderProperties.Accuracy.FINE)
                .setPowerUsage(android.location.provider.ProviderProperties.POWER_USAGE_LOW)
                .build()

            lm.addTestProvider(
                PROVIDER_NAME,
                false,  // requiresNetwork
                false,  // requiresCell
                false,  // hasMonetaryCost
                true,   // supportsAltitude
                true,   // supportsSpeed
                true,   // supportsBearing
                android.location.provider.ProviderProperties.POWER_USAGE_LOW,
                android.location.provider.ProviderProperties.Accuracy.FINE
            )

            // 启用 provider
            lm.setTestProviderEnabled(PROVIDER_NAME, true)

            // 设置初始位置
            updateLocation(fakeLat, fakeLon)

            // 尝试启用 native hook
            try {
                FakeGpsNative.setFakeLocation(fakeLat, fakeLon, fakeAlt, fakeAccuracy)
                FakeGpsNative.enable()
            } catch (e: Exception) {
                Log.w("FakeGps", "Native hook not available: ${e.message}")
            }

            isRunning = true
            updateNotification("定位保护中 (${String.format("%.4f", fakeLat)}, ${String.format("%.4f", fakeLon)})")
            Log.i("FakeGps", "addTestProvider started")

        } catch (e: SecurityException) {
            Log.e("FakeGps", "addTestProvider failed (need WRITE_SECURE_SETTINGS): ${e.message}")
            // 回退到 VPN 方案
            startVpnFallback()
        } catch (e: Exception) {
            Log.e("FakeGps", "Fake provider error: ${e.message}", e)
            stopSelf()
        }
    }

    private fun updateLocation(lat: Double, lon: Double) {
        val lm = locationManager ?: return
        try {
            val location = android.location.Location(PROVIDER_NAME).apply {
                this.latitude = lat
                this.longitude = lon
                this.altitude = fakeAlt
                this.accuracy = fakeAccuracy
                this.speed = 0f
                this.bearing = 0f
                this.time = System.currentTimeMillis()
                this.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                // 必需：设置 Provider
                provider = PROVIDER_NAME
            }

            lm.setTestProviderLocation(PROVIDER_NAME, location)
            Log.d("FakeGps", "Location updated: $lat, $lon")

        } catch (e: Exception) {
            Log.e("FakeGps", "Update location failed: ${e.message}")
        }
    }

    private fun stopFakeProvider() {
        val lm = locationManager ?: return
        try {
            lm.removeTestProvider(PROVIDER_NAME)
        } catch (e: Exception) { }
        try {
            FakeGpsNative.disable()
        } catch (e: Exception) { }
        isRunning = false
    }

    // ===================== VPN 回退方案 (Android 11+) =====================
    // 如果 addTestProvider 不可用，使用本地 VPN 劫持网络定位
    // 注意：VPN 方案只能劫持 HTTP 请求，对 HTTPS 定位无效
    //      主要用于一些使用 HTTP API 而非系统 GPS 的 App

    private fun startVpnFallback() {
        Log.i("FakeGps", "Starting VPN fallback mode")
        try {
            val builder = Builder()
                .setSession("FakeGps")
                .setMtu(1500)
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")

            // 不要劫持本应用
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) { }

            vpnInterface = builder.establish()
            if (vpnInterface != null) {
                vpnThread = Thread(this, "FakeGps-VPN").apply { start() }
                isRunning = true
                Log.i("FakeGps", "VPN fallback started")
            }
        } catch (e: Exception) {
            Log.e("FakeGps", "VPN fallback failed: ${e.message}")
        }
    }

    // VPN 主循环（简化为 NTP 时间同步，GPS 劫持在 native 层处理）
    override fun run() {
        val fd = vpnInterface?.fileDescriptor ?: return
        val buffer = ByteBuffer.allocate(1500)

        try {
            while (!Thread.currentThread().isInterrupted) {
                buffer.clear()
                val fis = FileInputStream(fd)
                val n = try { fis.read(buffer.array()) } catch (e: Exception) {
                    Thread.sleep(50)
                    continue
                }
                if (n <= 0) {
                    Thread.sleep(50)
                    continue
                }
                buffer.limit(n)

                // 简单的 IP 包解析
                if (n >= 20) {
                    val version = (buffer.get(0).toInt() and 0xF0) shr 4
                    if (version == 4) {
                        processIpPacket(buffer, n, fd)
                    }
                }
            }
        } catch (e: InterruptedException) { }
          catch (e: Exception) {
            Log.e("FakeGps", "VPN loop error: ${e.message}")
        }
    }

    private fun processIpPacket(buffer: ByteBuffer, len: Int, fd: FileDescriptor) {
        // VPN 层: 主要负责保持连接活跃
        // 真正的 GPS 数据伪造在 Native Hook 层
    }

    // ===================== 浮动窗口 =====================

    private fun startFloatingBall() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        val ball = TextView(this).apply {
            text = "📍"
            textSize = 22f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.floating_circle)
            setTextColor(Color.WHITE)
        }

        root.addView(ball)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }

        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f

        ball.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (event.rawX - touchX).toInt()
                    params.y = initY + (event.rawY - touchY).toInt()
                    windowManager?.updateViewLayout(root, params)
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(root, params)
            floatingView = root
        } catch (e: Exception) {
            Log.e("FakeGps", "Floating ball error: ${e.message}")
        }
    }

    private fun stopFloatingBall() {
        try {
            floatingView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) { }
    }

    private fun requestOverlayPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: Exception) { }
    }

    // ===================== 通知 =====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "定位保护", NotificationManager.IMPORTANCE_LOW).apply {
                description = "虚拟定位运行中"
                setShowBadge(false)
            }.also { notificationManager.createNotificationChannel(it) }
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FakeGpsService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📍 虚拟定位运行中")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_location)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(R.drawable.ic_stop, "停止", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            notificationManager.notify(NOTIFY_ID, buildNotification(text))
        } catch (e: Exception) { }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

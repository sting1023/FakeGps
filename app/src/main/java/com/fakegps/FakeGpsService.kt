package com.fakegps

import android.app.*
import android.content.*
import android.location.*
import android.os.*
import android.util.*

/**
 * FakeGpsService - 虚拟定位服务（简化版）
 * 使用 addTestProvider API，无需 root，适用于 Android 6-14
 */
class FakeGpsService : Service() {

    companion object {
        const val ACTION_START = "com.fakegps.START"
        const val ACTION_STOP  = "com.fakegps.STOP"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        private const val PROVIDER_NAME = "fake_gps"

        var isRunning = false
            private set

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FakeGpsService::class.java))
        }
    }

    private lateinit var locationManager: LocationManager
    private var currentLat = 0.0
    private var currentLon = 0.0

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                updateLocation()
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        isRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                currentLat = intent.getDoubleExtra(EXTRA_LAT, 39.9)
                currentLon = intent.getDoubleExtra(EXTRA_LON, 116.4)
                startFakeLocation()
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startFakeLocation() {
        try {
            locationManager.removeTestProvider(PROVIDER_NAME)
        } catch (e: Exception) {
            // ignore
        }

        try {
            locationManager.addTestProvider(
                PROVIDER_NAME,
                false, // requiresNetwork
                false, // requiresSatellite
                false, // requiresCell
                false, // hasMonetaryCost
                false, // supportsAltitude
                false  // supportsSpeed
            )
        } catch (e: SecurityException) {
            Log.e("FakeGpsService", "需要 ACCESS_MOCK_LOCATION 权限，请在开发者选项中开启")
            isRunning = false
            stopSelf()
            return
        } catch (e: Exception) {
            Log.e("FakeGpsService", "添加Provider失败: ${e.message}")
            isRunning = false
            stopSelf()
            return
        }

        isRunning = true
        updateLocation()
        handler.post(updateRunnable)
        Log.i("FakeGpsService", "虚拟定位已启动: $currentLat, $currentLon")
    }

    private fun updateLocation() {
        try {
            val location = Location(PROVIDER_NAME).apply {
                latitude = currentLat
                longitude = currentLon
                altitude = 0.0
                accuracy = 5f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos())
            }

            locationManager.setTestProviderEnabled(PROVIDER_NAME, true)
            locationManager.setTestProviderLocation(PROVIDER_NAME, location)
        } catch (e: Exception) {
            Log.e("FakeGpsService", "更新位置失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
        try {
            locationManager.removeTestProvider(PROVIDER_NAME)
        } catch (e: Exception) {
            // ignore
        }
        isRunning = false
        Log.i("FakeGpsService", "虚拟定位已停止")
    }

    override fun onBind(intent: Intent?) = null
}

package com.fakegps

import android.*
import android.Manifest
import android.annotation.*
import android.app.*
import android.content.*
import android.content.pm.*
import android.graphics.*
import android.net.*
import android.os.*
import android.provider.*
import android.view.*
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.*
import androidx.core.app.*
import org.json.*
import java.io.*
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQ_OVERLAY = 1001
        const val REQ_VPN = 1002
        const val REQ_LOCATION = 1003

        // 保存的位置列表文件（lazy避免提前访问filesDir）
        val savedFile: File
            get() = File(filesDir, "saved_locations.json")
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvCurrent: TextView
    private lateinit var etLat: EditText
    private lateinit var etLon: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnMap: Button
    private lateinit var btnSave: Button
    private lateinit var btnLoad: Button
    private lateinit var lvSaved: ListView
    private lateinit var tvSavedTitle: TextView

    private var webView: WebView? = null
    private var dialogMap: Dialog? = null

    private var savedLocations = mutableListOf<SavedLocation>()
    private var adapter: SavedLocationAdapter? = null

    data class SavedLocation(val name: String, val lat: Double, val lon: Double)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadSavedLocations()
        checkPermissions()
    }

    private fun initViews() {
        tvStatus    = findViewById(R.id.tv_status)
        tvCurrent   = findViewById(R.id.tv_current)
        etLat       = findViewById(R.id.et_lat)
        etLon       = findViewById(R.id.et_lon)
        btnStart    = findViewById(R.id.btn_start)
        btnStop     = findViewById(R.id.btn_stop)
        btnMap      = findViewById(R.id.btn_map)
        btnSave     = findViewById(R.id.btn_save)
        btnLoad     = findViewById(R.id.btn_load)
        lvSaved     = findViewById(R.id.lv_saved)
        tvSavedTitle = findViewById(R.id.tv_saved_title)

        btnStart.setOnClickListener { startFakeGps() }
        btnStop.setOnClickListener  { stopFakeGps() }
        btnMap.setOnClickListener   { showMapPicker() }
        btnSave.setOnClickListener  { saveCurrentLocation() }
        btnLoad.setOnClickListener  { toggleSavedList() }

        lvSaved.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            val loc = savedLocations[pos]
            etLat.setText(loc.lat.toString())
            etLon.setText(loc.lon.toString())
            Toast.makeText(this, "已加载: ${loc.name}", Toast.LENGTH_SHORT).show()
        }

        lvSaved.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, pos, _ ->
            savedLocations.removeAt(pos)
            saveSavedLocations()
            adapter?.notifyDataSetChanged()
            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun toggleSavedList() {
        if (lvSaved.visibility == View.VISIBLE) {
            lvSaved.visibility = View.GONE
            tvSavedTitle.visibility = View.GONE
        } else {
            adapter = SavedLocationAdapter(savedLocations)
            lvSaved.adapter = adapter
            lvSaved.visibility = View.VISIBLE
            tvSavedTitle.visibility = View.VISIBLE
        }
    }

    // ===================== 权限 =====================

    private fun checkPermissions() {
        val perms = mutableListOf<String>()

        // 精确位置
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)

        // 浮窗
        if (!Settings.canDrawOverlays(this))
            perms.add(Manifest.permission.SYSTEM_ALERT_WINDOW)

        // VPN
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            tvStatus.text = "⚠️ 需要授权 VPN 权限\n请在下一步点击确定"
            AlertDialog.Builder(this)
                .setTitle("VPN 权限")
                .setMessage("定位助手需要创建本地 VPN 来拦截定位数据。\n这是本地 VPN，不会上传任何数据。")
                .setPositiveButton("授权") { _, _ ->
                    startActivityForResult(vpnIntent, REQ_VPN)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        updateStatus()
    }

    private fun updateStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasVpn = VpnService.prepare(this) == null
        val hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        val statusText = buildString {
            append(if (hasLocation) "✅ 位置权限\n" else "❌ 位置权限\n")
            append(if (hasOverlay) "✅ 浮窗权限\n" else "❌ 浮窗权限\n")
            append(if (hasVpn) "✅ VPN权限" else "❌ VPN权限")
        }
        tvStatus.text = statusText

        val allOk = hasOverlay && hasVpn
        btnStart.isEnabled = allOk
        if (FakeGpsService.isRunning) {
            tvCurrent.text = "📍 定位保护中\n${etLat.text} / ${etLon.text}"
            btnStop.isEnabled = true
        } else {
            btnStop.isEnabled = false
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res == Activity.RESULT_OK) updateStatus()
        else {
            Toast.makeText(this, "权限不足，功能无法使用", Toast.LENGTH_LONG).show()
        }
    }

    // ===================== 核心功能 =====================

    private fun startFakeGps() {
        val latStr = etLat.text.toString().trim()
        val lonStr = etLon.text.toString().trim()

        if (latStr.isEmpty() || lonStr.isEmpty()) {
            Toast.makeText(this, "请输入经纬度", Toast.LENGTH_SHORT).show()
            return
        }

        val lat = latStr.toDoubleOrNull()
        val lon = lonStr.toDoubleOrNull()

        if (lat == null || lon == null || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            Toast.makeText(this, "经纬度格式不正确", Toast.LENGTH_SHORT).show()
            return
        }

        // 先设坐标再启动
        val intent = Intent(this, FakeGpsService::class.java).apply {
            action = FakeGpsService.ACTION_START
            putExtra(FakeGpsService.EXTRA_LAT, lat)
            putExtra(FakeGpsService.EXTRA_LON, lon)
        }
        startService(intent)

        Toast.makeText(this, "虚拟定位已启动", Toast.LENGTH_SHORT).show()
        btnStop.isEnabled = true
        btnStart.isEnabled = false
        tvCurrent.text = "📍 定位保护中\n纬度: $lat\n经度: $lon"
    }

    private fun stopFakeGps() {
        FakeGpsService.stop(this)
        Toast.makeText(this, "虚拟定位已停止", Toast.LENGTH_SHORT).show()
        btnStop.isEnabled = false
        btnStart.isEnabled = true
        tvCurrent.text = "⏹ 未开启虚拟定位"
    }

    // ===================== 地图选点 =====================

    private fun showMapPicker() {
        val currentLat = etLat.text.toString().toDoubleOrNull() ?: 39.9
        val currentLon = etLon.text.toString().toDoubleOrNull() ?: 116.4

        dialogMap = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(R.layout.dialog_map)
        }

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: Message?) {
                    // 处理地图 JS 回调
                    msg?.let {
                        val text = it.message ?: return
                        if (text.startsWith("LOCATION_CALLBACK:")) {
                            val parts = text.removePrefix("LOCATION_CALLBACK:").split(",")
                            if (parts.size == 2) {
                                val lat = parts[0].toDoubleOrNull()
                                val lon = parts[1].toDoubleOrNull()
                                if (lat != null && lon != null) {
                                    runOnUiThread {
                                        etLat.setText(String.format("%.6f", lat))
                                        etLon.setText(String.format("%.6f", lon))
                                        dialogMap?.dismiss()
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
                <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body { height: 100vh; display: flex; flex-direction: column; }
                    #map { flex: 1; }
                    #panel {
                        background: #1a1a1a;
                        padding: 12px 16px 16px;
                        color: #fff;
                        position: relative;
                    }
                    #panel .title {
                        font-size: 14px;
                        color: #888;
                        margin-bottom: 6px;
                    }
                    #panel .coords {
                        font-size: 18px;
                        font-weight: bold;
                        margin-bottom: 10px;
                    }
                    #panel .buttons {
                        display: flex;
                        gap: 10px;
                    }
                    #panel button {
                        flex: 1;
                        height: 44px;
                        border: none;
                        border-radius: 8px;
                        font-size: 15px;
                        cursor: pointer;
                    }
                    #btn-confirm { background: #2196F3; color: #fff; }
                    #btn-cancel  { background: #444; color: #ccc; }
                    #draggable-marker {
                        width: 32px; height: 32px;
                        background: #E53935;
                        border: 3px solid white;
                        border-radius: 50%;
                        box-shadow: 0 2px 8px rgba(0,0,0,0.4);
                        cursor: grab;
                        position: absolute;
                        top: 50%; left: 50%;
                        transform: translate(-50%, -100%);
                        pointer-events: none;
                        z-index: 1000;
                    }
                </style>
                </head>
                <body>
                <div id="map"></div>
                <div id="draggable-marker"></div>
                <div id="panel">
                    <div class="title">拖动地图选点，或长按地图</div>
                    <div class="coords" id="coords">纬度: --<br>经度: --</div>
                    <div class="buttons">
                        <button id="btn-cancel" onclick="cancel()">取消</button>
                        <button id="btn-confirm" onclick="confirm()">确认位置</button>
                    </div>
                </div>
                <script>
                var lat = $currentLat, lng = $currentLon;
                var map = L.map('map', {
                    zoomControl: false,
                    attributionControl: false
                }).setView([lat, lng], 16);

                L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
                    maxZoom: 19
                }).addTo(map);

                var marker = L.marker([lat, lng], {draggable: true}).addTo(map);

                function updateCoords() {
                    var text = '纬度: ' + marker.getLatLng().lat.toFixed(6) +
                               '<br>经度: ' + marker.getLatLng().lng.toFixed(6);
                    document.getElementById('coords').innerHTML = text;
                    // JS回调给Android
                    if (window.Android && window.Android.onLocationSelected) {
                        window.Android.onLocationSelected(
                            marker.getLatLng().lat, marker.getLatLng().lng);
                    }
                }

                marker.on('dragend', function() {
                    lat = marker.getLatLng().lat;
                    lng = marker.getLatLng().lng;
                    updateCoords();
                });

                marker.on('move', updateCoords);

                // 长按地图
                map.on('contextmenu', function(e) {
                    marker.setLatLng(e.latlng);
                    lat = e.latlng.lat; lng = e.latlng.lng;
                    updateCoords();
                });

                // 拖动地图结束后更新标记
                map.on('moveend', function() {
                    var c = map.getCenter();
                    marker.setLatLng(c);
                    lat = c.lat; lng = c.lng;
                    updateCoords();
                });

                // 地图中心跟随标记（触摸设备上）
                map.on('touchmove', function(e) {
                    // 不自动跟随，避免抖动
                });

                function confirm() {
                    console.log('LOCATION_CALLBACK:' + lat + ',' + lng);
                    window.location = 'fakegps://confirm?lat=' + lat + '&lng=' + lng;
                }
                function cancel() {
                    window.location = 'fakegps://cancel';
                }

                // 处理 Android 回退
                window.onpopstate = function() { cancel(); };

                updateCoords();
                </script>
                </body>
                </html>
            """.trimIndent()

            loadDataWithBaseURL("https://openstreetmap.org/", html, "text/html", "UTF-8", null)

            // Android JS 接口
            addJavascriptInterface(object : Any() {
                @JavascriptInterface
                fun onLocationSelected(lat: Double, lng: Double) {
                    // Android 端处理（备用）
                }
            }, "Android")
        }

        (dialogMap as? Dialog)?.findViewById<View>(android.R.id.content)?.let {
            (it as? ViewGroup)?.removeAllViews()
        }

        val contentView = layoutInflater.inflate(R.layout.dialog_map, null)
        dialogMap?.setContentView(contentView)

        val webContainer = contentView.findViewById<FrameLayout>(R.id.web_container)
        webContainer.addView(webView)
        this.webView = webView

        dialogMap?.setOnDismissListener {
            webView.stopLoading()
            webView.destroy()
            this.webView = null
        }

        dialogMap?.show()
    }

    // ===================== 保存的位置 =====================

    private fun saveCurrentLocation() {
        val name = etLat.text.toString().takeIf { it.isNotEmpty() } ?: return
        val lat = etLat.text.toString().toDoubleOrNull() ?: return
        val lon = etLon.text.toString().toDoubleOrNull() ?: return

        val dialog = AlertDialog.Builder(this)
            .setTitle("保存位置")
            .setView(EditText(this).apply {
                hint = "位置名称，如：公司/家里"
                setPadding(48, 32, 48, 16)
            })
            .setPositiveButton("保存") { d, _ ->
                val editText = (d as AlertDialog).findViewById<EditText>(android.R.id.custom)
                val locName = editText?.text.toString().ifEmpty {
                    "位置${savedLocations.size + 1}"
                }
                savedLocations.add(SavedLocation(locName, lat, lon))
                saveSavedLocations()
                adapter?.notifyDataSetChanged()
                Toast.makeText(this, "已保存: $locName", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()

        dialog.show()
    }

    private fun loadSavedLocations() {
        try {
            if (savedFile.exists()) {
                val json = savedFile.readText()
                val arr = JSONArray(json)
                savedLocations.clear()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    savedLocations.add(
                        SavedLocation(obj.getString("name"), obj.getDouble("lat"), obj.getDouble("lon"))
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveSavedLocations() {
        val arr = JSONArray()
        savedLocations.forEach {
            arr.put(JSONObject().apply {
                put("name", it.name)
                put("lat", it.lat)
                put("lon", it.lon)
            })
        }
        savedFile.writeText(arr.toString(2))
    }

    // ===================== 适配器 =====================

    inner class SavedLocationAdapter(
        val items: List<SavedLocation>
    ) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(pos: Int) = items[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, cv: View?, parent: ViewGroup?): View {
            val view = cv ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val loc = items[pos]
            (view as TextView).apply {
                text1 = "📍 ${loc.name}"
                text2 = "${loc.lat}, ${loc.lon}"
                textSize = 15f
            }
            return view
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        dialogMap?.dismiss()
    }
}

package com.fakegps

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.*
import android.graphics.*
import android.net.*
import android.net.VpnService
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
    }

    // 位置文件（延迟初始化，在 onCreate 后才访问）
    private lateinit var savedFile: File

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

        // onCreate 后才初始化 filesDir 相关
        savedFile = File(filesDir, "saved_locations.json")

        initViews()
        loadSavedLocations()
        checkPermissions()
    }

    private fun initViews() {
        tvStatus     = findViewById(R.id.tv_status)
        tvCurrent    = findViewById(R.id.tv_current)
        etLat        = findViewById(R.id.et_lat)
        etLon        = findViewById(R.id.et_lon)
        btnStart     = findViewById(R.id.btn_start)
        btnStop      = findViewById(R.id.btn_stop)
        btnMap       = findViewById(R.id.btn_map)
        btnSave      = findViewById(R.id.btn_save)
        btnLoad      = findViewById(R.id.btn_load)
        lvSaved      = findViewById(R.id.lv_saved)
        tvSavedTitle = findViewById(R.id.tv_saved_title)

        btnStart.setOnClickListener  { startFakeGps() }
        btnStop.setOnClickListener   { stopFakeGps() }
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
        // 精确位置
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
            return
        }

        // VPN
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            tvStatus.text = "⚠️ 需要授权 VPN 权限\n请在下一步点击确定"
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("VPN 权限")
                .setMessage("定位助手需要创建本地 VPN 来拦截定位数据。\n这是本地 VPN，不会上传任何数据。")
                .setPositiveButton("授权") { _: DialogInterface, _: Int ->
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
        if (com.fakegps.FakeGpsService.isRunning) {
            tvCurrent.text = "📍 定位保护中\n纬度: ${etLat.text}\n经度: ${etLon.text}"
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

        val intent = Intent(this, com.fakegps.FakeGpsService::class.java).apply {
            action = com.fakegps.FakeGpsService.ACTION_START
            putExtra(com.fakegps.FakeGpsService.EXTRA_LAT, lat)
            putExtra(com.fakegps.FakeGpsService.EXTRA_LON, lon)
        }
        startService(intent)

        Toast.makeText(this, "虚拟定位已启动", Toast.LENGTH_SHORT).show()
        btnStop.isEnabled = true
        btnStart.isEnabled = false
        tvCurrent.text = "📍 定位保护中\n纬度: $lat\n经度: $lon"
    }

    private fun stopFakeGps() {
        com.fakegps.FakeGpsService.stop(this)
        Toast.makeText(this, "虚拟定位已停止", Toast.LENGTH_SHORT).show()
        btnStop.isEnabled = false
        btnStart.isEnabled = true
        tvCurrent.text = "⏹ 未开启虚拟定位"
    }

    // ===================== 地图选点 =====================

    private fun showMapPicker() {
        val currentLat = etLat.text.toString().toDoubleOrNull() ?: 39.9
        val currentLon = etLon.text.toString().toDoubleOrNull() ?: 116.4

        dialogMap = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        val text = it.message()
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
                    return true
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
body { height: 100vh; display: flex; flex-direction: column; font-family: -apple-system, sans-serif; }
#map { flex: 1; }
#panel {
    background: #1a1a1a;
    padding: 12px 16px 16px;
    color: #fff;
}
#panel .title { font-size: 13px; color: #888; margin-bottom: 6px; }
#panel .coords { font-size: 17px; font-weight: bold; margin-bottom: 10px; }
#panel .buttons { display: flex; gap: 10px; }
#panel button { flex: 1; height: 44px; border: none; border-radius: 8px; font-size: 15px; cursor: pointer; }
#btn-confirm { background: #2196F3; color: #fff; }
#btn-cancel { background: #444; color: #ccc; }
</style>
</head>
<body>
<div id="map"></div>
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
var map = L.map('map', { zoomControl: false, attributionControl: false }).setView([lat, lng], 16);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19 }).addTo(map);
var marker = L.marker([lat, lng], {draggable: true}).addTo(map);

function updateCoords() {
    document.getElementById('coords').innerHTML = '纬度: ' + marker.getLatLng().lat.toFixed(6) + '<br>经度: ' + marker.getLatLng().lng.toFixed(6);
    lat = marker.getLatLng().lat;
    lng = marker.getLatLng().lng;
}

marker.on('dragend', updateCoords);
map.on('contextmenu', function(e) { marker.setLatLng(e.latlng); updateCoords(); });
map.on('moveend', function() { var c = map.getCenter(); marker.setLatLng(c); updateCoords(); });
map.on('touchmove', function(e) { e.originalEvent.preventDefault(); });

function confirm() {
    console.log('LOCATION_CALLBACK:' + lat + ',' + lng);
    window.location = 'fakegps://confirm?lat=' + lat + '&lng=' + lng;
}
function cancel() { window.location = 'fakegps://cancel'; }

updateCoords();
</script>
</body>
</html>
            """.trimIndent()

            loadDataWithBaseURL("https://openstreetmap.org/", html, "text/html", "UTF-8", null)

            addJavascriptInterface(object : Any() {
                @JavascriptInterface
                fun onLocationSelected(lat: Double, lon: Double) {}
            }, "Android")
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
        val lat = etLat.text.toString().toDoubleOrNull() ?: return
        val lon = etLon.text.toString().toDoubleOrNull() ?: return

        val input = EditText(this).apply {
            hint = "位置名称，如：公司/家里"
            setPadding(64, 32, 64, 16)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("保存位置")
            .setView(input)
            .setPositiveButton("保存") { _: DialogInterface, _: Int ->
                val locName = input.text.toString().ifEmpty { "位置${savedLocations.size + 1}" }
                savedLocations.add(SavedLocation(locName, lat, lon))
                saveSavedLocations()
                adapter?.notifyDataSetChanged()
                Toast.makeText(this, "已保存: $locName", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun loadSavedLocations() {
        try {
            if (savedFile.exists()) {
                val json = savedFile.readText()
                val arr = JSONArray(json)
                savedLocations.clear()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    savedLocations.add(SavedLocation(
                        obj.getString("name"),
                        obj.getDouble("lat"),
                        obj.getDouble("lon")
                    ))
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
        private val items: List<SavedLocation>
    ) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(pos: Int) = items[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val loc = items[pos]
            val tv1 = view.findViewById<TextView>(android.R.id.text1)
            val tv2 = view.findViewById<TextView>(android.R.id.text2)
            tv1.text = "📍 ${loc.name}"
            tv2.text = "${loc.lat}, ${loc.lon}"
            tv1.textSize = 15f
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

package ru.routerdash.android

import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

class MainActivity : FragmentActivity() {
    private val securityPrefs by lazy { getSharedPreferences("routerdash_security", MODE_PRIVATE) }
    private var shouldAuthenticate = true
    private var promptVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RouterDashApp(onEnableBiometric = { enableBiometricLock() }) }
    }

    override fun onStart() {
        super.onStart()
        if (securityPrefs.getBoolean("biometric_enabled", false) && shouldAuthenticate) {
            authenticateBiometric()
        }
    }

    override fun onStop() {
        super.onStop()
        if (securityPrefs.getBoolean("biometric_enabled", false)) shouldAuthenticate = true
    }

    private fun enableBiometricLock() {
        val manager = BiometricManager.from(this)
        val status = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            val message = when (status) {
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Сначала добавь отпечаток пальца в настройках телефона."
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "На устройстве нет поддерживаемого биометрического датчика."
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Датчик отпечатка сейчас недоступен."
                else -> "Биометрическая авторизация недоступна (код $status)."
            }
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        showBiometricPrompt(
            title = "Включить вход по отпечатку",
            subtitle = "Подтверди личность, чтобы включить защиту RouterDash",
            onSuccess = {
                securityPrefs.edit().putBoolean("biometric_enabled", true).apply()
                shouldAuthenticate = false
                android.widget.Toast.makeText(this, "Защита отпечатком включена", android.widget.Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun authenticateBiometric() {
        if (promptVisible) return
        showBiometricPrompt(
            title = "Разблокировать RouterDash",
            subtitle = "Используй отпечаток пальца для продолжения",
            onSuccess = { shouldAuthenticate = false },
            onFailure = { finish() }
        )
    }

    private fun showBiometricPrompt(
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit = {}
    ) {
        if (promptVisible) return
        promptVisible = true
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                promptVisible = false
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                promptVisible = false
                if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                    errorCode != BiometricPrompt.ERROR_USER_CANCELED) onFailure()
                else if (securityPrefs.getBoolean("biometric_enabled", false)) finish()
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Отмена")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info)
    }
}

private enum class Screen(val title: String) {
    OVERVIEW("Обзор"), NETWORK("Сеть"), DEVICES("Устройства"), STORAGE("Диски"), SERVICES("Службы"), LOGS("Журнал")
}
private data class TrafficPoint(val rx: Long, val tx: Long)
private data class Snapshot(
    val board: JSONObject? = null, val info: JSONObject? = null,
    val network: JSONObject? = null, val wireless: JSONObject? = null, val devices: JSONObject? = null,
    val services: JSONObject? = null, val logs: JSONObject? = null,
    val storage: JSONArray? = null,
    val cpuRaw: String? = null, val networkDevices: JSONObject? = null,
    val cpuPercent: Int? = null, val rxRate: Long? = null, val txRate: Long? = null,
    val trafficHistory: List<TrafficPoint> = emptyList(),
    val cpuHistory: List<Int> = emptyList(),
    val sessionRxBytes: Long = 0L,
    val sessionTxBytes: Long = 0L
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouterDashApp(onEnableBiometric: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("routerdash", 0) }
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(prefs.getString("url", "http://192.168.1.1") ?: "") }
    var username by rememberSaveable { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf(Screen.OVERVIEW) }
    var snapshot by remember { mutableStateOf(Snapshot()) }
    var previousCpu by remember { mutableStateOf<LongArray?>(null) }
    var previousTraffic by remember { mutableStateOf<LongArray?>(null) }
    var previousTrafficAt by remember { mutableLongStateOf(0L) }
    var api by remember { mutableStateOf<RouterApi?>(null) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        error = if (granted) "Разрешение получено. Нажми «Подключиться» ещё раз."
                else "Разреши доступ к локальной сети для связи с роутером."
    }

    fun refresh(screen: Screen = selected) {
        val client = api ?: return
        scope.launch {
            loading = true
            error = null
            try {
                snapshot = withContext(Dispatchers.IO) {
                    when (screen) {
                        Screen.OVERVIEW -> snapshot.copy(
                            board = client.call("system", "board"),
                            info = client.call("system", "info"),
                            cpuRaw = readUbusFile(client, "/proc/stat"),
                            networkDevices = client.call("luci-rpc", "getNetworkDevices")
                        )
                        Screen.NETWORK -> {
                            val ifaceDump = client.call("network.interface", "dump")
                            val radios = client.call("luci-rpc", "getWirelessDevices")
                            // Enrich each AP interface with associated stations, matching LuCI's iwinfo calls.
                            radios.keys().asSequence().toList().forEach { radioKey ->
                                val radio = radios.optJSONObject(radioKey) ?: return@forEach
                                val interfaces = radio.optJSONArray("interfaces") ?: return@forEach
                                for (index in 0 until interfaces.length()) {
                                    val wifiIface = interfaces.optJSONObject(index) ?: continue
                                    val ifname = wifiIface.optString("ifname")
                                    if (ifname.isBlank()) continue
                                    runCatching {
                                        val assoc = client.call("iwinfo", "assoclist", JSONObject().put("device", ifname))
                                        wifiIface.put("associations", assoc.optJSONArray("results") ?: JSONArray())
                                    }.onFailure {
                                        wifiIface.put("associations", JSONArray())
                                        wifiIface.put("association_error", it.message ?: "Недоступно")
                                    }
                                }
                            }
                            snapshot.copy(network = ifaceDump, wireless = radios)
                        }
                        Screen.DEVICES -> {
                            val leases = client.call("luci-rpc", "getDHCPLeases")
                            val arpRaw = readUbusFile(client, "/proc/net/arp").orEmpty()
                            val arp = JSONArray()
                            arpRaw.lineSequence().drop(1).forEach { row ->
                                val fields = row.trim().split(Regex("\\s+"))
                                if (fields.size >= 6 && fields[2] == "0x2") {
                                    arp.put(JSONObject().put("ipaddr", fields[0]).put("macaddr", fields[3]).put("device", fields[5]))
                                }
                            }
                            val combined = JSONObject().put("dhcp_leases", leases.optJSONArray("dhcp_leases") ?: JSONArray())
                                .put("arp_entries", arp)
                            snapshot.copy(devices = combined)
                        }
                        Screen.STORAGE -> snapshot.copy(storage = client.callArray("luci", "getMountPoints"))
                        Screen.SERVICES -> snapshot.copy(services = client.call("rc", "list"))
                        Screen.LOGS -> snapshot.copy(logs = client.call("log", "read", JSONObject().put("lines", 300)))
                    }
                }
                if (screen == Screen.OVERVIEW) {
                    val ticks = parseCpuTicks(snapshot.cpuRaw)
                    val oldTicks = previousCpu
                    val cpu = if (ticks != null && oldTicks != null && ticks[0] > oldTicks[0]) {
                        val totalDelta = ticks[0] - oldTicks[0]
                        val idleDelta = ticks[1] - oldTicks[1]
                        (((totalDelta - idleDelta).coerceAtLeast(0L) * 100L) / totalDelta).toInt().coerceIn(0, 100)
                    } else null
                    if (ticks != null) previousCpu = ticks

                    val devices = snapshot.networkDevices
                    val wanStats = devices?.optJSONObject("eth1")?.optJSONObject("stats")
                    val counters = if (wanStats != null) longArrayOf(
                        wanStats.optLong("rx_bytes"), wanStats.optLong("tx_bytes")
                    ) else null
                    val now = System.currentTimeMillis()
                    val oldCounters = previousTraffic
                    val dt = (now - previousTrafficAt).coerceAtLeast(1L)
                    val rx = if (counters != null && oldCounters != null && counters[0] >= oldCounters[0])
                        ((counters[0] - oldCounters[0]) * 1000L / dt) else null
                    val tx = if (counters != null && oldCounters != null && counters[1] >= oldCounters[1])
                        ((counters[1] - oldCounters[1]) * 1000L / dt) else null
                    if (counters != null) {
                        previousTraffic = counters
                        previousTrafficAt = now
                    }
                    if (cpu != null || rx != null || tx != null) {
                        val history = if (rx != null && tx != null)
                            (snapshot.trafficHistory + TrafficPoint(rx, tx)).takeLast(36)
                        else snapshot.trafficHistory
                        val cpuHistory = if (cpu != null)
                            (snapshot.cpuHistory + cpu).takeLast(48) else snapshot.cpuHistory
                        val rxDelta = if (counters != null && oldCounters != null && counters[0] >= oldCounters[0])
                            counters[0] - oldCounters[0] else 0L
                        val txDelta = if (counters != null && oldCounters != null && counters[1] >= oldCounters[1])
                            counters[1] - oldCounters[1] else 0L
                        snapshot = snapshot.copy(cpuPercent = cpu ?: snapshot.cpuPercent,
                            rxRate = rx, txRate = tx, trafficHistory = history, cpuHistory = cpuHistory,
                            sessionRxBytes = snapshot.sessionRxBytes + rxDelta,
                            sessionTxBytes = snapshot.sessionTxBytes + txDelta)
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "Ошибка получения данных"
                if (error?.contains("401") == true || error?.contains("403") == true) connected = false
            } finally { loading = false }
        }
    }

    fun connect() {
        val parsed = runCatching { URI(url.trim()) }.getOrNull()
        if (parsed?.host.isNullOrBlank() || parsed?.scheme !in listOf("http", "https") || parsed?.userInfo != null) {
            error = "Укажи корректный адрес, например http://192.168.1.1"; return
        }
        if (Build.VERSION.SDK_INT >= 37 &&
            context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permission.launch("android.permission.ACCESS_LOCAL_NETWORK")
            error = "После выдачи разрешения нажми «Подключиться» ещё раз"; return
        }
        scope.launch {
            loading = true; error = null
            try {
                val newApi = RouterApi(url.trim().trimEnd('/'))
                withContext(Dispatchers.IO) { newApi.login(username.trim(), password) }
                api = newApi
                prefs.edit().putString("url", newApi.baseUrl).apply()
                connected = true
                password = ""
                refresh(Screen.OVERVIEW)
            } catch (e: Exception) {
                error = e.message ?: "Не удалось подключиться"; connected = false
            } finally { loading = false }
        }
    }

    LaunchedEffect(connected, selected) {
        while (connected) {
            refresh(selected)
            delay(5000)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFFFF9A52),
        secondary = androidx.compose.ui.graphics.Color(0xFF69C7F0),
        background = androidx.compose.ui.graphics.Color(0xFF171B22),
        surface = androidx.compose.ui.graphics.Color(0xFF222832),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF2B323D),
        onBackground = androidx.compose.ui.graphics.Color(0xFFF4F6F8),
        onSurface = androidx.compose.ui.graphics.Color(0xFFF4F6F8),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF9AA5B1)
    )) {
        if (!connected) {
            LoginScreen(url, { url = it }, username, { username = it }, password, { password = it },
                loading, error, ::connect)
        } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("RouterDash", fontWeight = FontWeight.Bold)
                                Text(url, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        actions = {
                            IconButton(onClick = onEnableBiometric) {
                                Icon(Icons.Outlined.Fingerprint, contentDescription = "Включить вход по отпечатку")
                            }
                            if (loading) CircularProgressIndicator(Modifier.size(20.dp))
                            IconButton(onClick = { refresh() }) { Text("↻", style = MaterialTheme.typography.headlineSmall) }
                            IconButton(onClick = { connected = false; api = null }) {
                                Icon(Icons.Outlined.Settings, contentDescription = "Отключиться")
                            }
                        }
                    )
                },
                bottomBar = {
                    NavigationBar {
                        val entries = listOf(
                            Screen.OVERVIEW to Icons.Outlined.Dashboard,
                            Screen.NETWORK to Icons.Outlined.Lan,
                            Screen.DEVICES to Icons.Outlined.Devices,
                            Screen.STORAGE to Icons.Outlined.Storage,
                            Screen.SERVICES to Icons.Outlined.Settings,
                            Screen.LOGS to Icons.Outlined.Article
                        )
                        entries.forEach { (screen, icon) ->
                            NavigationBarItem(
                                selected = selected == screen,
                                onClick = { selected = screen; refresh(screen) },
                                icon = { Icon(icon, contentDescription = screen.title) },
                                label = { Text(screen.title, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                    if (error != null) {
                        ElevatedCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(error!!, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    when (selected) {
                        Screen.OVERVIEW -> OverviewScreen(snapshot)
                        Screen.NETWORK -> NetworkScreen(snapshot.network, snapshot.wireless)
                        Screen.DEVICES -> DevicesScreen(snapshot.devices, snapshot.wireless)
                        Screen.STORAGE -> StorageScreen(snapshot.storage) { refresh(Screen.STORAGE) }
                        Screen.SERVICES -> ServicesScreen(snapshot.services, api) { refresh(Screen.SERVICES) }
                        Screen.LOGS -> LogsScreen(snapshot.logs)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(
    url: String, onUrl: (String) -> Unit, username: String, onUsername: (String) -> Unit,
    password: String, onPassword: (String) -> Unit, loading: Boolean, error: String?, onConnect: () -> Unit
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth().widthIn(max = 480.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("RouterDash", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Нативное управление OpenWrt", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(url, onUrl, Modifier.fillMaxWidth(), label = { Text("Адрес роутера") }, singleLine = true)
                OutlinedTextField(username, onUsername, Modifier.fillMaxWidth(), label = { Text("Пользователь") }, singleLine = true)
                OutlinedTextField(password, onPassword, Modifier.fillMaxWidth(), label = { Text("Пароль LuCI") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation())
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                Button(onClick = onConnect, Modifier.fillMaxWidth(), enabled = !loading) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Подключиться")
                }
                Text("Пароль используется только для входа и не сохраняется на устройстве.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OverviewScreen(s: Snapshot) {
    val board = s.board
    val info = s.info
    val mem = info?.optJSONObject("memory")
    val total = mem?.optLong("total") ?: 0L
    val available = mem?.optLong("available") ?: 0L
    val used = (total - available).coerceAtLeast(0L)
    val ramPercent = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
    val load = info?.optJSONArray("load")?.optLong(0)?.let { String.format("%.2f", it / 1000.0) } ?: "—"
    val uptime = info?.optLong("uptime")?.let { duration(it) } ?: "—"
    val model = board?.optString("model", "OpenWrt") ?: "OpenWrt"
    val hostname = board?.optString("hostname", "RouterDash") ?: "RouterDash"

    LazyColumn(contentPadding = PaddingValues(top = 10.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(52.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Router, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(27.dp))
                    }
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Панель управления", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(hostname, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(model, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.NotificationsNone, contentDescription = "Уведомления",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(25.dp))
            }
        }
        item {
            InfoCard("CPU Usage") {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        s.cpuPercent?.let { "$it%" } ?: "Ожидание замера",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                    Text(
                        "Загрузка процессора",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                CpuHistoryChart(s.cpuHistory)
                Text(if (s.cpuHistory.isEmpty()) "Ожидаем данные процессора · опрос каждые 5 секунд" else "Общая загрузка CPU · обновление каждые 5 секунд",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        item { NetworkInformationCard(s) }
        item {
            InfoCard("Internal Storage · RAM") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Используется", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall)
                        Text(if (total > 0) formatBytes(used) else "—",
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Всего", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall)
                        Text(if (total > 0) formatBytes(total) else "—",
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (total > 0) {
                    LinearProgressIndicator(progress = { ramPercent }, Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    Text("Доступно: ${formatBytes(available)} · ${(ramPercent * 100).toInt()}% занято",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            InfoCard("System Information") {
                InfoLine("Load average · 1 мин", load)
                InfoLine("Время работы", uptime)
                InfoLine("OpenWrt", board?.optJSONObject("release")?.optString("version", "—") ?: "—")
                InfoLine("Ядро", board?.optString("kernel", "—") ?: "—")
                InfoLine("Архитектура", board?.optString("system", "—") ?: "—")
            }
        }
    }
}

@Composable
private fun CpuHistoryChart(history: List<Int>) {
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(178.dp)) {
        val left = 34.dp.toPx()
        val right = 4.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = 20.dp.toPx()
        val chartWidth = size.width - left - right
        val chartHeight = size.height - top - bottom
        listOf(0, 25, 50, 75, 100).forEach { tick ->
            val y = top + chartHeight * (1f - tick / 100f)
            drawLine(grid, androidx.compose.ui.geometry.Offset(left, y),
                androidx.compose.ui.geometry.Offset(size.width - right, y), 1.dp.toPx())
            // Grid tick; labels are omitted to keep the chart independent of Android Canvas APIs.
        }
        if (history.isEmpty()) return@Canvas
        val path = androidx.compose.ui.graphics.Path()
        val fill = androidx.compose.ui.graphics.Path()
        history.forEachIndexed { index, value ->
            val x = left + if (history.size == 1) chartWidth else chartWidth * index / (history.size - 1f)
            val y = top + chartHeight * (1f - value.coerceIn(0, 100) / 100f)
            if (index == 0) { path.moveTo(x, y); fill.moveTo(x, top + chartHeight); fill.lineTo(x, y) }
            else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        val lastX = left + if (history.size == 1) chartWidth else chartWidth
        fill.lineTo(lastX, top + chartHeight)
        fill.close()
        drawPath(fill, androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(accent.copy(alpha = 0.42f), accent.copy(alpha = 0.02f)),
            startY = top, endY = top + chartHeight))
        drawPath(path, accent, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    }
}

@Composable
private fun NetworkInformationCard(s: Snapshot) {
    val rx = s.sessionRxBytes
    val tx = s.sessionTxBytes
    val total = rx + tx
    val rxColor = MaterialTheme.colorScheme.primary
    val txColor = androidx.compose.ui.graphics.Color(0xFF31C879)
    InfoCard("Network Information") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(formatBytes(rx), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                    Text("↓ Получено за сеанс", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(formatBytes(tx), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                    Text("↑ Передано за сеанс", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val trackColor = MaterialTheme.colorScheme.surfaceVariant
            Box(Modifier.size(142.dp), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val stroke = 17.dp.toPx()
                    drawArc(trackColor, -90f, 360f, false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    if (total > 0L) {
                        val rxSweep = 360f * rx.toFloat() / total.toFloat()
                        drawArc(rxColor, -90f, rxSweep, false,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round))
                        drawArc(txColor, -90f + rxSweep, 360f - rxSweep, false,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(formatBytes(total), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Text("Всего", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("↓ ${s.rxRate?.let { formatRate(it) } ?: "—"}",
                color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge)
            Text("↑ ${s.txRate?.let { formatRate(it) } ?: "—"}",
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        Text("Счётчики с момента подключения приложения; не являются статистикой за месяц",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun NetworkScreen(result: JSONObject?, wireless: JSONObject?) {
    val list = result?.optJSONArray("interface")
    val radios = wireless?.keys()?.asSequence()?.mapNotNull { key -> wireless.optJSONObject(key)?.let { key to it } }?.toList() ?: emptyList()
    LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Сетевые интерфейсы", style = MaterialTheme.typography.headlineSmall) }
        if (radios.isNotEmpty()) {
            item { Text("Wi-Fi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(radios) { (radioName, radio) ->
                InfoCard(radioName) {
                    InfoLine("Состояние", if (radio.optBoolean("up")) "Включён" else "Выключен")
                    InfoLine("Канал", radio.optJSONObject("config")?.optString("channel", "—") ?: "—")
                    val interfaces = radio.optJSONArray("interfaces")
                    if (interfaces != null) {
                        for (idx in 0 until interfaces.length()) {
                            val iface = interfaces.optJSONObject(idx) ?: JSONObject()
                            val cfg = iface.optJSONObject("config") ?: JSONObject()
                            val iw = iface.optJSONObject("iwinfo") ?: JSONObject()
                            InfoLine("SSID", cfg.optString("ssid", "—"))
                            InfoLine("Интерфейс", iface.optString("ifname", "—"))
                            InfoLine("Сигнал", if (iw.has("signal")) "${iw.optInt("signal")} dBm" else "—")
                            InfoLine("Шифрование", cfg.optString("encryption", "—"))
                            val stations = iface.optJSONArray("associations")
                            Text("Подключённые клиенты · ${stations?.length() ?: 0}",
                                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            if (stations != null && stations.length() > 0) {
                                for (stationIndex in 0 until stations.length()) {
                                    val station = stations.optJSONObject(stationIndex) ?: JSONObject()
                                    val mac = station.optString("mac", "—")
                                    val signal = if (station.has("signal")) "${station.optInt("signal")} dBm" else "сигнал неизвестен"
                                    val connectedSeconds = station.optLong("connected_time", -1L)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(mac, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                            Text(signal + if (connectedSeconds >= 0) " · ${duration(connectedSeconds)}" else "",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(formatBytes(station.optJSONObject("rx")?.optLong("bytes") ?: 0L) + " ↓",
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            } else {
                                Text(if (iface.has("association_error")) "Список клиентов недоступен: ${iface.optString("association_error")}" else "Нет подключённых клиентов",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        if (list == null || list.length() == 0) item { EmptyState("Нет данных о сетевых интерфейсах") }
        else items((0 until list.length()).map { list.optJSONObject(it) ?: JSONObject() }) { entry ->
            InfoCard(entry.optString("interface", "Интерфейс")) {
                InfoLine("Состояние", if (entry.optBoolean("up")) "Подключён" else "Не подключён")
                InfoLine("Протокол", entry.optString("proto", "—"))
                InfoLine("Устройство", entry.optString("device", "—"))
                val ip = entry.optJSONArray("ipv4-address")?.optJSONObject(0)?.optString("address", "—") ?: "—"
                InfoLine("IPv4", ip)
            }
        }
    }
}

@Composable
private fun DevicesScreen(result: JSONObject?, wireless: JSONObject?) {
    val leases = result?.optJSONArray("dhcp_leases")
    val arp = result?.optJSONArray("arp_entries")
    data class Device(
        val ip: String, val mac: String, val hostname: String, val source: String,
        val expires: String, val signal: String = "", val connected: String = ""
    )
    val devices = remember(result, wireless) {
        val merged = linkedMapOf<String, Device>()
        if (leases != null) for (i in 0 until leases.length()) {
            val item = leases.optJSONObject(i) ?: continue
            val mac = item.optString("macaddr").uppercase()
            val ip = item.optString("ipaddr")
            val key = mac.ifBlank { ip }
            if (key.isNotBlank()) merged[key] = Device(ip, mac, item.optString("hostname", "Без имени"), "DHCP", item.optString("expires", "—"))
        }
        if (arp != null) for (i in 0 until arp.length()) {
            val item = arp.optJSONObject(i) ?: continue
            val mac = item.optString("macaddr").uppercase()
            val ip = item.optString("ipaddr")
            val key = mac.ifBlank { ip }
            if (key.isNotBlank() && !merged.containsKey(key))
                merged[key] = Device(ip, mac, "Неизвестное устройство", "ARP", "—")
        }
        wireless?.keys()?.asSequence()?.forEach { radioKey ->
            val radio = wireless.optJSONObject(radioKey) ?: return@forEach
            val interfaces = radio.optJSONArray("interfaces") ?: return@forEach
            for (interfaceIndex in 0 until interfaces.length()) {
                val iface = interfaces.optJSONObject(interfaceIndex) ?: continue
                val stations = iface.optJSONArray("associations") ?: continue
                for (stationIndex in 0 until stations.length()) {
                    val station = stations.optJSONObject(stationIndex) ?: continue
                    val mac = station.optString("mac").uppercase()
                    if (mac.isBlank()) continue
                    val existing = merged[mac]
                    val signal = if (station.has("signal")) "${station.optInt("signal")} dBm" else ""
                    val connectedSeconds = station.optLong("connected_time", -1L)
                    val connected = if (connectedSeconds >= 0L) duration(connectedSeconds) else ""
                    merged[mac] = if (existing != null) existing.copy(
                        source = (existing.source.split(" · ") + "Wi-Fi").distinct().joinToString(" · "),
                        signal = signal, connected = connected
                    ) else Device("", mac, "Wi-Fi клиент", "Wi-Fi", "—", signal, connected)
                }
            }
        }
        merged.values.sortedWith(compareBy<Device> {
            it.hostname == "Неизвестное устройство" || it.hostname == "Wi-Fi клиент"
        }.thenBy { it.hostname })
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Устройства", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Surface(color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f), shape = MaterialTheme.shapes.small) {
                    Text("${devices.size} в сети", Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text("DHCP, ARP и подключённые Wi-Fi-клиенты объединены по MAC-адресу.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (result == null) item { EmptyState("Не удалось получить список устройств.") }
        else if (devices.isEmpty()) item { EmptyState("Активных DHCP-, ARP- и Wi-Fi-записей нет.") }
        else items(devices, key = { it.mac.ifBlank { it.ip } }) { device ->
            InfoCard(device.hostname) {
                InfoLine("IP-адрес", device.ip.ifBlank { "—" })
                InfoLine("MAC-адрес", device.mac.ifBlank { "—" })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(device.source, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    if (device.expires != "—") Text("Аренда: ${device.expires}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
                if (device.signal.isNotBlank()) InfoLine("Wi-Fi сигнал", device.signal)
                if (device.connected.isNotBlank()) InfoLine("В сети", device.connected)
            }
        }
    }
}
@Composable
private fun StorageScreen(mounts: JSONArray?, onRefresh: () -> Unit) {
    data class Mount(val path: String, val device: String, val size: Long, val available: Long) {
        val used: Long get() = (size - available).coerceAtLeast(0L)
        val percent: Int get() = if (size > 0L) ((used * 100L) / size).toInt().coerceIn(0, 100) else 0
    }
    val entries = remember(mounts) {
        if (mounts == null) emptyList() else (0 until mounts.length()).mapNotNull { index ->
            val item = mounts.optJSONObject(index) ?: return@mapNotNull null
            val size = item.optLong("size")
            val available = item.optLong("avail")
            if (size <= 0L) null else Mount(
                item.optString("mount", "—"),
                item.optString("device", "—"),
                size,
                available.coerceIn(0L, size)
            )
        }.sortedBy { it.path }
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Хранилища роутера", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = "Обновить") }
            }
            Text("Данные получены через luci.getMountPoints. Размеры отображаются для смонтированных файловых систем.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (mounts == null) item { EmptyState("Нет данных о файловых системах. Проверь доступ к luci.getMountPoints.") }
        else if (entries.isEmpty()) item { EmptyState("Нет смонтированных файловых систем с доступной статистикой.") }
        else items(entries, key = { it.path + it.device }) { mount ->
            val barColor = when {
                mount.percent >= 90 -> MaterialTheme.colorScheme.error
                mount.percent >= 75 -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.primary
            }
            InfoCard(mount.path) {
                InfoLine("Устройство", mount.device)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Занято", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${formatBytes(mount.used)} · ${mount.percent}%", fontWeight = FontWeight.SemiBold)
                }
                LinearProgressIndicator(
                    progress = { mount.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = barColor
                )
                InfoLine("Свободно", formatBytes(mount.available))
                InfoLine("Всего", formatBytes(mount.size))
            }
        }
    }
}

@Composable
private fun ServicesScreen(result: JSONObject?, api: RouterApi?, onRefresh: () -> Unit) {
    val names = result?.keys()?.asSequence()?.toList()?.sorted() ?: emptyList()
    val scope = rememberCoroutineScope()
    var busyService by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<Pair<String, String>?>(null) }

    pendingAction?.let { (name, action) ->
        val verb = when (action) { "start" -> "запустить"; "stop" -> "остановить"; else -> "перезапустить" }
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text("Управление службой") },
            text = { Text("Действительно $verb службу «$name»?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingAction = null
                    scope.launch {
                        busyService = name
                        message = null
                        try {
                            withContext(Dispatchers.IO) {
                                checkNotNull(api) { "Нет подключения к роутеру" }
                                    .call("rc", "init", JSONObject().put("name", name).put("action", action))
                            }
                            message = "Команда отправлена: $name · $action"
                            delay(900)
                            onRefresh()
                        } catch (e: Exception) {
                            message = "Не удалось выполнить $action для $name: ${e.message}"
                        } finally { busyService = null }
                    }
                }) { Text("Выполнить") }
            },
            dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("Отмена") } }
        )
    }

    LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Службы OpenWrt", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                IconButton(onClick = onRefresh, enabled = busyService == null) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Обновить список")
                }
            }
            if (message != null) Text(message!!, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (names.isEmpty()) item { EmptyState("Нет данных о службах. Проверь доступ к ubus rc.list.") }
        else items(names) { name ->
            val service = result?.optJSONObject(name) ?: JSONObject()
            val hasRuntimeState = service.has("running")
            val running = service.optBoolean("running")
            InfoCard(name) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Состояние", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (!hasRuntimeState) "Неизвестно" else if (running) "Работает" else "Остановлена",
                        color = if (running) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold)
                }
                InfoLine("Автозапуск", if (service.optBoolean("enabled")) "Включён" else "Выключен")
                if (hasRuntimeState) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pendingAction = name to "start" },
                            enabled = busyService == null && !running, modifier = Modifier.weight(1f)) {
                            Text("Запустить")
                        }
                        OutlinedButton(onClick = { pendingAction = name to "stop" },
                            enabled = busyService == null && running, modifier = Modifier.weight(1f)) {
                            Text("Остановить")
                        }
                        OutlinedButton(onClick = { pendingAction = name to "restart" },
                            enabled = busyService == null && running, modifier = Modifier.weight(1f)) {
                            Text("Перезапуск")
                        }
                    }
                } else {
                    Text("Управление недоступно: rc.list не сообщил состояние процесса.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                if (busyService == name) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun LogsScreen(result: JSONObject?) {
    val list = result?.optJSONArray("log") ?: result?.optJSONArray("entries")
    var query by rememberSaveable { mutableStateOf("") }
    var selectedLevel by rememberSaveable { mutableStateOf("Все") }
    val entries = remember(result, query, selectedLevel) {
        val parsed = if (list == null) emptyList() else (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        parsed.filter { entry ->
            val message = entry.optString("msg", entry.optString("message", ""))
            val priority = entry.optString("priority", entry.optString("level", ""))
            val matchesText = query.isBlank() || message.contains(query, ignoreCase = true)
            val normalized = priority.lowercase()
            val matchesLevel = selectedLevel == "Все" ||
                (selectedLevel == "Ошибка" && normalized in listOf("err", "error", "crit", "alert", "emerg", "3", "2", "1", "0")) ||
                (selectedLevel == "Предупреждение" && normalized in listOf("warn", "warning", "4"))
            matchesText && matchesLevel
        }.reversed()
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Системный журнал", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, label = { Text("Поиск по сообщению") }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                listOf("Все", "Ошибка", "Предупреждение").forEach { level ->
                    FilterChip(selected = selectedLevel == level, onClick = { selectedLevel = level }, label = { Text(level) })
                }
            }
        }
        if (result == null) item { EmptyState("Не удалось получить журнал. Проверь доступ к ubus log.read.") }
        else if (entries.isEmpty()) item { EmptyState("Нет записей, соответствующих фильтру.") }
        else items(entries) { line ->
            val message = line.optString("msg", line.optString("message", "—"))
            val timestamp = line.optString("time", line.optString("timestamp", ""))
            val priority = line.optString("priority", line.optString("level", ""))
            InfoCard(priority.ifBlank { "Системное сообщение" }) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (timestamp.isNotBlank()) Text(timestamp, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
@Composable
private fun GaugeCard(title: String, value: String, progress: Float, caption: String,
                      modifier: Modifier = Modifier, accent: androidx.compose.ui.graphics.Color) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    ElevatedCard(modifier, colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val stroke = 10.dp.toPx()
                    drawArc(color = trackColor, startAngle = -90f, sweepAngle = 360f,
                        useCenter = false, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    drawArc(color = accent, startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0f, 1f),
                        useCenter = false, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TrafficCard(history: List<TrafficPoint>, rx: Long?, tx: Long?) {
    val rxColor = MaterialTheme.colorScheme.secondary
    val txColor = MaterialTheme.colorScheme.primary
    InfoCard("Интернет · WAN (eth1)") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("↓ Приём", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall)
                Text(rx?.let { formatRate(it) } ?: "Сбор данных…",
                    color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("↑ Передача", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall)
                Text(tx?.let { formatRate(it) } ?: "Сбор данных…",
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(112.dp)) {
            if (history.size < 2) return@Canvas
            val maxValue = (history.maxOf { maxOf(it.rx, it.tx) }).coerceAtLeast(1L).toFloat()
            val step = size.width / (history.size - 1).toFloat()
            fun drawSeries(selector: (TrafficPoint) -> Long, color: androidx.compose.ui.graphics.Color) {
                val path = androidx.compose.ui.graphics.Path()
                history.forEachIndexed { index, point ->
                    val x = index * step
                    val y = size.height - (selector(point).toFloat() / maxValue) * (size.height - 8.dp.toPx()) - 4.dp.toPx()
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
            }
            drawSeries({ it.rx }, rxColor)
            drawSeries({ it.tx }, txColor)
        }
        Text(if (history.size < 2) "График появится после второго замера" else "Скорость по интервалам опроса · 5 секунд",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun InfoCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EmptyState(message: String) {
    Text(message, Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private class RouterApi(val baseUrl: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).build()
    private var session = ""
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    fun login(username: String, password: String) {
        // OpenWrt's standard JSON-RPC endpoint accepts session.login on /ubus.
        // The all-zero session ID is used only to start an unauthenticated session.
        val params = JSONArray()
            .put("00000000000000000000000000000000")
            .put("session")
            .put("login")
            .put(JSONObject().put("username", username).put("password", password))
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "call")
            .put("params", params)
            .toString()
        val root = JSONObject(post("/ubus", body))
        if (root.has("error")) {
            val message = root.optJSONObject("error")?.optString("message")
            throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "Ошибка авторизации ubus")
        }
        val result = root.optJSONArray("result")
            ?: throw IllegalStateException("Роутер вернул неожиданный ответ на авторизацию")
        val status = result.optInt(0, -1)
        if (status != 0) {
            throw IllegalStateException("OpenWrt отклонил вход (код ubus: $status). Проверь логин и пароль.")
        }
        session = result.optJSONObject(1)?.optString("ubus_rpc_session").orEmpty()
        if (session.isBlank() || session == "null") {
            throw IllegalStateException("OpenWrt не вернул токен сессии ubus")
        }
    }

    fun callArray(objectName: String, method: String, args: JSONObject = JSONObject()): JSONArray {
        check(session.isNotBlank()) { "Нет сессии LuCI. Подключись заново." }
        val params = JSONArray().put(session).put(objectName).put(method).put(args)
        val body = JSONObject().put("jsonrpc", "2.0").put("id", System.currentTimeMillis())
            .put("method", "call").put("params", params).toString()
        val root = JSONObject(post("/ubus", body))
        if (root.has("error")) throw IllegalStateException(root.optJSONObject("error")?.optString("message") ?: "Ошибка ubus")
        val result = root.optJSONArray("result")
            ?: throw IllegalStateException("Роутер вернул неожиданный ответ для $objectName.$method")
        val status = result.optInt(0, -1)
        if (status != 0) throw IllegalStateException("Ошибка ubus $objectName.$method: код $status")
        return result.optJSONArray(1)
            ?: throw IllegalStateException("$objectName.$method вернул не список")
    }

    fun call(objectName: String, method: String, args: JSONObject = JSONObject()): JSONObject {
        check(session.isNotBlank()) { "Нет сессии LuCI. Подключись заново." }
        val params = JSONArray().put(session).put(objectName).put(method).put(args)
        val body = JSONObject().put("jsonrpc", "2.0").put("id", System.currentTimeMillis())
            .put("method", "call").put("params", params).toString()
        val root = JSONObject(post("/ubus", body))
        if (root.has("error")) throw IllegalStateException(root.optJSONObject("error")?.optString("message") ?: "Ошибка ubus")
        val result = root.opt("result")
        if (result is JSONArray) {
            if (result.length() > 0 && result.optInt(0, 0) != 0)
                throw IllegalStateException("Ошибка ubus: код " + result.opt(0))
            return result.optJSONObject(1) ?: JSONObject()
        }
        return result as? JSONObject ?: JSONObject()
    }

    private fun post(path: String, body: String): String {
        val request = Request.Builder().url(baseUrl + path)
            .post(body.toRequestBody(mediaType)).header("X-Requested-With", "XMLHttpRequest").build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = text.replace(Regex("\\s+"), " ").take(180)
                val suffix = if (detail.isBlank()) "" else " — $detail"
                throw IllegalStateException("HTTP " + response.code + " на " + path + suffix)
            }
            return text
        }
    }
}

private fun readUbusFile(api: RouterApi, path: String): String? {
    val response = api.call("file", "read", JSONObject().put("path", path))
    val encoded = response.optString("data")
    if (encoded.isBlank() || encoded == "null") return null
    return runCatching {
        String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), Charsets.UTF_8)
    }.getOrElse { encoded }
}

private fun parseCpuTicks(raw: String?): LongArray? {
    val line = raw?.lineSequence()?.firstOrNull { it.startsWith("cpu ") } ?: return null
    val values = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
    if (values.size < 5) return null
    val total = values.sum()
    val idle = values[3] + (values.getOrNull(4) ?: 0L)
    return longArrayOf(total, idle)
}

private fun formatRate(bytesPerSecond: Long): String {
    if (bytesPerSecond < 1024) return "$bytesPerSecond Б/с"
    return formatBytes(bytesPerSecond) + "/с"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return bytes.toString() + " Б"
    val units = listOf("КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble()
    var index = -1
    do { value /= 1024.0; index++ } while (value >= 1024 && index < units.lastIndex)
    return String.format("%.1f %s", value, units[index])
}

private fun duration(seconds: Long): String {
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    return if (days > 0) days.toString() + "д " + hours + "ч" else hours.toString() + "ч " + minutes + "м"
}

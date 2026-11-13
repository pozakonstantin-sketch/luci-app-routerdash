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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
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
import com.jcraft.jsch.JSch
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream

class MainActivity : FragmentActivity() {
    private val securityPrefs by lazy { getSharedPreferences("routerdash_security", MODE_PRIVATE) }
    private val biometricUnlockSignal = androidx.compose.runtime.mutableIntStateOf(0)
    private var shouldAuthenticate = true
    private var promptVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RouterDashApp(biometricUnlockSignal.intValue, onEnableBiometric = { onEnabled -> enableBiometricLock(onEnabled) }) }
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

    private fun enableBiometricLock(onEnabled: () -> Unit) {
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
                onEnabled()
                android.widget.Toast.makeText(this, "Защита отпечатком включена", android.widget.Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun authenticateBiometric() {
        if (promptVisible) return
        showBiometricPrompt(
            title = "Разблокировать RouterDash",
            subtitle = "Используй отпечаток пальца для продолжения",
            onSuccess = { shouldAuthenticate = false; biometricUnlockSignal.intValue++ },
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
    OVERVIEW("Обзор"), NETWORK("Сеть"), DEVICES("Устройства"), STORAGE("Диски"), PACKAGES("Пакеты"), DOWNLOADS("Загрузки"), SERVICES("Службы"), VPN("VPN"), FIREWALL("Правила"), SSH("SSH-терминал"), LOGS("Журнал"), SETTINGS("Настройки")
}
private data class TrafficPoint(val rx: Long, val tx: Long)
private data class Snapshot(
    val board: JSONObject? = null, val info: JSONObject? = null,
    val network: JSONObject? = null, val wireless: JSONObject? = null, val devices: JSONObject? = null,
    val services: JSONObject? = null, val logs: JSONObject? = null,
    val storage: JSONArray? = null, val storageError: String? = null,
    val packages: JSONObject? = null, val downloads: JSONObject? = null,
    val vpnStatus: String? = null, val diskDetails: String? = null, val firewallRules: String? = null,
    val cpuRaw: String? = null, val networkDevices: JSONObject? = null,
    val processes: String? = null, val logsError: String? = null,
    val cpuPercent: Int? = null, val rxRate: Long? = null, val txRate: Long? = null,
    val trafficHistory: List<TrafficPoint> = emptyList(),
    val cpuHistory: List<Int> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouterDashApp(biometricUnlockSignal: Int, onEnableBiometric: (() -> Unit) -> Unit) {
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
    var refreshIntervalSeconds by rememberSaveable { mutableIntStateOf(prefs.getInt("refresh_interval", 5).coerceIn(3, 60)) }
    var biometricEnabled by remember { mutableStateOf(context.getSharedPreferences("routerdash_security", 0).getBoolean("biometric_enabled", false)) }
    var snapshot by remember { mutableStateOf(Snapshot()) }
    var previousCpu by remember { mutableStateOf<LongArray?>(null) }
    var previousTraffic by remember { mutableStateOf<LongArray?>(null) }
    var previousTrafficAt by remember { mutableLongStateOf(0L) }
    var api by remember { mutableStateOf<RouterApi?>(null) }
    var showNavigationSheet by remember { mutableStateOf(false) }
    var sshSession by remember { mutableStateOf<SshTerminal?>(null) }
    var sshConnected by remember { mutableStateOf(false) }
    var sshConnecting by remember { mutableStateOf(false) }
    var sshOutput by remember { mutableStateOf("RouterDash SSH · готов к подключению\n") }

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
                            cpuRaw = runCatching { readUbusFile(client, "/proc/stat") }.getOrNull()?.takeIf { it.contains("cpu ") }
                                ?: runCatching { client.execReadOnly("cat", listOf("/proc/stat")) }.getOrNull(),
                            networkDevices = client.call("luci-rpc", "getNetworkDevices"),
                            storage = runCatching { parseDfOutput(client.execReadOnly("df", listOf("-kP"))) }.getOrNull(),
                            vpnStatus = runCatching {
                                client.execReadOnly("sh", listOf("-c",
                                    "echo VPN_PROCESSES; ps | grep -E \"tailscale|sing-box|xray|openvpn|wireguard\" | grep -v grep; " +
                                    "echo TAILSCALE_STATUS; if command -v tailscale >/dev/null 2>&1; then tailscale status 2>&1 | head -n 10; fi; " +
                                    "echo TUNNEL_INTERFACES; ip -o addr 2>/dev/null | grep -E \"tailscale|tun|wg|sing\" || true; echo VPN_PROCESS_STATS; top -bn1 2>/dev/null | head -n 25"))
                            }.getOrDefault("Не удалось получить состояние VPN")
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
                            val leases = runCatching { client.call("luci-rpc", "getDHCPLeases") }.getOrDefault(JSONObject())
                            val leaseArray = findArray(leases, listOf("dhcp_leases", "leases", "results", "entries"))
                            val arpRaw = runCatching { readUbusFile(client, "/proc/net/arp") }.getOrNull().orEmpty()
                            val arp = JSONArray()
                            arpRaw.lineSequence().drop(1).forEach { row ->
                                val fields = row.trim().split(Regex("\\s+"))
                                if (fields.size >= 6 && fields[2] == "0x2") {
                                    arp.put(JSONObject().put("ipaddr", fields[0]).put("macaddr", fields[3]).put("device", fields[5]))
                                }
                            }
                            val combined = JSONObject().put("dhcp_leases", leaseArray)
                                .put("arp_entries", arp)
                            snapshot.copy(devices = combined)
                        }
                        Screen.STORAGE -> {
                            val failures = mutableListOf<String>()
                            var mounts = runCatching { normalizeMounts(client.callArray("luci", "getMountPoints")) }
                                .onFailure { failures += "luci.getMountPoints: ${it.message}" }.getOrDefault(JSONArray())
                            if (mounts.length() == 0) {
                                mounts = runCatching {
                                    normalizeMounts(findArrayDeep(client.call("luci", "getMountPoints"),
                                        listOf("mounts", "mount_points", "mountpoints", "results", "entries", "data")))
                                }.onFailure { failures += "ответ LuCI: ${it.message}" }.getOrDefault(JSONArray())
                            }
                            if (mounts.length() == 0) {
                                mounts = runCatching { parseDfOutput(client.execReadOnly("df", listOf("-kP"))) }
                                    .onFailure { failures += "df -kP: ${it.message}" }.getOrDefault(JSONArray())
                            }
                            if (mounts.length() == 0) {
                                mounts = runCatching { parseDfOutput(client.execReadOnly("df", listOf("-k"))) }
                                    .onFailure { failures += "df -k: ${it.message}" }.getOrDefault(JSONArray())
                            }
                            if (mounts.length() == 0) {
                                mounts = runCatching { parseProcMounts(readUbusFile(client, "/proc/mounts").orEmpty()) }
                                    .onFailure { failures += "/proc/mounts: ${it.message}" }.getOrDefault(JSONArray())
                            }
                            // Match LuCI's own storage page: system.info provides root and tmp
                            // capacities in KiB, while luci.getMountPoints provides mounted volumes in bytes.
                            runCatching {
                                val systemInfo = client.call("system", "info")
                                listOf("root" to "/", "tmp" to "/tmp").forEach { (key, mountPath) ->
                                    val fs = systemInfo.optJSONObject(key) ?: return@forEach
                                    val totalKb = fs.optLong("total")
                                    val usedKb = fs.optLong("used", -1L)
                                    val freeKb = fs.optLong("free", -1L)
                                    if (totalKb > 0L && mounts.toString().contains("\"mount\":\"$mountPath\"").not()) {
                                        val usedBytes = if (usedKb >= 0L) usedKb * 1024L
                                            else (totalKb - freeKb.coerceAtLeast(0L)) * 1024L
                                        mounts.put(JSONObject().put("device", if (key == "root") "rootfs" else "tmpfs")
                                            .put("mount", mountPath).put("size", totalKb * 1024L)
                                            .put("used", usedBytes).put("avail", (totalKb * 1024L - usedBytes).coerceAtLeast(0L)))
                                    }
                                }
                            }
                            val diagnostic = if (mounts.length() == 0)
                                failures.joinToString("\n").ifBlank { "Роутер не вернул список файловых систем." }
                            else null
                            val diskDetails = runCatching { client.execReadOnly("sh", listOf("-c", "echo BLOCK_DEVICES; ls /sys/block; echo BLOCK_SIZES_SECTORS; cat /sys/block/*/size 2>/dev/null; echo DISK_STATS; cat /proc/diskstats; echo MOUNTS; grep -E \"/mnt|/overlay|/rom\" /proc/mounts; echo DF; df -h")) }.getOrDefault("Диагностика дисков недоступна")
                            snapshot.copy(storage = mounts, storageError = diagnostic, diskDetails = diskDetails)
                        }
                        Screen.PACKAGES -> snapshot.copy(packages = client.call("rpc-sys", "packagelist"))
                        Screen.DOWNLOADS -> snapshot.copy(downloads = client.aria2Overview())
                        Screen.FIREWALL -> snapshot.copy(firewallRules = runCatching { client.execReadOnly("sh", listOf("-c", "echo UCI_FIREWALL; uci show firewall 2>/dev/null; echo NFT_RULESET; nft list ruleset 2>/dev/null | head -n 260")) }.getOrDefault("Не удалось прочитать правила firewall"))
                        Screen.VPN -> snapshot.copy(vpnStatus = client.execReadOnly("sh", listOf("-c",
                            "echo VPN_PROCESSES; ps | grep -E \"tailscale|sing-box|xray|openvpn|wireguard\" | grep -v grep; " +
                            "echo TAILSCALE_STATUS; if command -v tailscale >/dev/null 2>&1; then tailscale status 2>&1 | head -n 30; fi; " +
                            "echo TUNNEL_INTERFACES; ip -o addr 2>/dev/null | grep -E \"tailscale|tun|wg|sing\" || true; " +
                            "echo VPN_PROCESS_STATS; top -bn1 2>/dev/null | head -n 25; echo ROUTES; ip route 2>/dev/null | head -n 15")))
                        Screen.SERVICES -> snapshot.copy(
                            services = client.call("rc", "list"),
                            processes = runCatching { client.execReadOnly("top", listOf("-bn1")) }.getOrDefault("") +
                                "\n--ROUTERDASH-PS--\n" +
                                runCatching { client.execReadOnly("ps", listOf("-o", "pid,comm,rss")) }.getOrDefault("")
                        )
                        Screen.LOGS -> {
                            val args = JSONObject().put("lines", 300)
                            val failures = mutableListOf<String>()
                            var logs = runCatching { JSONObject().put("log", normalizeLogRows(client.callArray("log", "read", args))) }
                                .onFailure { failures += "ubus log.read (array): ${it.message}" }
                                .getOrDefault(JSONObject().put("log", JSONArray()))
                            if (logs.optJSONArray("log")?.length() == 0) {
                                logs = runCatching { normalizeLogResponse(client.call("log", "read", args)) }
                                    .onFailure { failures += "ubus log.read (object): ${it.message}" }
                                    .getOrDefault(JSONObject().put("log", JSONArray()))
                            }
                            if (logs.optJSONArray("log")?.length() == 0) {
                                logs = runCatching { parseLogReadOutput(client.execReadOnly("logread", listOf("-l", "300"))) }
                                    .onFailure { failures += "logread: ${it.message}" }
                                    .getOrDefault(JSONObject().put("log", JSONArray()))
                            }
                            val hasEntries = (logs.optJSONArray("log")?.length() ?: 0) > 0
                            snapshot.copy(logs = logs,
                                logsError = if (hasEntries) null else failures.joinToString("\n").ifBlank { null })
                        }
                        Screen.SSH -> snapshot
                        Screen.SETTINGS -> snapshot
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
                        snapshot = snapshot.copy(cpuPercent = cpu ?: snapshot.cpuPercent,
                            rxRate = rx, txRate = tx, trafficHistory = history, cpuHistory = cpuHistory)
                    }
                }
                if (screen == Screen.OVERVIEW) {
                    saveRouterDashWidgetSnapshot(context, snapshot, url)
                }
            } catch (e: Exception) {
                error = e.message ?: "Ошибка получения данных"
                if (error?.contains("401") == true || error?.contains("403") == true) connected = false
                if (screen == Screen.OVERVIEW) {
                    context.getSharedPreferences("routerdash_widget_cache", 0).edit()
                        .putBoolean("online", false).putLong("updated_at", System.currentTimeMillis()).apply()
                    RouterDashWidgetProvider.refreshAll(context)
                }
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
                runCatching { encryptedCredentials(context).edit().putString("url", newApi.baseUrl).putString("username", username.trim()).putString("password", password).apply() }
                connected = true
                password = ""
                refresh(Screen.OVERVIEW)
            } catch (e: Exception) {
                error = e.message ?: "Не удалось подключиться"; connected = false
            } finally { loading = false }
        }
    }

    LaunchedEffect(biometricUnlockSignal) {
        if (biometricUnlockSignal > 0 && !connected) {
            runCatching {
                val saved = encryptedCredentials(context)
                val savedUrl = saved.getString("url", null)
                val savedUser = saved.getString("username", null)
                val savedPassword = saved.getString("password", null)
                if (!savedUrl.isNullOrBlank() && !savedUser.isNullOrBlank() && !savedPassword.isNullOrBlank()) {
                    url = savedUrl; username = savedUser; password = savedPassword; connect()
                } else error = "Нет сохранённых данных входа. Подключись по паролю один раз, затем вход по отпечатку будет автоматическим."
            }.onFailure { error = "Не удалось открыть защищённые данные входа: " + (it.message ?: "ошибка") }
        }
    }

    LaunchedEffect(sshConnected) {
        while (sshConnected) {
            val chunk = withContext(Dispatchers.IO) {
                runCatching { sshSession?.readAvailable().orEmpty() }.getOrDefault("")
            }
            if (chunk.isNotEmpty()) sshOutput = (sshOutput + chunk).takeLast(24000)
            delay(250)
        }
    }

    LaunchedEffect(connected, selected, refreshIntervalSeconds) {
        while (connected) {
            refresh(selected)
            delay(refreshIntervalSeconds * 1000L)
        }
    }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val isExpandedLayout = screenWidthDp >= 600

    MaterialTheme(colorScheme = darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFFFF784E),
        secondary = androidx.compose.ui.graphics.Color(0xFF47D7FF),
        tertiary = androidx.compose.ui.graphics.Color(0xFFA98BFF),
        background = androidx.compose.ui.graphics.Color(0xFF0C1320),
        surface = androidx.compose.ui.graphics.Color(0xFF172235),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF21314A),
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
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(painterResource(R.drawable.ic_routerdash_foreground), null,
                                    tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size(34.dp))
                                Column {
                                    Text("RouterDash", fontWeight = FontWeight.Bold)
                                    Text(url, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { selected = Screen.SETTINGS }) {
                                Icon(Icons.Outlined.Settings, contentDescription = "Настройки приложения")
                            }
                            if (loading) CircularProgressIndicator(Modifier.size(20.dp))
                            IconButton(onClick = { refresh() }) { Text("↻", style = MaterialTheme.typography.headlineSmall) }
                            IconButton(onClick = { connected = false; api = null; selected = Screen.OVERVIEW }) {
                                Icon(Icons.Outlined.Logout, contentDescription = "Отключиться")
                            }
                        }
                    )
                },
                bottomBar = {
                    if (!isExpandedLayout) {
                        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 5.dp) {
                            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                IconButton(onClick = { refresh() }, enabled = !loading) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = "Обновить")
                                }
                                Button(onClick = { showNavigationSheet = true },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
                                    Icon(Icons.Outlined.Menu, contentDescription = null)
                                    Spacer(Modifier.width(10.dp))
                                    Text("Разделы · ${selected.title}")
                                    Spacer(Modifier.weight(1f))
                                    Icon(Icons.Outlined.ExpandLess, contentDescription = null)
                                }
                            }
                        }
                    }
                }
            ) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (isExpandedLayout) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surface,
                            header = {
                                Icon(Icons.Outlined.Router, contentDescription = "RouterDash",
                                    modifier = Modifier.padding(vertical = 12.dp).size(28.dp))
                            }
                        ) {
                            val entries = listOf(
                                Screen.OVERVIEW to Icons.Outlined.Dashboard,
                                Screen.NETWORK to Icons.Outlined.Lan,
                                Screen.DEVICES to Icons.Outlined.Devices,
                                Screen.STORAGE to Icons.Outlined.Storage,
                                Screen.PACKAGES to Icons.Outlined.Inventory2,
                                Screen.DOWNLOADS to Icons.Outlined.Download,
                                Screen.SERVICES to Icons.Outlined.MiscellaneousServices,
                                Screen.VPN to Icons.Outlined.VpnKey,
                                Screen.FIREWALL to Icons.Outlined.Security,
                                Screen.SSH to Icons.Outlined.Terminal,
                                Screen.LOGS to Icons.Outlined.Article,
                                Screen.SETTINGS to Icons.Outlined.Tune
                            )
                            entries.forEach { (screen, icon) ->
                                NavigationRailItem(
                                    selected = selected == screen,
                                    onClick = { selected = screen; refresh(screen) },
                                    icon = { Icon(icon, contentDescription = screen.title) },
                                    label = { Text(screen.title, maxLines = 1) },
                                    alwaysShowLabel = false
                                )
                            }
                        }
                    }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().widthIn(max = 1440.dp)
                            .padding(horizontal = if (isExpandedLayout) 28.dp else 16.dp)
                    ) {
                    if (error != null) {
                        ElevatedCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(error!!, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    when (selected) {
                        Screen.OVERVIEW -> OverviewScreen(snapshot)
                        Screen.NETWORK -> NetworkScreen(snapshot.network, snapshot.wireless)
                        Screen.DEVICES -> DevicesScreen(snapshot.devices, snapshot.wireless)
                        Screen.STORAGE -> StorageScreen(snapshot.storage, snapshot.storageError, snapshot.diskDetails) { refresh(Screen.STORAGE) }
                        Screen.PACKAGES -> PackagesScreen(snapshot.packages) { refresh(Screen.PACKAGES) }
                        Screen.DOWNLOADS -> DownloadsScreen(snapshot.downloads, api) { refresh(Screen.DOWNLOADS) }
                        Screen.SERVICES -> ServicesScreen(snapshot.services, snapshot.processes, api) { refresh(Screen.SERVICES) }
                        Screen.VPN -> VpnScreen(snapshot.vpnStatus) { refresh(Screen.VPN) }
                        Screen.FIREWALL -> FirewallScreen(snapshot.firewallRules) { refresh(Screen.FIREWALL) }
                        Screen.SSH -> SshScreen(
                            host = runCatching { URI(url).host ?: "192.168.1.1" }.getOrDefault("192.168.1.1"),
                            defaultUser = username, connected = sshConnected, connecting = sshConnecting, output = sshOutput,
                            onConnect = { host, port, user, pass ->
                                scope.launch {
                                    sshConnecting = true
                                    try {
                                        val terminal = withContext(Dispatchers.IO) { SshTerminal(context).also { it.connect(host, port, user, pass) } }
                                        sshSession = terminal; sshConnected = true
                                        sshOutput += "\n[SSH] Соединение установлено: $user@$host:$port\n[SSH] Host key: ${terminal.fingerprint}\n"
                                    } catch (e: Exception) { sshOutput += "\n[SSH ERROR] ${e.message}\n" }
                                    finally { sshConnecting = false }
                                }
                            },
                            onSend = { command -> scope.launch {
                                try { withContext(Dispatchers.IO) { sshSession?.send(command) }; sshOutput += "\n$ $command\n" }
                                catch (e: Exception) { sshOutput += "\n[SSH ERROR] ${e.message}\n" }
                            } },
                            onDisconnect = { scope.launch {
                                withContext(Dispatchers.IO) { sshSession?.close() }
                                sshSession = null; sshConnected = false; sshOutput += "\n[SSH] Сессия закрыта\n"
                            } }
                        )
                        Screen.LOGS -> LogsScreen(snapshot.logs, snapshot.logsError)
                        Screen.SETTINGS -> SettingsScreen(
                            refreshIntervalSeconds = refreshIntervalSeconds,
                            onIntervalChange = { value ->
                                refreshIntervalSeconds = value
                                prefs.edit().putInt("refresh_interval", value).apply()
                            },
                            biometricEnabled = biometricEnabled,
                            onBiometricToggle = { enabled ->
                                if (enabled) {
                                    onEnableBiometric { biometricEnabled = true }
                                } else {
                                    context.getSharedPreferences("routerdash_security", 0).edit().putBoolean("biometric_enabled", false).apply()
                                    biometricEnabled = false
                                }
                            },
                            onDisconnect = { connected = false; api = null; selected = Screen.OVERVIEW }
                        )
                    }
                    }
                    if (!isExpandedLayout && showNavigationSheet) {
                        val destinations = listOf(
                            Screen.OVERVIEW to Icons.Outlined.Dashboard,
                            Screen.NETWORK to Icons.Outlined.Lan,
                            Screen.DEVICES to Icons.Outlined.Devices,
                            Screen.STORAGE to Icons.Outlined.Storage,
                            Screen.PACKAGES to Icons.Outlined.Inventory2,
                            Screen.DOWNLOADS to Icons.Outlined.Download,
                            Screen.SERVICES to Icons.Outlined.MiscellaneousServices,
                            Screen.VPN to Icons.Outlined.VpnKey,
                            Screen.FIREWALL to Icons.Outlined.Security,
                            Screen.SSH to Icons.Outlined.Terminal,
                            Screen.LOGS to Icons.Outlined.Article,
                            Screen.SETTINGS to Icons.Outlined.Tune
                        )
                        ModalBottomSheet(onDismissRequest = { showNavigationSheet = false },
                            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                            containerColor = MaterialTheme.colorScheme.surface) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                Text("Навигация", style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
                                LazyColumn(Modifier.heightIn(max = 560.dp)) {
                                    items(destinations) { (screen, icon) ->
                                        NavigationDrawerItem(
                                            label = { Text(screen.title) }, selected = selected == screen,
                                            onClick = { showNavigationSheet = false; selected = screen; refresh(screen) },
                                            icon = { Icon(icon, contentDescription = null) },
                                            modifier = Modifier.padding(vertical = 3.dp),
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                            }
                        }
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
private fun SettingsScreen(
    refreshIntervalSeconds: Int,
    onIntervalChange: (Int) -> Unit,
    biometricEnabled: Boolean,
    onBiometricToggle: (Boolean) -> Unit,
    onDisconnect: () -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Параметры работы RouterDash на этом устройстве",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        item {
            InfoCard("Частота обновления") {
                Text("Как часто приложение запрашивает данные у роутера.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(3, 5, 10, 15, 30, 60).forEach { seconds ->
                        FilterChip(selected = refreshIntervalSeconds == seconds,
                            onClick = { onIntervalChange(seconds) }, label = { Text("${seconds}с") })
                    }
                }
                Text("Текущий интервал: $refreshIntervalSeconds сек.",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            InfoCard("Защита приложения") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Вход по отпечатку")
                        Text(if (biometricEnabled) "Защита включена" else "Защита выключена",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = biometricEnabled, onCheckedChange = onBiometricToggle)
                }
                Text("Используется системная биометрия Android.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            InfoCard("Подключение") {
                Text("Адрес и учётные данные задаются на экране входа. Пароль LuCI не сохраняется приложением.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Logout, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Отключиться от роутера")
                }
            }
        }
        item {
            Text("RouterDash 2.2.0 · Нативное приложение", modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OverviewScreen(s: Snapshot) {
    val board = s.board
    val info = s.info
    val mem = info?.optJSONObject("memory")
    val total = mem?.optLong("total") ?: 0L
    val available = mem?.optLong("available", -1L)?.takeIf { it >= 0L }
        ?: ((mem?.optLong("free") ?: 0L) + (mem?.optLong("buffered") ?: 0L) + (mem?.optLong("cached") ?: 0L))
    val used = (total - available).coerceAtLeast(0L)
    val ramPercent = if (total > 0L) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
    val uptime = info?.optLong("uptime")?.let { duration(it) } ?: "—"
    val load = info?.optJSONArray("load")?.optLong(0)?.let { String.format("%.2f", it / 1000.0) } ?: "—"
    val hostname = board?.optString("hostname", "OpenWrt")?.ifBlank { "OpenWrt" } ?: "OpenWrt"
    val model = board?.optString("model", "Маршрутизатор")?.ifBlank { "Маршрутизатор" } ?: "Маршрутизатор"
    val cpu = s.cpuPercent
    val ramLabel = if (total > 0L) "${(ramPercent * 100).toInt()}%" else "—"
    val health = when {
        cpu == null || total <= 0L -> "Собираем данные"
        cpu >= 85 || ramPercent >= 0.90f -> "Нужна проверка"
        else -> "Всё работает"
    }
    val healthColor = if (health == "Нужна проверка")
        androidx.compose.ui.graphics.Color(0xFFFFB454)
    else androidx.compose.ui.graphics.Color(0xFF42D9A0)
    val diskRows = s.storage
    val rootDisk = (0 until (diskRows?.length() ?: 0)).mapNotNull { diskRows?.optJSONObject(it) }
        .firstOrNull { it.optString("mount") in listOf("/overlay", "/", "/mnt/sda") }
    val diskTotal = rootDisk?.optLong("size") ?: 0L
    val diskUsed = rootDisk?.optLong("used") ?: 0L
    val diskPercent = if (diskTotal > 0L) (diskUsed * 100L / diskTotal).toInt() else -1
    val vpn = s.vpnStatus.orEmpty()
    val vpnDetected = vpn.contains("tailscale", true) || vpn.contains("sing-box", true) ||
        vpn.contains("xray", true) || vpn.contains("openvpn", true) || vpn.contains("wireguard", true)
    val vpnLabel = when {
        vpn.contains("Logged out", true) || vpn.contains("NeedsLogin", true) -> "Нужен вход"
        vpnDetected -> "Обнаружен"
        vpn.isBlank() -> "Проверяем…"
        else -> "Не найден"
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 3.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            RouterStatusHero(hostname, model, health, healthColor, uptime)
        }
        item {
            GaugeCard("CPU", cpu?.let { "$it%" } ?: "—",
                (cpu ?: 0) / 100f, "Процессор",
                accent = androidx.compose.ui.graphics.Color(0xFFFF8A3D))
        }
        item {
            GaugeCard("RAM", ramLabel, ramPercent,
                if (total > 0L) "Свободно ${formatBytes(available)}" else "Нет данных",
                accent = androidx.compose.ui.graphics.Color(0xFF4FBEFF))
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            InfoCard("Интернет · WAN") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    DashboardMetric("↓ Приём", s.rxRate?.let { formatRate(it) } ?: "—",
                        androidx.compose.ui.graphics.Color(0xFF36D6C5), Modifier.weight(1f))
                    DashboardMetric("↑ Передача", s.txRate?.let { formatRate(it) } ?: "—",
                        androidx.compose.ui.graphics.Color(0xFFFF9A52), Modifier.weight(1f))
                    DashboardMetric("Load average", load,
                        androidx.compose.ui.graphics.Color(0xFFB58CFF), Modifier.weight(0.8f))
                }
            }
        }
        item {
            DashboardMiniCard("Диск / overlay", if (diskPercent >= 0) "${diskPercent}%" else "—",
                if (diskTotal > 0) "${formatBytes(diskUsed)} / ${formatBytes(diskTotal)}" else "Нет данных",
                if (diskPercent >= 90) androidx.compose.ui.graphics.Color(0xFFFF5D73) else androidx.compose.ui.graphics.Color(0xFF55D6A7),
                Icons.Outlined.Storage)
        }
        item {
            DashboardMiniCard("VPN / туннели", vpnLabel,
                if (vpn.contains("Logged out", true)) "Tailscale: нет входа" else "Tailscale · sing-box · VLESS",
                if (vpnLabel == "Нужен вход") androidx.compose.ui.graphics.Color(0xFFFFB454) else androidx.compose.ui.graphics.Color(0xFF5CC8FF),
                Icons.Outlined.VpnKey)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            InfoCard("Система · ${board?.optJSONObject("release")?.optString("version", "OpenWrt") ?: "OpenWrt"}") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    CompactSystemMetric("Ядро", board?.optString("kernel", "—") ?: "—", Modifier.weight(1f))
                    CompactSystemMetric("Архитектура", board?.optString("system", "—") ?: "—", Modifier.weight(1f))
                    CompactSystemMetric("Аптайм", uptime, Modifier.weight(1f))
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            val warning = (cpu ?: 0) >= 85 || (total > 0L && ramPercent >= 0.90f) || diskPercent >= 90
            Surface(
                color = (if (warning) androidx.compose.ui.graphics.Color(0xFFFFB454) else androidx.compose.ui.graphics.Color(0xFF42D9A0)).copy(alpha = 0.12f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(15.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (warning) Icons.Outlined.WarningAmber else Icons.Outlined.Verified,
                        null, tint = if (warning) androidx.compose.ui.graphics.Color(0xFFFFB454) else androidx.compose.ui.graphics.Color(0xFF42D9A0))
                    Text(if (warning) "Есть показатели, требующие внимания" else "Роутер работает штатно · критичных отклонений нет",
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun DashboardMetric(label: String, value: String, accent: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier.padding(vertical = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            color = accent, maxLines = 1)
    }
}

@Composable
private fun DashboardMiniCard(title: String, value: String, detail: String,
                              accent: androidx.compose.ui.graphics.Color, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    InfoCard(title) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(21.dp))
            Column(Modifier.weight(1f)) {
                Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    color = accent, maxLines = 1)
                Text(detail, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

@Composable
private fun RouterStatusHero(
    hostname: String, model: String, status: String,
    statusColor: androidx.compose.ui.graphics.Color, uptime: String
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(
                        androidx.compose.ui.graphics.Color(0xFF263F59),
                        androidx.compose.ui.graphics.Color(0xFF25334B),
                        androidx.compose.ui.graphics.Color(0xFF3C2E55)
                    )
                ), shape
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f),
            modifier = Modifier.size(46.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_routerdash_foreground), contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size(38.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(hostname, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White, maxLines = 1)
            Text(model, style = MaterialTheme.typography.labelMedium,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.72f), maxLines = 1)
            Text("Маршрутизатор · OpenWrt", style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.58f))
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                color = statusColor.copy(alpha = 0.18f)) {
                Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(7.dp).background(statusColor,
                        androidx.compose.foundation.shape.CircleShape))
                    Text(status, color = statusColor, style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            Text("Аптайм $uptime", style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.72f))
        }
    }
}

@Composable
private fun CompactSystemMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            maxLines = 1)
    }
}

@Composable
private fun StatusRecommendationsCard(cpuPercent: Int?, ramPercent: Int?, availableRam: Long?) {
    val issues = buildList {
        if (cpuPercent != null && cpuPercent >= 85) add("Высокая загрузка CPU ($cpuPercent%). Проверь список процессов и активные службы.")
        if (ramPercent != null && ramPercent >= 90) add("Критически мало свободной памяти ($ramPercent% занято). Найди процессы с большим потреблением ОЗУ.")
        else if (ramPercent != null && ramPercent >= 80) add("ОЗУ занято на $ramPercent%. Проверь, не растёт ли потребление памяти со временем.")
        if (availableRam != null && availableRam < 64L * 1024L * 1024L)
            add("Свободно менее 64 МБ ОЗУ. Избегай запуска дополнительных тяжёлых служб.")
    }
    val warning = issues.isNotEmpty()
    InfoCard("Состояние системы и рекомендации") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                if (warning) Icons.Outlined.WarningAmber else Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = if (warning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
            )
            Text(
                when {
                    cpuPercent == null || ramPercent == null -> "Собираем показатели для оценки"
                    warning -> "Требуется внимание"
                    else -> "Работает в нормальном режиме"
                },
                fontWeight = FontWeight.SemiBold
            )
        }
        if (issues.isEmpty()) {
            Text(
                if (cpuPercent == null || ramPercent == null) "Рекомендации появятся после получения статистики CPU и памяти."
                else "Критичных отклонений по загрузке процессора и памяти не обнаружено.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            issues.forEach { recommendation ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                    Text(recommendation, style = MaterialTheme.typography.bodySmall)
                }
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
    val maxRate = maxOf(s.rxRate ?: 0L, s.txRate ?: 0L, 1024L)
    InfoCard("Скорость соединения · WAN") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Speedometer("Приём", s.rxRate?.let { formatRate(it) } ?: "—",
                ((s.rxRate ?: 0L).toFloat() / maxRate).coerceIn(0f, 1f),
                androidx.compose.ui.graphics.Color(0xFF36D6C5), Modifier.weight(1f))
            Speedometer("Передача", s.txRate?.let { formatRate(it) } ?: "—",
                ((s.txRate ?: 0L).toFloat() / maxRate).coerceIn(0f, 1f),
                androidx.compose.ui.graphics.Color(0xFFFF9A52), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Speedometer(title: String, value: String, progress: Float, accent: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
    val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = androidx.compose.animation.core.tween(700),
        label = "speed-$title"
    )
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().height(78.dp), contentAlignment = Alignment.BottomCenter) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val stroke = 8.dp.toPx()
                val diameter = (size.width - stroke).coerceAtLeast(1f)
                val top = size.height - diameter / 2f - stroke / 2f
                val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)
                val left = (size.width - diameter) / 2f
                drawArc(trackColor, 180f, 180f, false, androidx.compose.ui.geometry.Offset(left, top), arcSize,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                drawArc(brush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), accent)),
                    startAngle = 180f, sweepAngle = 180f * animatedProgress, useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(left, top), size = arcSize,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                val angle = Math.PI * (1.0 + animatedProgress.toDouble())
                val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height - stroke / 2f)
                val radius = diameter / 2f - stroke
                val end = androidx.compose.ui.geometry.Offset(center.x + kotlin.math.cos(angle).toFloat() * radius,
                    center.y + kotlin.math.sin(angle).toFloat() * radius)
                drawLine(accent, center, end, strokeWidth = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawCircle(accent, radius = 3.dp.toPx(), center = center)
            }
            Text(value, Modifier.padding(bottom = 1.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
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
private fun StorageScreen(mounts: JSONArray?, loadError: String?, diskDetails: String?, onRefresh: () -> Unit) {
    data class Mount(val path: String, val device: String, val size: Long, val available: Long) {
        val used: Long get() = (size - available).coerceAtLeast(0L)
        val percent: Int get() = if (size > 0L) ((used * 100L) / size).toInt().coerceIn(0, 100) else 0
    }
    val entries = remember(mounts) {
        if (mounts == null) emptyList() else (0 until mounts.length()).mapNotNull { index ->
            val item = mounts.optJSONObject(index) ?: return@mapNotNull null
            val size = item.optLong("size", item.optLong("total", item.optLong("total_bytes", 0L))).coerceAtLeast(0L)
            val available = item.optLong("avail", item.optLong("available", item.optLong("free", 0L))).coerceIn(0L, size)
            Mount(item.optString("mount", item.optString("mountpoint", item.optString("target", "—"))),
                item.optString("device", item.optString("fs", item.optString("source", "—"))), size, available)
        }.filter { it.path != "—" }.distinctBy { it.path }.sortedBy { it.path }
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Хранилища роутера", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = "Обновить") }
            }
            Text("Использование файловых систем, разделов и подключённых накопителей.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            InfoCard("Физические диски · разделы · точки монтирования") {
                Text(diskDetails ?: "Диагностика ещё не получена.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
        if (!loadError.isNullOrBlank()) item {
            InfoCard("Диагностика получения данных") {
                Text(loadError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Повторить") }
            }
        }
        if (mounts == null) item { EmptyState("Нет данных о файловых системах.") }
        else if (entries.isEmpty()) item { EmptyState("Роутер не вернул смонтированные файловые системы.") }
        else items(entries, key = { it.path + it.device }) { mount ->
            val barColor = when {
                mount.percent >= 90 -> MaterialTheme.colorScheme.error
                mount.percent >= 75 -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.primary
            }
            InfoCard(mount.path) {
                InfoLine("Устройство", mount.device)
                if (mount.size > 0L) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Занято", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${formatBytes(mount.used)} · ${mount.percent}%", fontWeight = FontWeight.SemiBold)
                    }
                    LinearProgressIndicator(progress = { mount.percent / 100f }, modifier = Modifier.fillMaxWidth(), color = barColor)
                    InfoLine("Свободно", formatBytes(mount.available))
                    InfoLine("Всего", formatBytes(mount.size))
                } else {
                    Text("Точка монтирования обнаружена, статистика объёма недоступна.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun findArray(root: JSONObject?, keys: List<String>): JSONArray {
    if (root == null) return JSONArray()
    keys.forEach { key -> root.optJSONArray(key)?.let { return it } }
    root.keys().asSequence().forEach { key ->
        val nested = root.optJSONObject(key)
        keys.forEach { name -> nested?.optJSONArray(name)?.let { return it } }
    }
    return JSONArray()
}

private fun findArrayDeep(root: Any?, keys: List<String>, depth: Int = 0): JSONArray {
    if (depth > 8 || root == null) return JSONArray()
    if (root is JSONArray) return root
    if (root is JSONObject) {
        keys.forEach { key -> root.optJSONArray(key)?.let { return it } }
        val iterator = root.keys()
        while (iterator.hasNext()) {
            val value = root.opt(iterator.next())
            val found = findArrayDeep(value, keys, depth + 1)
            if (found.length() > 0) return found
        }
    }
    return JSONArray()
}

private fun normalizeMounts(source: JSONArray): JSONArray {
    val normalized = JSONArray()
    for (index in 0 until source.length()) {
        val item = source.optJSONObject(index) ?: continue
        val device = item.optString("device", item.optString("fs",
            item.optString("source", item.optString("filesystem", "—"))))
        val mount = item.optString("mount", item.optString("mountpoint",
            item.optString("target", item.optString("path", ""))))
        if (mount.isBlank()) continue
        val blockSize = item.optLong("block_size", item.optLong("bsize", item.optLong("frsize", 0L)))
        val blocks = item.optLong("blocks", item.optLong("total_blocks", 0L))
        val rawSize = item.optLong("size", item.optLong("total", item.optLong("total_bytes", 0L)))
        val size = if (rawSize > 0L) rawSize else if (blockSize > 0L && blocks > 0L) blockSize * blocks else 0L
        val rawUsed = item.optLong("used", item.optLong("used_bytes", -1L))
        val rawAvailable = item.optLong("avail", item.optLong("available",
            item.optLong("free", item.optLong("free_bytes", -1L))))
        val used = if (rawUsed >= 0L) rawUsed else if (size > 0L && rawAvailable >= 0L) (size - rawAvailable).coerceAtLeast(0L) else 0L
        val available = if (rawAvailable >= 0L) rawAvailable else (size - used).coerceAtLeast(0L)
        normalized.put(JSONObject().put("device", device).put("mount", mount)
            .put("size", size).put("used", used).put("avail", available))
    }
    return normalized
}

private fun parseDfOutput(output: String): JSONArray {
    val rows = JSONArray()
    output.lineSequence().drop(1).forEach { raw ->
        val parts = raw.trim().split(Regex("\\s+"))
        if (parts.size < 6) return@forEach
        val blockSize = parts[1].toLongOrNull() ?: return@forEach
        val used = parts[2].toLongOrNull() ?: return@forEach
        val available = parts[3].toLongOrNull() ?: return@forEach
        rows.put(JSONObject().put("device", parts[0]).put("mount", parts.last())
            .put("size", blockSize * 1024L).put("used", used * 1024L)
            .put("avail", available * 1024L))
    }
    return rows
}

private fun parseProcMounts(output: String): JSONArray {
    val rows = JSONArray()
    val seen = mutableSetOf<String>()
    output.lineSequence().forEach { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size >= 3) {
            val mount = parts[1].replace("\\040", " ")
            if (mount.startsWith("/") && seen.add(mount)) {
                rows.put(JSONObject().put("device", parts[0]).put("mount", mount)
                    .put("size", 0).put("used", 0).put("avail", 0))
            }
        }
    }
    return rows
}

private fun normalizeLogResponse(response: JSONObject): JSONObject {
    val source = findArrayDeep(response, listOf("log", "entries", "lines", "messages", "data"))
    val entries = normalizeLogRows(source)
    if (entries.length() > 0) return JSONObject().put("log", entries)
    val text = response.optString("data", response.optString("output", ""))
    if (text.isNotBlank()) return parseLogReadOutput(text)
    return JSONObject().put("log", entries)
}

private fun normalizeLogRows(source: JSONArray): JSONArray {
    val entries = JSONArray()
    for (index in 0 until source.length()) {
        when (val row = source.opt(index)) {
            is JSONObject -> entries.put(row)
            is String -> entries.put(parseLogLine(row))
        }
    }
    return entries
}

private fun parseLogReadOutput(output: String): JSONObject {
    val entries = JSONArray()
    output.lineSequence().filter { it.isNotBlank() }.forEach { entries.put(parseLogLine(it)) }
    return JSONObject().put("log", entries)
}

private fun parseLogLine(line: String): JSONObject {
    val priority = when {
        line.contains(" error", true) || line.contains(" failed", true) || line.contains(" denied", true) -> "error"
        line.contains(" warn", true) -> "warning"
        else -> "info"
    }
    val timestamp = Regex("^(\\w{3}\\s+\\d+\\s+[\\d:]+)").find(line)?.value.orEmpty()
    return JSONObject().put("msg", line).put("time", timestamp).put("priority", priority)
}

private fun serviceDescription(name: String): String {
    val descriptions = mapOf(
        "dnsmasq" to "Локальный DNS и DHCP: выдаёт IP-адреса устройствам и разрешает доменные имена.",
        "odhcpd" to "Объявления IPv6, DHCPv6 и Router Advertisement в локальной сети.",
        "netifd" to "Управляет сетевыми интерфейсами, WAN/LAN-подключениями и адресацией.",
        "firewall" to "Межсетевой экран: фильтрует входящий, исходящий и транзитный трафик.",
        "firewall4" to "Применяет правила межсетевого экрана на базе nftables.",
        "dropbear" to "SSH-сервер для защищённого удалённого доступа к роутеру.",
        "uhttpd" to "Встроенный HTTP/HTTPS-сервер, через который работает LuCI.",
        "rpcd" to "Предоставляет RPC/ubus-доступ к функциям OpenWrt и LuCI.",
        "log" to "Системный журнал: принимает и хранит сообщения служб и ядра.",
        "cron" to "Планировщик периодических задач по расписанию.",
        "sysntpd" to "Синхронизирует системное время по NTP.",
        "tailscale" to "Защищённая mesh-VPN сеть для удалённого доступа без публичного IP.",
        "sing-box" to "Прокси и маршрутизация трафика: VLESS, Shadowsocks и другие протоколы.",
        "aria2" to "Фоновый менеджер загрузок HTTP/FTP/BitTorrent.",
        "smbd" to "Общий доступ к файлам и папкам по протоколу SMB для ПК и телевизоров.",
        "wsdd2" to "Обнаружение SMB-устройств в локальной сети Windows.",
        "collectd" to "Собирает системные метрики CPU, памяти, дисков и сети.",
        "irqbalance" to "Распределяет аппаратные прерывания между ядрами процессора."
    )
    return descriptions[name] ?: when {
        name.startsWith("luci") -> "Компонент веб-интерфейса LuCI: предоставляет страницу или функцию управления роутером."
        name.startsWith("ppp") -> "Обслуживает PPP/PPPoE-подключение к провайдеру."
        name.startsWith("wpa") || name.startsWith("hostapd") -> "Управляет Wi-Fi точками доступа и аутентификацией беспроводных клиентов."
        name.startsWith("ntp") -> "Синхронизация системного времени."
        else -> "Системная служба OpenWrt. Назначение зависит от установленного пакета и конфигурации."
    }
}

private fun processUsage(output: String?, service: String): Pair<String, String>? {
    if (output.isNullOrBlank()) return null
    val sections = output.split("\n--ROUTERDASH-PS--\n", limit = 2)
    val topLines = sections.first().lines()
    val header = topLines.firstOrNull { it.contains("PID") && it.contains("%CPU") }
    var cpuValue = "—"
    if (header != null) {
        val columns = header.trim().split(Regex("\\s+"))
        val cpuIndex = columns.indexOfFirst { it.equals("%CPU", true) || it.equals("CPU%", true) }
        val row = topLines.dropWhile { it != header }.drop(1)
            .firstOrNull { it.contains(service, true) }
        if (cpuIndex >= 0 && row != null) cpuValue = row.trim().split(Regex("\\s+")).getOrNull(cpuIndex) ?: "—"
    }
    var rssKb = 0L
    if (sections.size > 1) {
        val psLines = sections[1].lines()
        val psHeader = psLines.firstOrNull { it.contains("PID", true) && it.contains("RSS", true) }
        if (psHeader != null) {
            val columns = psHeader.trim().split(Regex("\\s+"))
            val rssIndex = columns.indexOfFirst { it.equals("RSS", true) }
            psLines.dropWhile { it != psHeader }.drop(1).forEach { row ->
                val fields = row.trim().split(Regex("\\s+"))
                val processName = fields.drop(1).dropLast(1).joinToString(" ")
                if (processName.contains(service, true)) {
                    rssKb += fields.getOrNull(rssIndex)?.toLongOrNull() ?: 0L
                }
            }
        }
    }
    val memoryValue = if (rssKb > 0L) formatBytes(rssKb * 1024L) else "Нет данных"
    return cpuValue to memoryValue
}
private fun packageDescription(name: String): String {
    val exact = mapOf(
        "adblock" to "Блокировка рекламы и трекеров на уровне DNS.",
        "aria2" to "Многопоточный загрузчик файлов по HTTP, HTTPS, FTP и BitTorrent.",
        "block-mount" to "Автоматическое подключение внешних накопителей.",
        "ca-bundle" to "Набор доверенных корневых сертификатов для HTTPS.",
        "curl" to "Утилита для HTTP-запросов и загрузки данных из сети.",
        "dnsmasq" to "Локальный DNS-сервер и DHCP для домашних устройств.",
        "dropbear" to "Лёгкий SSH-сервер для удалённого доступа к роутеру.",
        "firewall4" to "Межсетевой экран OpenWrt на базе nftables.",
        "htop" to "Интерактивный просмотр процессов и нагрузки системы.",
        "iperf3" to "Измерение пропускной способности сети.",
        "kmod-usb-storage" to "Драйвер поддержки USB-накопителей.",
        "luci" to "Веб-интерфейс управления OpenWrt.",
        "luci-app-aria2" to "Страница настройки и управления загрузчиком aria2 в LuCI.",
        "luci-app-firewall" to "Управление правилами межсетевого экрана через LuCI.",
        "luci-app-opkg" to "Управление пакетами через веб-интерфейс LuCI.",
        "luci-app-sqm" to "Настройка SQM для уменьшения задержек и bufferbloat.",
        "luci-app-ttyd" to "Доступ к терминалу роутера из браузера.",
        "luci-proto-wireguard" to "Поддержка настройки WireGuard в LuCI.",
        "luci-theme-argon" to "Тема оформления веб-интерфейса LuCI.",
        "nano" to "Простой текстовый редактор в терминале.",
        "openssh-sftp-server" to "Поддержка передачи файлов по SFTP через SSH.",
        "rpcd" to "Служба RPC, предоставляющая системные функции через ubus.",
        "samba4-server" to "Общий доступ к файлам по SMB/CIFS для Windows и телевизоров.",
        "sing-box" to "Универсальный сетевой прокси-клиент и маршрутизатор трафика.",
        "tailscale" to "Защищённая mesh-VPN сеть для доступа к устройствам без белого IP.",
        "tcpdump" to "Захват и анализ сетевых пакетов.",
        "wget-ssl" to "Загрузка файлов по HTTP и HTTPS из командной строки.",
        "wireguard-tools" to "Утилиты настройки и управления VPN WireGuard.",
        "wpad" to "Компонент Wi-Fi с поддержкой WPA/WPA2/WPA3."
    )
    exact[name]?.let { return it }
    return when {
        name.startsWith("luci-i18n-") -> "Русский или другой языковой перевод для соответствующего приложения LuCI."
        name.startsWith("luci-app-") -> "Дополнительная страница или функция управления OpenWrt в веб-интерфейсе LuCI."
        name.startsWith("luci-proto-") -> "Поддержка настройки сетевого протокола в интерфейсе LuCI."
        name.startsWith("luci-") -> "Компонент веб-интерфейса LuCI: страницы, библиотеки или системная интеграция."
        name.startsWith("kmod-") -> "Модуль ядра Linux: драйвер или поддержка соответствующего оборудования и функции."
        name.startsWith("lib") -> "Программная библиотека, от которой зависят другие приложения и системные службы."
        name.startsWith("odhcp") -> "Компонент DHCPv4/DHCPv6 и IPv6-автоконфигурации сети."
        name.startsWith("ppp") -> "Компонент протокола PPP для соответствующих WAN-подключений."
        name.startsWith("iptables") || name.startsWith("nftables") -> "Инструменты фильтрации сетевого трафика и настройки firewall."
        name.startsWith("dns") -> "Компонент разрешения DNS-имён и сетевой службы."
        name.startsWith("busybox") -> "Набор базовых Unix-утилит для командной строки OpenWrt."
        name.startsWith("base-") || name in listOf("procd", "netifd", "uci", "ubus", "ubox", "fstools") ->
            "Базовый системный компонент OpenWrt; обеспечивает работу и настройку роутера."
        else -> "Системный пакет OpenWrt. Точное назначение зависит от компонента; название можно использовать для поиска документации."
    }
}

@Composable
private fun PackagesScreen(result: JSONObject?, onRefresh: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("") }
    var expandedPackage by rememberSaveable { mutableStateOf("") }
    val packages = remember(result) {
        val map = result?.optJSONObject("packages") ?: JSONObject()
        map.keys().asSequence().map { name -> name to map.optString(name) }.sortedBy { it.first }.toList()
    }
    val filtered = remember(packages, filter) {
        packages.filter { (name, version) ->
            filter.isBlank() || name.contains(filter, true) || packageDescription(name).contains(filter, true) ||
                version.contains(filter, true)
        }
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Установленные пакеты", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = "Обновить") }
            }
            Text("${filtered.size} из ${packages.size} пакетов · нажми на пакет, чтобы раскрыть описание",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(),
                label = { Text("Поиск по названию и назначению") }, singleLine = true)
        }
        if (result == null) item { EmptyState("Не удалось получить список пакетов. Проверь доступ rpc-sys.packagelist.") }
        else if (filtered.isEmpty()) item { EmptyState("Пакеты не найдены.") }
        else items(filtered, key = { it.first }) { (name, version) ->
            ElevatedCard(onClick = { expandedPackage = if (expandedPackage == name) "" else name }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Icon(if (expandedPackage == name) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = if (expandedPackage == name) "Свернуть" else "Описание")
                    }
                    Text("Версия: ${version.ifBlank { "—" }}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (expandedPackage == name) Text(packageDescription(name), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary)
                    else Text(packageDescription(name), maxLines = 2, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DownloadsScreen(result: JSONObject?, api: RouterApi?, onRefresh: () -> Unit) {
    var uri by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val global = result?.optJSONObject("global")
    val tasks = remember(result) {
        val rows = mutableListOf<Pair<String, JSONObject>>()
        listOf("active", "waiting", "stopped").forEach { key ->
            val array = result?.optJSONArray(key) ?: return@forEach
            for (i in 0 until array.length()) {
                val task = array.optJSONObject(i) ?: continue
                rows += (if (key == "active") "active" else if (key == "waiting") "waiting" else task.optString("status", "stopped")) to task
            }
        }
        rows
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Загрузки aria2", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = "Обновить") }
            }
            if (global != null) {
                InfoCard("Состояние загрузчика") {
                    InfoLine("Загружается", formatRate(global.optString("downloadSpeed").toLongOrNull() ?: 0L))
                    InfoLine("Отдаётся", formatRate(global.optString("uploadSpeed").toLongOrNull() ?: 0L))
                    InfoLine("Активные", global.optString("numActive", "0"))
                    InfoLine("В очереди", global.optString("numWaiting", "0"))
                    InfoLine("Остановлены", global.optString("numStopped", "0"))
                }
            }
            InfoCard("Добавить загрузку") {
                OutlinedTextField(uri, { uri = it }, Modifier.fillMaxWidth(),
                    label = { Text("HTTP(S), FTP или magnet-ссылка") }, singleLine = true)
                Button(
                    enabled = uri.isNotBlank() && !adding && api != null,
                    onClick = {
                        val target = uri.trim()
                        adding = true
                        message = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { api?.aria2AddUri(target) }
                                uri = ""
                                message = "Загрузка добавлена."
                                onRefresh()
                            } catch (e: Exception) { message = e.message ?: "Не удалось добавить загрузку" }
                            finally { adding = false }
                        }
                    }
                ) {
                    if (adding) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Добавить")
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (result == null) item { EmptyState("aria2 недоступен. Проверь, что служба запущена и JSON-RPC доступен на порту 6800.") }
        else if (tasks.isEmpty()) item { EmptyState("Активных и завершённых загрузок нет.") }
        else items(tasks, key = { it.second.optString("gid") }) { (status, task) ->
            val total = task.optString("totalLength").toLongOrNull() ?: 0L
            val done = task.optString("completedLength").toLongOrNull() ?: 0L
            val percent = if (total > 0L) (done * 100L / total).toInt().coerceIn(0, 100) else if (status == "complete") 100 else 0
            val files = task.optJSONArray("files")
            val file = files?.optJSONObject(0)
            val name = file?.optString("path")?.takeIf { !it.isNullOrBlank() }
                ?: task.optString("bittorrent", "").ifBlank { task.optString("gid", "Загрузка") }
            InfoCard(name.substringAfterLast('/')) {
                InfoLine("Состояние", status)
                LinearProgressIndicator(progress = { percent / 100f }, Modifier.fillMaxWidth())
                InfoLine("Прогресс", "$percent% · ${formatBytes(done)} / ${formatBytes(total)}")
                if (status == "active") InfoLine("Скорость", formatRate(task.optString("downloadSpeed").toLongOrNull() ?: 0L))
                if (task.optString("errorMessage").isNotBlank()) Text(task.optString("errorMessage"),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ServicesScreen(result: JSONObject?, processes: String?, api: RouterApi?, onRefresh: () -> Unit) {
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
                Text(serviceDescription(name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val usage = processUsage(processes, name)
                InfoLine("CPU процесса", usage?.first?.let { "$it%" } ?: "Нет данных")
                InfoLine("ОЗУ процесса (RSS)", usage?.second ?: "Нет данных")
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
private fun LogsScreen(result: JSONObject?, loadError: String?) {
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
        if (!loadError.isNullOrBlank()) item {
            InfoCard("Не удалось прочитать журнал") {
                Text(loadError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (result == null) item { EmptyState("Журнал не получен. Проверь доступ к ubus log.read и команде logread.") }
        else if (entries.isEmpty()) item { EmptyState(if (loadError.isNullOrBlank()) "Журнал пуст или записей по фильтру нет." else "Нет доступных записей журнала.") }
        else items(entries) { line ->
            val message = line.optString("msg", line.optString("message", line.optString("line", "—")))
            val timestamp = line.optString("time", line.optString("timestamp", ""))
            val priority = line.optString("priority", line.optString("level", "info"))
            InfoCard(priority.ifBlank { "Системное сообщение" }) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (timestamp.isNotBlank()) Text(timestamp, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
@Composable
private fun VpnScreen(status: String?, onRefresh: () -> Unit) {
    val lines = status.orEmpty().lines().filter { it.isNotBlank() }
    val processes = lines.filter { !it.startsWith("VPN_") && !it.startsWith("TAILSCALE_") &&
        !it.startsWith("TUNNEL_") && !it.startsWith("ROUTES") }
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("VPN и туннели", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, null) }
            }
            Text("Состояние Tailscale, sing-box / VLESS, VPN-процессов, туннельных интерфейсов и маршрутов.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            InfoCard("Обнаруженные VPN-процессы") {
                if (processes.isEmpty()) Text("Процессы VPN не обнаружены или нет доступа к списку процессов.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else processes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            InfoCard("Диагностика туннелей и маршрутов") {
                Text(status ?: "Данные ещё не получены.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FirewallScreen(rules: String?, onRefresh: () -> Unit) {
    val text = rules.orEmpty()
    val sections = text.split("NFT_RULESET")
    val uci = sections.firstOrNull().orEmpty().substringAfter("UCI_FIREWALL", "")
    val nft = sections.getOrNull(1).orEmpty()
    val uciLines = uci.lines().filter { it.isNotBlank() }
    val nftLines = nft.lines().filter { it.isNotBlank() }
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Правила firewall", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, null) }
            }
            Text("Настройки зон, перенаправлений портов и фактические правила nftables (firewall4).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            InfoCard("Конфигурация UCI · ${uciLines.size} строк") {
                Text(if (uciLines.isEmpty()) "Конфигурация firewall не получена." else uciLines.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
        item {
            InfoCard("Активные правила nftables · ${nftLines.size} строк") {
                Text(if (nftLines.isEmpty()) "Не удалось прочитать nft ruleset. Проверь наличие nftables." else nftLines.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun SshScreen(host: String, defaultUser: String, connected: Boolean, connecting: Boolean,
                      output: String, onConnect: (String, Int, String, String) -> Unit,
                      onSend: (String) -> Unit, onDisconnect: () -> Unit) {
    var hostValue by rememberSaveable(host) { mutableStateOf(host) }
    var portValue by rememberSaveable { mutableStateOf("22") }
    var userValue by rememberSaveable(defaultUser) { mutableStateOf(defaultUser) }
    var passwordValue by remember { mutableStateOf("") }
    var command by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SSH-консоль", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Отдельная интерактивная shell-сессия Dropbear. Команды выполняются непосредственно на роутере.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!connected) {
            OutlinedTextField(hostValue, { hostValue = it }, Modifier.fillMaxWidth(),
                label = { Text("Адрес роутера") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(portValue, { portValue = it.filter(Char::isDigit).take(5) },
                    Modifier.weight(0.35f), label = { Text("Порт") }, singleLine = true)
                OutlinedTextField(userValue, { userValue = it }, Modifier.weight(0.65f),
                    label = { Text("Пользователь") }, singleLine = true)
            }
            OutlinedTextField(passwordValue, { passwordValue = it }, Modifier.fillMaxWidth(),
                label = { Text("Пароль SSH") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Button(onClick = {
                val port = portValue.toIntOrNull() ?: 22
                if (hostValue.isNotBlank() && userValue.isNotBlank() && passwordValue.isNotBlank() && port in 1..65535)
                    onConnect(hostValue, port, userValue, passwordValue)
            }, enabled = !connecting && hostValue.isNotBlank() && userValue.isNotBlank() &&
                passwordValue.isNotBlank() && (portValue.toIntOrNull() ?: 0) in 1..65535,
                modifier = Modifier.fillMaxWidth()) {
                if (connecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Terminal, null)
                Spacer(Modifier.width(8.dp))
                Text(if (connecting) "Подключение…" else "Открыть SSH-сессию")
            }
            Text("По умолчанию используется порт 22. Пароль не сохраняется в истории терминала.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("● SSH подключён · $userValue@$hostValue", color = androidx.compose.ui.graphics.Color(0xFF55D6A7),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = onDisconnect) { Text("Завершить") }
            }
            Surface(Modifier.weight(1f).fillMaxWidth(), color = androidx.compose.ui.graphics.Color(0xFF10151D),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {
                LazyColumn(Modifier.fillMaxSize().padding(10.dp)) {
                    item { Text(output, color = androidx.compose.ui.graphics.Color(0xFFB6F5D0),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(command, { command = it }, Modifier.weight(1f),
                    label = { Text("Команда") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = {
                        if (command.isNotBlank()) { onSend(command); command = "" }
                    }))
                IconButton(onClick = { if (command.isNotBlank()) { onSend(command); command = "" } }) {
                    Icon(Icons.Outlined.Send, "Выполнить")
                }
            }
        }
    }
}

@Composable
private fun GaugeCard(title: String, value: String, progress: Float, caption: String,
                      modifier: Modifier = Modifier, accent: androidx.compose.ui.graphics.Color) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
    val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 850),
        label = "gauge-$title"
    )
    ElevatedCard(modifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val stroke = 9.dp.toPx()
                    drawArc(color = trackColor, startAngle = -90f, sweepAngle = 360f, useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    drawArc(brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                        listOf(accent.copy(alpha = 0.55f), accent, accent.copy(alpha = 0.85f))),
                        startAngle = -90f, sweepAngle = 360f * animatedProgress, useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(caption, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    val edge = androidx.compose.ui.graphics.Brush.linearGradient(
        listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.42f),
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.28f),
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.34f)))
    ElevatedCard(modifier.fillMaxWidth().border(androidx.compose.foundation.BorderStroke(1.dp, edge), shape),
        shape = shape,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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

private class SshTerminal(private val context: android.content.Context) {
    var fingerprint: String = ""
        private set
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    fun connect(host: String, port: Int, username: String, password: String) {
        val jsch = JSch()
        val ssh = jsch.getSession(username, host, port)
        ssh.setPassword(password)
        val config = java.util.Properties().apply {
            put("StrictHostKeyChecking", "no")
            put("PreferredAuthentications", "password,keyboard-interactive")
        }
        ssh.setConfig(config)
        ssh.timeout = 10000
        ssh.connect(10000)
        val hostKeyFingerprint = ssh.hostKey.getFingerPrint(jsch)
        val hostKeyPrefs = context.getSharedPreferences("routerdash_ssh_hosts", 0)
        val hostKeyId = "$host:$port"
        val knownFingerprint = hostKeyPrefs.getString(hostKeyId, null)
        if (knownFingerprint != null && knownFingerprint != hostKeyFingerprint) {
            ssh.disconnect()
            throw SecurityException("SSH host key изменился для $host:$port. Проверь роутер перед повторным подключением.")
        }
        if (knownFingerprint == null) hostKeyPrefs.edit().putString(hostKeyId, hostKeyFingerprint).apply()
        fingerprint = hostKeyFingerprint
        val shell = ssh.openChannel("shell") as ChannelShell
        shell.setPtyType("xterm", 100, 30, 1000, 700)
        val shellInput = shell.inputStream
        val shellOutput = shell.outputStream
        shell.connect(10000)
        session = ssh
        channel = shell
        input = shellInput
        output = shellOutput
    }

    @Synchronized
    fun send(command: String) {
        val stream = output ?: throw IllegalStateException("SSH-сессия не открыта")
        stream.write((command + "\n").toByteArray(Charsets.UTF_8))
        stream.flush()
    }

    @Synchronized
    fun readAvailable(): String {
        val stream = input ?: return ""
        if (channel?.isConnected != true) return ""
        val available = stream.available()
        if (available <= 0) return ""
        val buffer = ByteArray(minOf(available, 8192))
        val count = stream.read(buffer)
        return if (count > 0) String(buffer, 0, count, Charsets.UTF_8) else ""
    }

    @Synchronized
    fun close() {
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
        input = null
        output = null
        channel = null
        session = null
    }
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

    fun execReadOnly(command: String, args: List<String> = emptyList()): String {
        val params = JSONArray()
        args.forEach { params.put(it) }
        val result = call("file", "exec", JSONObject().put("command", command).put("params", params))
        val code = result.optInt("code", 0)
        val stderr = result.optString("stderr")
        if (code != 0) throw IllegalStateException(stderr.ifBlank { command + " завершилась с кодом " + code })
        return result.optString("stdout")
    }

    fun aria2AddUri(uri: String) {
        val base = URI(baseUrl)
        val endpoint = URI(base.scheme, null, base.host, 6800, "/jsonrpc", null, null).toASCIIString()
        val params = JSONArray().put(JSONArray().put(uri))
        val body = JSONObject().put("jsonrpc", "2.0").put("id", System.currentTimeMillis())
            .put("method", "aria2.addUri").put("params", params).toString()
        val request = Request.Builder().url(endpoint).post(body.toRequestBody(mediaType)).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("aria2: HTTP ${response.code}")
            val json = JSONObject(text)
            if (json.has("error")) throw IllegalStateException("aria2: " +
                (json.optJSONObject("error")?.optString("message") ?: "ошибка добавления"))
        }
    }

    fun aria2Overview(): JSONObject {
        fun ariaCall(method: String, params: JSONArray = JSONArray()): Any? {
            val base = URI(baseUrl)
            val endpoint = URI(base.scheme, null, base.host, 6800, "/jsonrpc", null, null).toASCIIString()
            val body = JSONObject().put("jsonrpc", "2.0").put("id", System.currentTimeMillis())
                .put("method", method).put("params", params).toString()
            val request = Request.Builder().url(endpoint).post(body.toRequestBody(mediaType)).build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IllegalStateException("aria2: HTTP ${response.code}")
                val json = JSONObject(text)
                if (json.has("error")) throw IllegalStateException("aria2: " +
                    (json.optJSONObject("error")?.optString("message") ?: "ошибка JSON-RPC"))
                return json.opt("result")
            }
        }
        val global = ariaCall("aria2.getGlobalStat") as? JSONObject ?: JSONObject()
        val active = ariaCall("aria2.tellActive") as? JSONArray ?: JSONArray()
        val waiting = ariaCall("aria2.tellWaiting", JSONArray().put(0).put(50)) as? JSONArray ?: JSONArray()
        val stopped = ariaCall("aria2.tellStopped", JSONArray().put(0).put(50)) as? JSONArray ?: JSONArray()
        return JSONObject().put("global", global).put("active", active)
            .put("waiting", waiting).put("stopped", stopped)
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
    val total = values.take(8).sum()
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


@Suppress("DEPRECATION")
private fun encryptedCredentials(context: android.content.Context): android.content.SharedPreferences {
    val key = androidx.security.crypto.MasterKey.Builder(context).setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
    return androidx.security.crypto.EncryptedSharedPreferences.create(context, "routerdash_credentials", key,
        androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
}

private fun saveRouterDashWidgetSnapshot(context: android.content.Context, snapshot: Snapshot, routerUrl: String) {
    val info = snapshot.info
    val memory = info?.optJSONObject("memory")
    val total = memory?.optLong("total") ?: 0L
    // OpenWrt system.info normally reports free/buffered/cached rather than "available".
    // Prefer an explicit available value when present, otherwise estimate reclaimable RAM.
    val explicitAvailable = memory?.optLong("available", -1L) ?: -1L
    val available = if (explicitAvailable >= 0L) explicitAvailable else {
        (memory?.optLong("free") ?: 0L) +
            (memory?.optLong("buffered") ?: 0L) +
            (memory?.optLong("cached") ?: 0L)
    }
    val ramPercent = if (total > 0L) (((total - available).coerceAtLeast(0L) * 100L) / total).coerceIn(0L, 100L).toInt() else -1
    val board = snapshot.board
    context.getSharedPreferences("routerdash_widget_cache", 0).edit()
        .putBoolean("online", true)
        .putString("router_url", routerUrl)
        .putString("hostname", board?.optString("hostname").orEmpty().ifBlank { "OpenWrt" })
        .putString("model", board?.optString("model").orEmpty().ifBlank { "Маршрутизатор" })
        .putInt("cpu_percent", snapshot.cpuPercent ?: -1)
        .putInt("ram_percent", ramPercent)
        .putString("rx_rate", snapshot.rxRate?.let { formatRate(it) } ?: "—")
        .putString("tx_rate", snapshot.txRate?.let { formatRate(it) } ?: "—")
        .putString("uptime", info?.optLong("uptime")?.let { duration(it) } ?: "—")
        .putLong("updated_at", System.currentTimeMillis())
        .apply()
    RouterDashWidgetProvider.refreshAll(context)
}

package ru.routerdash.android

import android.os.Build
import android.os.Bundle
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RouterDashApp() }
    }
}

private enum class Screen(val title: String) {
    OVERVIEW("Обзор"), NETWORK("Сеть"), DEVICES("Устройства"), SERVICES("Службы"), LOGS("Журнал")
}
private data class Snapshot(
    val board: JSONObject? = null, val info: JSONObject? = null,
    val network: JSONObject? = null, val devices: JSONObject? = null,
    val services: JSONObject? = null, val logs: JSONObject? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouterDashApp() {
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
                            info = client.call("system", "info")
                        )
                        Screen.NETWORK -> snapshot.copy(network = client.call("network.interface", "dump"))
                        Screen.DEVICES -> snapshot.copy(devices = client.call("luci-rpc", "getDHCPLeases"))
                        Screen.SERVICES -> snapshot.copy(services = client.call("rc", "list"))
                        Screen.LOGS -> snapshot.copy(logs = client.call("log", "read", JSONObject().put("lines", 100)))
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

    MaterialTheme(colorScheme = darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFFFF9852),
        secondary = androidx.compose.ui.graphics.Color(0xFF79B8FF),
        background = androidx.compose.ui.graphics.Color(0xFF0B1017),
        surface = androidx.compose.ui.graphics.Color(0xFF151E29)
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
                            Screen.SERVICES to Icons.Outlined.Settings,
                            Screen.LOGS to Icons.Outlined.Article
                        )
                        entries.forEach { (screen, icon) ->
                            NavigationBarItem(
                                selected = selected == screen,
                                onClick = { selected = screen; refresh(screen) },
                                icon = { Icon(icon, contentDescription = screen.title) },
                                label = { Text(screen.title) }
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
                        Screen.NETWORK -> NetworkScreen(snapshot.network)
                        Screen.DEVICES -> DevicesScreen(snapshot.devices)
                        Screen.SERVICES -> ServicesScreen(snapshot.services)
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
    val board = s.board; val info = s.info
    LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(board?.optString("hostname", "OpenWrt") ?: "OpenWrt",
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(board?.optString("model", "Роутер") ?: "Ожидание данных",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val load = info?.optJSONArray("load")?.optLong(0)?.toString() ?: "—"
                MetricCard("CPU load", load, Modifier.weight(1f))
                MetricCard("Аптайм", info?.optLong("uptime")?.let { duration(it) } ?: "—", Modifier.weight(1f))
            }
        }
        item {
            val mem = info?.optJSONObject("memory")
            val total = mem?.optLong("total") ?: 0L
            val available = mem?.optLong("available") ?: 0L
            val value = if (total > 0) (100L * (total - available) / total).toInt().toString() + "% · " +
                formatBytes(total - available) + " / " + formatBytes(total) else "—"
            MetricCard("Оперативная память", value)
        }
        item {
            InfoCard("Система") {
                InfoLine("Версия", board?.optJSONObject("release")?.optString("version", "—") ?: "—")
                InfoLine("Ядро", board?.optString("kernel", "—") ?: "—")
                InfoLine("Архитектура", board?.optString("system", "—") ?: "—")
            }
        }
        item {
            Text("Данные поступают напрямую из LuCI/ubus. Обновляй экран кнопкой ↻.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NetworkScreen(result: JSONObject?) {
    val list = result?.optJSONArray("interface")
    LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Сетевые интерфейсы", style = MaterialTheme.typography.headlineSmall) }
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
private fun DevicesScreen(result: JSONObject?) {
    val list = result?.optJSONArray("dhcp_leases")
    LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("DHCP-клиенты", style = MaterialTheme.typography.headlineSmall) }
        if (list == null || list.length() == 0) item { EmptyState("Нет активных DHCP-аренд") }
        else items((0 until list.length()).map { list.optJSONObject(it) ?: JSONObject() }) { lease ->
            InfoCard(lease.optString("hostname", "Без имени")) {
                InfoLine("IP", lease.optString("ipaddr", "—"))
                InfoLine("MAC", lease.optString("macaddr", "—"))
                InfoLine("Осталось", lease.optString("expires", "—"))
            }
        }
    }
}

@Composable
private fun ServicesScreen(result: JSONObject?) {
    val names = result?.keys()?.asSequence()?.toList()?.sorted() ?: emptyList()
    LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Службы OpenWrt", style = MaterialTheme.typography.headlineSmall) }
        if (names.isEmpty()) item { EmptyState("Нет данных о службах") }
        else items(names) { name ->
            val service = result?.optJSONObject(name) ?: JSONObject()
            InfoCard(name) {
                InfoLine("Состояние", if (service.optBoolean("running")) "Работает" else "Остановлена / неизвестно")
                InfoLine("Автозапуск", if (service.optBoolean("enabled")) "Включён" else "Выключен")
            }
        }
    }
}

@Composable
private fun LogsScreen(result: JSONObject?) {
    val list = result?.optJSONArray("log")
    LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Системный журнал", style = MaterialTheme.typography.headlineSmall) }
        if (list == null || list.length() == 0) item { EmptyState("Журнал пуст или недоступен") }
        else items((0 until list.length()).map { list.optJSONObject(it) ?: JSONObject() }.reversed()) { line ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(line.optString("msg", "—"))
                    Text(line.optString("time", ""), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
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
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
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
        val params = JSONArray().put(username).put(password)
        val body = JSONObject().put("id", 1).put("method", "login").put("params", params).toString()
        val root = JSONObject(post("/cgi-bin/luci/rpc/auth", body))
        val result = root.opt("result")
        session = when (result) {
            is String -> result
            is JSONObject -> result.optString("ubus_rpc_session", result.optString("sid"))
            else -> ""
        }
        if (session.isBlank() || session == "null") throw IllegalStateException("LuCI не принял логин или пароль")
    }

    fun call(objectName: String, method: String, args: JSONObject = JSONObject()): JSONObject {
        check(session.isNotBlank()) { "Нет сессии LuCI. Подключись заново." }
        val params = JSONArray().put(session).put(objectName).put(method).put(args)
        val body = JSONObject().put("jsonrpc", "2.0").put("id", System.currentTimeMillis())
            .put("method", "call").put("params", params).toString()
        val root = JSONObject(post("/cgi-bin/luci/admin/ubus", body))
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
            if (!response.isSuccessful)
                throw IllegalStateException("HTTP " + response.code + ": проверь адрес и доступ к LuCI")
            return text
        }
    }
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

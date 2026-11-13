package ru.routerdash.android

import android.content.Context
import android.net.Uri
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
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

internal enum class SyncAction { UPLOAD, DOWNLOAD, CONFLICT, SAME }
internal data class SyncPlanItem(
    val path: String, val action: SyncAction, val localSize: Long, val remoteSize: Long,
    val localModified: Long, val remoteModified: Long, val note: String
)
internal data class RemoteSyncEntry(val name: String, val isDirectory: Boolean, val size: Long, val modified: Long)

internal object SftpFileSync {
    private data class LocalEntry(val file: DocumentFile, val size: Long, val modified: Long)

    fun scan(context: Context, treeUri: Uri, ssh: SshTerminal, remoteRoot: String): List<SyncPlanItem> {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("Не удалось открыть выбранную папку телефона.")
        if (!root.canRead() || !root.canWrite()) throw IllegalStateException("Нет прав чтения и записи в выбранной папке.")
        val base = normalizeRoot(remoteRoot)
        ssh.verifyMountedStorage(base)
        ssh.ensureRemoteDirectory(base)
        val local = linkedMapOf<String, LocalEntry>()
        fun walkLocal(dir: DocumentFile, prefix: String) {
            dir.listFiles().forEach { child ->
                val name = child.name ?: return@forEach
                if (name == "." || name == ".." || name.contains('/')) return@forEach
                val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                if (child.isDirectory) walkLocal(child, relative)
                else if (child.isFile) local[relative] = LocalEntry(child, child.length(), child.lastModified())
            }
        }
        walkLocal(root, "")
        val remote = linkedMapOf<String, RemoteSyncEntry>()
        fun walkRemote(dir: String, prefix: String) {
            ssh.listRemote(dir).forEach { entry ->
                if (entry.name == "." || entry.name == ".." || entry.name.contains('/')) return@forEach
                val relative = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
                val full = "$dir/${entry.name}".replace("//", "/")
                if (entry.isDirectory) walkRemote(full, relative) else remote[relative] = entry
            }
        }
        walkRemote(base, "")
        return (local.keys + remote.keys).toSortedSet().map { relative ->
            val l = local[relative]; val r = remote[relative]
            val action: SyncAction
            val note: String
            when {
                l == null -> { action = SyncAction.DOWNLOAD; note = "Файл есть только на SSD" }
                r == null -> { action = SyncAction.UPLOAD; note = "Файл есть только на телефоне" }
                l.size == r.size && l.modified > 0L && r.modified > 0L &&
                    kotlin.math.abs(l.modified - r.modified) <= 2000L -> {
                    action = SyncAction.SAME; note = "Файлы совпадают"
                }
                l.modified <= 0L || r.modified <= 0L -> {
                    action = SyncAction.CONFLICT; note = "Хранилище не сообщает время изменения — выбери версию вручную"
                }
                l.modified > r.modified + 2000L -> { action = SyncAction.UPLOAD; note = "На телефоне версия новее" }
                r.modified > l.modified + 2000L -> { action = SyncAction.DOWNLOAD; note = "На SSD версия новее" }
                else -> { action = SyncAction.CONFLICT; note = "Разные версии с близким временем изменения" }
            }
            SyncPlanItem(relative, action, l?.size ?: 0L, r?.size ?: 0L, l?.modified ?: 0L, r?.modified ?: 0L, note)
        }
    }

    fun transfer(context: Context, treeUri: Uri, ssh: SshTerminal, remoteRoot: String, item: SyncPlanItem) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: throw IllegalStateException("Папка телефона недоступна.")
        val remotePath = "${normalizeRoot(remoteRoot)}/${item.path}".replace("//", "/")
        when (item.action) {
            SyncAction.UPLOAD -> {
                val source = findLocal(root, item.path) ?: throw FileNotFoundException("Не найден файл ${item.path}")
                ssh.ensureRemoteDirectory(remotePath.substringBeforeLast('/'))
                context.contentResolver.openInputStream(source.uri).use { input ->
                    if (input == null) throw FileNotFoundException("Не удалось прочитать ${item.path}")
                    ssh.uploadFile(input, remotePath)
                }
            }
            SyncAction.DOWNLOAD -> {
                val parent = ensureLocalDirectory(root, item.path.substringBeforeLast('/', ""))
                val name = item.path.substringAfterLast('/')
                val tempName = ".$name.routerdash-part"
                parent.findFile(tempName)?.delete()
                val temp = parent.createFile("application/octet-stream", tempName)
                    ?: throw IllegalStateException("Не удалось создать временный файл $name")
                try {
                    context.contentResolver.openOutputStream(temp.uri, "w").use { output ->
                        if (output == null) throw IllegalStateException("Не удалось записать $name")
                        ssh.downloadFile(remotePath, output); output.flush()
                    }
                    val old = parent.findFile(name)
                    if (old != null && !old.delete()) throw IllegalStateException("Не удалось заменить $name")
                    if (!temp.renameTo(name)) throw IllegalStateException("Файл загружен, но хранилище не поддерживает переименование.")
                } catch (e: Exception) { temp.delete(); throw e }
            }
            else -> throw IllegalArgumentException("Не выбрано направление передачи.")
        }
    }

    private fun findLocal(root: DocumentFile, relative: String): DocumentFile? {
        var current = root
        val parts = relative.split('/').filter { it.isNotBlank() }
        parts.forEachIndexed { index, part ->
            current = current.findFile(part) ?: return null
            if (index < parts.lastIndex && !current.isDirectory) return null
        }
        return current.takeIf { it.isFile }
    }

    private fun ensureLocalDirectory(root: DocumentFile, relative: String): DocumentFile {
        var current = root
        relative.split('/').filter { it.isNotBlank() }.forEach { part ->
            val found = current.findFile(part)
            current = when {
                found?.isDirectory == true -> found
                found != null -> throw IllegalStateException("На месте папки $part уже находится файл.")
                else -> current.createDirectory(part) ?: throw IllegalStateException("Не удалось создать папку $part")
            }
        }
        return current
    }

    private fun normalizeRoot(path: String): String {
        val cleaned = path.trim().replace(Regex("/+"), "/").trimEnd('/')
        require(cleaned.startsWith("/") && !cleaned.split('/').any { it == ".." }) {
            "Путь SSD должен быть абсолютным и не содержать '..'."
        }
        return cleaned.ifBlank { "/" }
    }
}

@Composable
internal fun SyncScreen(sshConnected: Boolean, ssh: SshTerminal?, sshHostDefault: String, sshUserDefault: String, onOpenSsh: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("routerdash_sync", 0) }
    val scope = rememberCoroutineScope()
    var treeUriText by rememberSaveable { mutableStateOf(prefs.getString("tree_uri", "") ?: "") }
    var remoteRoot by rememberSaveable { mutableStateOf(prefs.getString("remote_root", "/mnt/sda/RouterDashSync") ?: "/mnt/sda/RouterDashSync") }
    var scanning by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var uploadEnabled by rememberSaveable { mutableStateOf(true) }
    var downloadEnabled by rememberSaveable { mutableStateOf(true) }
    var progressText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var completed by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    val securePrefs = remember { encryptedCredentials(context) }
    var autoEnabled by remember { mutableStateOf(prefs.getBoolean("auto_photo_enabled", false)) }
    var autoHost by rememberSaveable { mutableStateOf(securePrefs.getString("auto_sync_ssh_host", sshHostDefault) ?: sshHostDefault) }
    var autoUser by rememberSaveable { mutableStateOf(securePrefs.getString("auto_sync_ssh_user", sshUserDefault) ?: sshUserDefault) }
    var autoPassword by remember { mutableStateOf("") }
    var autoPasswordSaved by remember { mutableStateOf(!securePrefs.getString("auto_sync_ssh_password", null).isNullOrBlank()) }
    var autoPort by rememberSaveable { mutableStateOf(securePrefs.getInt("auto_sync_ssh_port", 22).toString()) }
    var autoInterval by rememberSaveable { mutableIntStateOf(prefs.getInt("auto_interval_minutes", 60).coerceIn(15, 1440)) }
    var autoStatus by remember { mutableStateOf(prefs.getString("auto_last_status", "Пока запусков не было.") ?: "Пока запусков не было.") }
    var autoError by remember { mutableStateOf<String?>(null) }
    val plan = remember { mutableStateListOf<SyncPlanItem>() }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            treeUriText = uri.toString()
            prefs.edit().putString("tree_uri", treeUriText).apply()
            plan.clear(); errorText = null
        }
    }

    fun scanFiles() {
        val active = ssh
        val uri = runCatching { Uri.parse(treeUriText) }.getOrNull()
        if (!sshConnected || active == null) { errorText = "Сначала открой SSH-сессию."; return }
        if (treeUriText.isBlank() || uri == null) { errorText = "Выбери папку на телефоне."; return }
        scanning = true; errorText = null; progressText = "Сравниваю файлы телефона и SSD…"
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { SftpFileSync.scan(context, uri, active, remoteRoot) }
                plan.clear(); plan.addAll(result)
                prefs.edit().putString("remote_root", remoteRoot.trim()).apply()
                progressText = "Сравнение завершено · файлов к обработке: ${result.count { it.action != SyncAction.SAME }}"
            } catch (e: Exception) { errorText = e.message ?: "Ошибка сканирования"; progressText = "" }
            finally { scanning = false }
        }
    }

    fun startSync() {
        val active = ssh ?: return
        val uri = runCatching { Uri.parse(treeUriText) }.getOrNull() ?: return
        val selected = plan.filter { (it.action == SyncAction.UPLOAD && uploadEnabled) || (it.action == SyncAction.DOWNLOAD && downloadEnabled) }
        if (selected.isEmpty()) { errorText = "Нет выбранных файлов. Конфликты нужно разрешить вручную."; return }
        syncing = true; errorText = null; completed = 0; total = selected.size
        scope.launch {
            var failures = 0
            selected.forEachIndexed { index, item ->
                progressText = "Файл ${index + 1} из ${selected.size}: ${item.path}"
                try { withContext(Dispatchers.IO) { SftpFileSync.transfer(context, uri, active, remoteRoot, item) } }
                catch (e: Exception) { failures++; errorText = "Ошибка ${item.path}: ${e.message}" }
                completed = index + 1
            }
            syncing = false
            progressText = "Передача завершена · успешно ${selected.size - failures}, ошибок $failures"
            if (failures == 0) scanFiles()
        }
    }

    val uploadCount = plan.count { it.action == SyncAction.UPLOAD }
    val downloadCount = plan.count { it.action == SyncAction.DOWNLOAD }
    val conflictCount = plan.count { it.action == SyncAction.CONFLICT }
    val sameCount = plan.count { it.action == SyncAction.SAME }
    val folderName = remember(treeUriText) {
        if (treeUriText.isBlank()) "Не выбрана"
        else runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUriText))?.name ?: "Папка выбрана" }.getOrDefault("Папка выбрана")
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Синхронизация файлов", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Двусторонний обмен между телефоном и SSD по SSH/SFTP. Удаление файлов не выполняется.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!sshConnected || ssh == null) item {
            InfoCard("Нужно SSH-подключение") {
                Text("Используется уже открытая SSH-сессия. Пароль повторно не запрашивается и не сохраняется.",
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = onOpenSsh, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Terminal, null); Spacer(Modifier.width(8.dp)); Text("Открыть SSH-терминал")
                }
            }
        }
        item {
            InfoCard("Папки синхронизации") {
                Text("Телефон · $folderName", fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Folder, null); Spacer(Modifier.width(8.dp)); Text("Выбрать папку телефона")
                }
                OutlinedTextField(remoteRoot, { remoteRoot = it }, Modifier.fillMaxWidth(),
                    label = { Text("Папка на SSD") }, supportingText = { Text("Например: /mnt/sda/RouterDashSync") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(uploadEnabled, { uploadEnabled = it }); Text("Телефон → SSD")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(downloadEnabled, { downloadEnabled = it }); Text("SSD → телефон")
                }
                Button(onClick = { scanFiles() }, enabled = sshConnected && !scanning && !syncing &&
                    treeUriText.isNotBlank() && remoteRoot.startsWith("/"), modifier = Modifier.fillMaxWidth()) {
                    if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Search, null)
                    Spacer(Modifier.width(8.dp)); Text(if (scanning) "Сканирование…" else "Сравнить файлы")
                }
            }
        }
        if (plan.isNotEmpty()) item {
            InfoCard("Результат сравнения") {
                InfoLine("Всего файлов", plan.size.toString())
                InfoLine("Телефон → SSD", uploadCount.toString())
                InfoLine("SSD → телефон", downloadCount.toString())
                InfoLine("Конфликты", conflictCount.toString())
                InfoLine("Без изменений", sameCount.toString())
                Button(onClick = { startSync() }, enabled = sshConnected && !syncing && !scanning &&
                    ((uploadEnabled && uploadCount > 0) || (downloadEnabled && downloadCount > 0)),
                    modifier = Modifier.fillMaxWidth()) {
                    if (syncing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Sync, null)
                    Spacer(Modifier.width(8.dp)); Text("Синхронизировать выбранное")
                }
            }
        }
        if (syncing) item {
            InfoCard("Передача файлов") {
                LinearProgressIndicator(progress = { if (total > 0) completed.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth())
                Text("$completed из $total · $progressText", style = MaterialTheme.typography.bodySmall)
            }
        } else if (progressText.isNotBlank()) item {
            Text(progressText, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
        }
        if (errorText != null) item {
            InfoCard("Сообщение") { Text(errorText!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        val changed = plan.filter { it.action != SyncAction.SAME }
        if (changed.isNotEmpty()) {
            item { Text("Файлы к обработке", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(changed, key = { it.path }) { item ->
                InfoCard(item.path) {
                    Text(item.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when (item.action) {
                        SyncAction.UPLOAD -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CloudUpload, null, tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(6.dp)); Text("Телефон → SSD")
                        }
                        SyncAction.DOWNLOAD -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CloudDownload, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp)); Text("SSD → телефон")
                        }
                        SyncAction.CONFLICT -> {
                            Text("Выбери версию для сохранения:")
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(false, onClick = {
                                    val i = plan.indexOfFirst { it.path == item.path }
                                    if (i >= 0) plan[i] = item.copy(action = SyncAction.UPLOAD, note = "Выбрана версия телефона")
                                }, label = { Text("Телефон") })
                                FilterChip(false, onClick = {
                                    val i = plan.indexOfFirst { it.path == item.path }
                                    if (i >= 0) plan[i] = item.copy(action = SyncAction.DOWNLOAD, note = "Выбрана версия SSD")
                                }, label = { Text("SSD") })
                            }
                        }
                        SyncAction.SAME -> Unit
                    }
                    Text("Телефон: ${formatBytes(item.localSize)} · SSD: ${formatBytes(item.remoteSize)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (plan.isNotEmpty()) item {
            Text("Все файлы синхронизированы.", color = MaterialTheme.colorScheme.secondary)
        }
        item {
            InfoCard("Автозагрузка фотографий") {
                Text("RouterDash будет проверять выбранную папку телефона в фоне и отправлять на SSD только новые фотографии. Работает без открытого приложения, когда доступна сеть.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = autoEnabled, onCheckedChange = { enabled ->
                        autoError = null
                        if (!enabled) {
                            AutoPhotoSync.cancel(context)
                            prefs.edit().putBoolean("auto_photo_enabled", false).apply()
                            securePrefs.edit().remove("auto_sync_ssh_host").remove("auto_sync_ssh_user")
                                .remove("auto_sync_ssh_password").remove("auto_sync_ssh_port").apply()
                            autoEnabled = false
                            autoPasswordSaved = false
                            autoPassword = ""
                            autoStatus = "Автозагрузка выключена."
                        } else {
                            val port = autoPort.toIntOrNull()
                            if (treeUriText.isBlank()) autoError = "Сначала выбери папку телефона выше."
                            else if (autoHost.isBlank() || autoUser.isBlank() || port == null || port !in 1..65535)
                                autoError = "Проверь SSH-адрес, пользователя и порт."
                            else if (autoPassword.isBlank() && !autoPasswordSaved)
                                autoError = "Для фонового подключения введи SSH-пароль."
                            else try {
                                securePrefs.edit().putString("auto_sync_ssh_host", autoHost.trim())
                                    .putString("auto_sync_ssh_user", autoUser.trim())
                                    .putInt("auto_sync_ssh_port", port)
                                    .apply()
                                if (autoPassword.isNotBlank()) {
                                    securePrefs.edit().putString("auto_sync_ssh_password", autoPassword).apply()
                                    autoPasswordSaved = true
                                    autoPassword = ""
                                }
                                prefs.edit().putBoolean("auto_photo_enabled", true)
                                    .putInt("auto_interval_minutes", autoInterval).apply()
                                AutoPhotoSync.schedule(context, autoInterval.toLong())
                                autoEnabled = true
                                autoStatus = "Автозагрузка включена. Первый запуск будет выполнен по расписанию."
                            } catch (e: Exception) { autoError = e.message ?: "Не удалось включить автозагрузку." }
                        }
                    })
                    Spacer(Modifier.width(8.dp))
                    Text(if (autoEnabled) "Включена" else "Выключена", fontWeight = FontWeight.SemiBold)
                }
                OutlinedTextField(autoHost, { autoHost = it }, Modifier.fillMaxWidth(),
                    label = { Text("SSH-адрес роутера") }, singleLine = true)
                OutlinedTextField(autoPort, { autoPort = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(),
                    label = { Text("SSH-порт") }, singleLine = true)
                OutlinedTextField(autoUser, { autoUser = it }, Modifier.fillMaxWidth(),
                    label = { Text("SSH-пользователь") }, singleLine = true)
                OutlinedTextField(autoPassword, { autoPassword = it }, Modifier.fillMaxWidth(),
                    label = { Text(if (autoPasswordSaved) "Новый SSH-пароль (необязательно)" else "SSH-пароль") },
                    supportingText = { Text(if (autoPasswordSaved) "Пароль уже сохранён в зашифрованном хранилище. Оставь пустым, чтобы не менять." else "Для фонового подключения пароль будет сохранён в Android Keystore.") },
                    singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                Text("Проверка каждые:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 60, 180, 360, 1440).forEach { minutes ->
                        FilterChip(selected = autoInterval == minutes, onClick = { autoInterval = minutes },
                            label = { Text(if (minutes < 60) "${minutes}м" else if (minutes == 1440) "24ч" else "${minutes / 60}ч") })
                    }
                }
                if (autoEnabled) {
                    Text(autoStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    OutlinedButton(onClick = {
                        autoStatus = prefs.getString("auto_last_status", "Пока запусков не было.") ?: "Пока запусков не было."
                    }, modifier = Modifier.fillMaxWidth()) { Text("Обновить статус") }
                    OutlinedButton(onClick = {
                        try {
                            prefs.edit().putInt("auto_interval_minutes", autoInterval).apply()
                            AutoPhotoSync.schedule(context, autoInterval.toLong())
                            autoStatus = "Расписание обновлено."
                            autoError = null
                        } catch (e: Exception) { autoError = e.message }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить интервал") }
                }
                if (autoError != null) Text(autoError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("Минимальный интервал Android — 15 минут. Работа зависит от ограничений энергосбережения производителя. Пароль SSH хранится отдельно и зашифрован системным Android Keystore. Автозагрузка отправляет только фото; конфликты пропускаются, SSD → телефон и удаление не выполняются.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text("Ручная синхронизация: конфликты требуют выбора версии. Удаление и зеркальное удаление отключены. На роутере должен быть доступен SFTP-сервер.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

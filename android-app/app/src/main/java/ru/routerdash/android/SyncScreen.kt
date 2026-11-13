package ru.routerdash.android

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

internal data class RemoteSyncEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long
)

private data class TransferResult(val copied: Int, val skipped: Int, val bytes: Long)

@Composable
internal fun SyncScreen(
    sshConnected: Boolean,
    ssh: SshTerminal?,
    sshHostDefault: String,
    sshUserDefault: String,
    onOpenSsh: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var localTreeUri by remember { mutableStateOf<Uri?>(null) }
    var remotePath by remember { mutableStateOf("/mnt/sda/RouterDash") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Выбери папку телефона и укажи каталог SSD на роутере.") }
    var lastResult by remember { mutableStateOf<TransferResult?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            localTreeUri = uri
            status = "Папка телефона выбрана. Можно запускать передачу файлов."
            lastResult = null
        }
    }

    val localTree = remember(localTreeUri) {
        localTreeUri?.let { DocumentFile.fromTreeUri(context, it) }
    }

    fun runTransfer(upload: Boolean) {
        val activeSsh = ssh
        val tree = localTree
        val target = remotePath.trim().trimEnd('/').ifBlank { "/" }
        if (!sshConnected || activeSsh == null) {
            status = "Сначала подключись к роутеру по SSH."
            return
        }
        if (tree == null || !tree.isDirectory) {
            status = "Сначала выбери локальную папку на телефоне."
            return
        }
        if (!target.startsWith("/") || target.split('/').any { it == ".." }) {
            status = "Укажи абсолютный путь на SSD без сегментов '..'."
            return
        }
        busy = true
        lastResult = null
        status = if (upload) "Проверяю SSD и отправляю файлы…" else "Проверяю SSD и загружаю файлы…"
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    activeSsh.verifyMountedStorage(target)
                    activeSsh.ensureRemoteDirectory(target)
                    if (upload) {
                        uploadDirectory(context, activeSsh, tree, target)
                    } else {
                        downloadDirectory(context, activeSsh, target, tree)
                    }
                }
                lastResult = result
                status = if (upload) "Передача на SSD завершена." else "Загрузка с SSD завершена."
            } catch (e: Exception) {
                status = "Ошибка: ${e.message ?: "не удалось выполнить передачу"}"
            } finally {
                busy = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            InfoCard("Синхронизация с SSD") {
                Text(
                    "Передача папок между телефоном и внешним диском роутера по защищённому SSH/SFTP. " +
                        "Внутренняя память OpenWrt (/overlay, /rom и /tmp) не используется.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = onOpenSsh,
                        label = { Text(if (sshConnected) "SSH подключён" else "Подключить SSH") },
                        leadingIcon = { Icon(Icons.Outlined.Folder, null) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (sshConnected) "SFTP готов" else "Нужно SSH-соединение",
                        color = if (sshConnected) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                OutlinedButton(
                    onClick = { folderPicker.launch(localTreeUri) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                ) {
                    Icon(Icons.Outlined.Folder, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (localTree == null) "Выбрать папку на телефоне" else "Изменить локальную папку")
                }
                Text(
                    localTree?.name ?: "Локальная папка не выбрана",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                OutlinedTextField(
                    value = remotePath,
                    onValueChange = { remotePath = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Папка на SSD роутера") },
                    singleLine = true,
                    enabled = !busy,
                    supportingText = { Text("Например: /mnt/sda/RouterDash") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { runTransfer(true) },
                        modifier = Modifier.weight(1f),
                        enabled = !busy && sshConnected && localTree != null
                    ) {
                        Icon(Icons.Outlined.CloudUpload, null)
                        Spacer(Modifier.width(6.dp))
                        Text("На SSD")
                    }
                    OutlinedButton(
                        onClick = { runTransfer(false) },
                        modifier = Modifier.weight(1f),
                        enabled = !busy && sshConnected && localTree != null
                    ) {
                        Icon(Icons.Outlined.CloudDownload, null)
                        Spacer(Modifier.width(6.dp))
                        Text("На телефон")
                    }
                }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Не закрывай приложение до завершения передачи.", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            InfoCard("Состояние передачи") {
                Text(status, style = MaterialTheme.typography.bodyMedium)
                lastResult?.let { result ->
                    HorizontalDivider()
                    InfoLine("Скопировано файлов", result.copied.toString())
                    InfoLine("Пропущено", result.skipped.toString())
                    InfoLine("Передано", formatBytes(result.bytes))
                }
                Text(
                    "Передача выполняется только по нажатию кнопки. Существующие файлы с одинаковым именем могут быть перезаписаны; перед первой синхронизацией рекомендуется проверить содержимое выбранных папок.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        item {
            InfoCard("Важно") {
                Text("На роутере должен быть включён SSH и установлен SFTP-сервер (обычно openssh-sftp-server).")
                Text("Путь SSD проверяется по /proc/mounts. Если диск не смонтирован, операция будет остановлена.")
                Text("Это копирование в выбранном направлении: файлы в исходной папке не удаляются.")
            }
        }
    }
}

private fun uploadDirectory(
    context: android.content.Context,
    ssh: SshTerminal,
    localDirectory: DocumentFile,
    remoteDirectory: String
): TransferResult {
    var copied = 0
    var skipped = 0
    var bytes = 0L

    fun walk(local: DocumentFile, remote: String) {
        local.listFiles().forEach { child ->
            val name = child.name?.takeIf { it.isNotBlank() } ?: return@forEach
            require(name != "." && name != ".." && !name.contains('/')) { "Недопустимое имя файла: $name" }
            val remoteChild = "$remote/$name"
            if (child.isDirectory) {
                ssh.ensureRemoteDirectory(remoteChild)
                walk(child, remoteChild)
            } else if (child.isFile) {
                val size = child.length()
                val input = context.contentResolver.openInputStream(child.uri)
                    ?: throw FileNotFoundException("Не удалось открыть локальный файл $name")
                input.use { ssh.uploadFile(it, remoteChild) }
                copied++
                bytes += size
            }
        }
    }

    walk(localDirectory, remoteDirectory)
    return TransferResult(copied, skipped, bytes)
}

private fun downloadDirectory(
    context: android.content.Context,
    ssh: SshTerminal,
    remoteDirectory: String,
    localDirectory: DocumentFile
): TransferResult {
    var copied = 0
    var skipped = 0
    var bytes = 0L

    fun walk(remote: String, local: DocumentFile) {
        ssh.listRemote(remote).forEach { entry ->
            val name = entry.name
            if (name.isBlank() || name == "." || name == ".." || name.contains('/')) return@forEach
            if (entry.isDirectory) {
                val directory = local.findFile(name)?.takeIf { it.isDirectory }
                    ?: local.createDirectory(name)
                    ?: throw IllegalStateException("Не удалось создать папку $name")
                walk("$remote/$name", directory)
            } else {
                val existing = local.findFile(name)
                if (existing != null && existing.isDirectory) {
                    throw IllegalStateException("В телефоне вместо файла $name уже существует папка.")
                }
                val mime = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                    ?: "application/octet-stream"
                val destination = existing ?: local.createFile(mime, name)
                    ?: throw IllegalStateException("Не удалось создать файл $name")
                val output = context.contentResolver.openOutputStream(destination.uri, "wt")
                    ?: throw FileNotFoundException("Не удалось записать файл $name")
                output.use { ssh.downloadFile("$remote/$name", it) }
                copied++
                bytes += entry.size
            }
        }
    }

    walk(remoteDirectory, localDirectory)
    return TransferResult(copied, skipped, bytes)
}

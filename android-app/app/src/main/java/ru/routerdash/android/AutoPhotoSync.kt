package ru.routerdash.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal object AutoPhotoSync {
    private const val UNIQUE_WORK = "routerdash_auto_photo_sync"

    fun schedule(context: Context, intervalMinutes: Long) {
        require(intervalMinutes >= 15L) { "Минимальный интервал фоновой синхронизации — 15 минут." }
        val request = PeriodicWorkRequestBuilder<AutoPhotoSyncWorker>(
            intervalMinutes.coerceIn(15L, 24L * 60L), TimeUnit.MINUTES
        ).setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        ).setBackoffCriteria(
            androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
    }
}

internal class AutoPhotoSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val status = applicationContext.getSharedPreferences("routerdash_sync", Context.MODE_PRIVATE)
        val secure = runCatching { encryptedCredentials(applicationContext) }.getOrElse {
            status.edit().putString("auto_last_status", "Не удалось открыть защищённые настройки: ${it.message}").apply()
            return@withContext Result.failure()
        }
        val host = secure.getString("auto_sync_ssh_host", null)
        val user = secure.getString("auto_sync_ssh_user", null)
        val password = secure.getString("auto_sync_ssh_password", null)
        val port = secure.getInt("auto_sync_ssh_port", 22)
        val treeText = status.getString("tree_uri", null)
        val remoteRoot = status.getString("remote_root", "/mnt/sda/RouterDashSync") ?: "/mnt/sda/RouterDashSync"
        if (host.isNullOrBlank() || user.isNullOrBlank() || password.isNullOrBlank() || treeText.isNullOrBlank()) {
            status.edit().putString("auto_last_status", "Не заданы папка телефона или SSH-реквизиты. Открой настройки синхронизации.").apply()
            return@withContext Result.failure()
        }

        val ssh = SshTerminal(applicationContext)
        try {
            val uri = android.net.Uri.parse(treeText)
            ssh.connect(host, port, user, password)
            val plan = SftpFileSync.scan(applicationContext, uri, ssh, remoteRoot)
            val photoExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "dng", "gif", "tif", "tiff", "bmp")
            val photos = plan.filter { item ->
                item.action == SyncAction.UPLOAD &&
                    item.path.substringAfterLast('.', "").lowercase() in photoExtensions
            }
            var uploaded = 0
            var skippedConflicts = plan.count { it.action == SyncAction.CONFLICT &&
                it.path.substringAfterLast('.', "").lowercase() in photoExtensions }
            photos.forEach { item ->
                if (isStopped) throw InterruptedException("Фоновая синхронизация остановлена.")
                SftpFileSync.transfer(applicationContext, uri, ssh, remoteRoot, item)
                uploaded++
            }
            val message = "Автозагрузка завершена: фото отправлено $uploaded" +
                if (skippedConflicts > 0) ", конфликтов пропущено $skippedConflicts" else ""
            status.edit().putString("auto_last_status", message)
                .putLong("auto_last_run", System.currentTimeMillis()).apply()
            Result.success()
        } catch (e: InterruptedException) {
            status.edit().putString("auto_last_status", "Синхронизация остановлена.").apply()
            Result.failure()
        } catch (e: Exception) {
            status.edit().putString("auto_last_status", "Ошибка фоновой синхронизации: ${e.message ?: "неизвестная ошибка"}")
                .putLong("auto_last_run", System.currentTimeMillis()).apply()
            Result.retry()
        } finally {
            runCatching { ssh.close() }
        }
    }
}

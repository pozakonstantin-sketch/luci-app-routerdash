package ru.routerdash.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class RouterDashWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, manager, it) }
    }

    override fun onEnabled(context: Context) {
        refreshAll(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        updateWidget(context, manager, appWidgetId)
    }

    companion object {
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RouterDashWidgetProvider::class.java))
            ids.forEach { updateWidget(context, manager, it) }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val prefs = context.getSharedPreferences("routerdash_widget_cache", Context.MODE_PRIVATE)
            val options = manager.getAppWidgetOptions(widgetId)
            val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val layoutId = if (widthDp >= 250) R.layout.routerdash_widget_expanded else R.layout.routerdash_widget
            val views = RemoteViews(context.packageName, layoutId)
            val online = prefs.getBoolean("online", false)
            val updatedAt = prefs.getLong("updated_at", 0L)
            val fresh = updatedAt > 0L && System.currentTimeMillis() - updatedAt < 120_000L

            views.setTextViewText(R.id.widget_hostname, prefs.getString("hostname", "RouterDash") ?: "RouterDash")
            views.setTextViewText(R.id.widget_model, prefs.getString("model", "OpenWrt") ?: "OpenWrt")
            views.setTextViewText(R.id.widget_status, when {
                online && fresh -> "● В сети"
                online -> "● Нет свежих данных"
                else -> "○ Откройте приложение для подключения"
            })
            views.setTextViewText(R.id.widget_cpu, metric(prefs.getInt("cpu_percent", -1), "CPU"))
            views.setTextViewText(R.id.widget_ram, metric(prefs.getInt("ram_percent", -1), "RAM"))
            views.setTextViewText(R.id.widget_rx, "↓ " + (prefs.getString("rx_rate", "—") ?: "—"))
            views.setTextViewText(R.id.widget_tx, "↑ " + (prefs.getString("tx_rate", "—") ?: "—"))
            views.setTextViewText(R.id.widget_uptime, "Uptime · " + (prefs.getString("uptime", "—") ?: "—"))

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            manager.updateAppWidget(widgetId, views)
        }

        private fun metric(value: Int, label: String): String =
            if (value in 0..100) "$label  $value%" else "$label  —"
    }
}

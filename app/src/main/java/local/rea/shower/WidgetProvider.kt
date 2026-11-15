package local.rea.shower

import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.net.Uri
import android.widget.RemoteViews
import android.widget.Toast
import com.google.gson.JsonObject
import local.rea.shower.core.*

class WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAll(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) = updateAll(context)
    companion object {
        fun updateAll(context: Context) {
            val runtime = AppRuntime.get(context)
            val s = runtime.snapshot()
            val busy = runtime.busy.get()
            val manager = AppWidgetManager.getInstance(context)
            for (id in manager.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java))) {
                manager.updateAppWidget(id, createViews(context, s, busy, id))
            }
        }
        fun createViews(context: Context, s: JsonObject, busy: Boolean, id: Int): RemoteViews {
            val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
            // Launcher bounds describe both orientations; do not size portrait using the landscape minimum height.
            val portrait = createSizedViews(context, s, busy, id,
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 166),
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 200))
            if (!options.containsKey(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)) return portrait
            val landscape = createSizedViews(context, s, busy, id,
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 200),
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 166))
            return RemoteViews(landscape, portrait)
        }
        fun createSizedViews(context: Context, s: JsonObject, busy: Boolean, id: Int,
            widthDp: Int, heightDp: Int, bindActions: Boolean = true): RemoteViews {
            val model = widgetState(s, busy)
            val layout = if (widthDp < 148 || heightDp < 144) R.layout.widget_shower_compact else R.layout.widget_shower
            val views = RemoteViews(context.packageName, layout)
            views.setTextViewText(R.id.widget_device, model.number)
            views.setTextViewText(R.id.widget_status, model.status)
            views.setTextViewText(R.id.widget_balance, model.balanceText)
            views.setTextColor(R.id.widget_balance, when (model.balanceTone) {
                "above" -> context.getColor(R.color.ui_positive)
                "low" -> context.getColor(R.color.ui_problem)
                else -> context.getColor(R.color.ui_muted)
            })
            val (statusBackground, statusColor) = when {
                model.status == "结果未确认" -> R.drawable.widget_status_warning to R.color.ui_warning
                model.status in setOf("设备故障", "操作失败", "编号重复", "状态未知") -> R.drawable.widget_status_problem to R.color.ui_problem
                model.action == "close" && model.enabled -> R.drawable.widget_status_active to R.color.ui_active
                model.status == "空闲" -> R.drawable.widget_status_ready to R.color.ui_primary
                else -> R.drawable.widget_status_neutral to R.color.ui_muted
            }
            views.setInt(R.id.widget_status, "setBackgroundResource", statusBackground)
            views.setTextColor(R.id.widget_status, context.getColor(statusColor))
            views.setTextViewText(R.id.widget_action, model.buttonText)
            val balanceDescription = model.balanceText + when (model.balanceTone) {
                "above" -> "，高于单次限额"
                "low" -> "，不高于单次限额"
                else -> "，暂未获取"
            }
            views.setContentDescription(R.id.widget_header,
                context.getString(R.string.widget_header_description, model.target.ifEmpty { "未选择" }, model.status, balanceDescription))
            views.setContentDescription(R.id.widget_action, if (model.enabled)
                context.getString(R.string.widget_action_description, model.buttonText, model.target)
                else context.getString(R.string.widget_action_disabled, model.buttonText, model.status))
            if (bindActions) {
                val openApp = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                views.setOnClickPendingIntent(R.id.widget_header, openApp)
                val command = AppRuntime.intent(context, Action(model.action, s.s("revision"), target = model.target)).apply {
                    data = Uri.parse("shower://widget/$id/${model.action}/${s.s("revision")}")
                }
                val pending = PendingIntent.getForegroundService(context, id, command, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                views.setOnClickPendingIntent(R.id.widget_action, if (model.enabled) pending else null)
            }
            views.setBoolean(R.id.widget_action, "setEnabled", model.enabled)
            views.setTextColor(R.id.widget_action, context.getColor(when {
                !model.enabled -> R.color.ui_muted
                model.action == "close" -> R.color.ui_on_active
                else -> R.color.ui_on_primary
            }))
            views.setInt(R.id.widget_action, "setBackgroundResource", when {
                !model.enabled -> R.drawable.button_disabled
                model.action == "close" -> R.drawable.button_close
                else -> R.drawable.button_primary
            })
            return views
        }
        fun phaseLabel(phase: String) = when (phase) {
            "open" -> "已确认开阀"
            "closed" -> "已确认关阀"
            "opening" -> "正在开阀…"
            "closing" -> "正在关阀…"
            "uncertain" -> "结果未确认 · 请检查水流"
            else -> "等待操作"
        }
    }
}

class WidgetPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WidgetProvider.updateAll(context)
        Toast.makeText(context, "淋浴小组件已添加到桌面", Toast.LENGTH_LONG).show()
    }
}

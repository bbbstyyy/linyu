package local.rea.shower

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.appwidget.AppWidgetManager
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.*
import android.view.*
import android.widget.*
import com.google.gson.JsonObject
import local.rea.shower.core.*

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var runtime: AppRuntime
    private lateinit var screen: ShowerScreen
    private var pinHint = ""
    private var pinPending = false
    private var pinBaseline = intArrayOf()
    private var resumed = false
    private val verifyPin = Runnable { verifyWidgetPin() }
    private val poll = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 700) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lightBars = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        // PhoneWindow.getInsetsController() dereferences its decor without creating it.
        // Install the decor first; its controller also accepts appearance before attachment.
        val decor = window.decorView
        if (Build.VERSION.SDK_INT >= 30) decor.windowInsetsController?.setSystemBarsAppearance(
            if (lightBars) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        else {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = if (lightBars) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        }
        try { runtime = AppRuntime.get(this) }
        catch (_: Exception) {
            setContentView(TextView(this).apply { text = "本机登录信息无法解密或读取。请在系统应用设置中清除本应用数据，再重新登录。"; setPadding(40, 100, 40, 40) })
            return
        }
        screen = ShowerScreen(this, ::run, ::confirmOpen, ::confirmLogout, ::requestWidgetPin, ::manualWidgetGuide)
        screen.restoreState(savedInstanceState)
        pinHint = savedInstanceState?.getString("widget_pin_hint", "") ?: ""
        pinPending = savedInstanceState?.getBoolean("widget_pin_pending", false) ?: false
        pinBaseline = savedInstanceState?.getIntArray("widget_pin_baseline") ?: intArrayOf()
        screen.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(screen)
        screen.requestApplyInsets()
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { navigateBack() }
        AppRuntime.schedule(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("notification_asked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notification_asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }
    override fun onResume() {
        super.onResume()
        resumed = true
        if (::screen.isInitialized) {
            WidgetProvider.updateAll(this); handler.post(poll)
            if (pinPending) schedulePinVerification(700)
            val s = runtime.snapshot()
            if (s.o("auth").has("user") && !s.o("auth").b("renewal_blocked") && !s.has("funds"))
                run(Action("refresh", s.s("revision")))
        }
    }
    override fun onPause() {
        resumed = false
        handler.removeCallbacks(poll); handler.removeCallbacks(verifyPin)
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::screen.isInitialized) screen.saveState(outState)
        outState.putString("widget_pin_hint", pinHint)
        outState.putBoolean("widget_pin_pending", pinPending)
        outState.putIntArray("widget_pin_baseline", pinBaseline)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
    // API 33+ uses the registered OnBackInvokedCallback above; only older systems call this.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Legacy Android back dispatcher")
    override fun onBackPressed() { navigateBack() }
    private fun navigateBack() {
        if (::screen.isInitialized && screen.selectedTab != 0) screen.selectTab(0)
        else finish()
    }
    private fun run(action: Action) {
        if (runtime.busy.get()) return
        try { startForegroundService(AppRuntime.intent(this, action)) }
        catch (_: Exception) { Toast.makeText(this, "系统暂未允许执行，请保持应用打开后重试。", Toast.LENGTH_LONG).show() }
    }
    private fun render() {
        val count = AppWidgetManager.getInstance(this).getAppWidgetIds(ComponentName(this, WidgetProvider::class.java)).size
        screen.render(runtime.snapshot(), runtime.busy.get(), runtime.busyLabel, count, pinHint, pinPending)
    }
    private fun confirmLogout(state: JsonObject) {
        AlertDialog.Builder(this).setTitle("退出登录？").setMessage("请先确认现场已停水。退出将清除本机凭据和设备选择。")
            .setNegativeButton("取消", null).setPositiveButton("已停水，退出") { _, _ ->
                run(Action("logout", state.s("revision"), confirmedStopped = true))
            }.show()
    }
    private fun confirmOpen(state: JsonObject, target: String) {
        AlertDialog.Builder(this).setTitle("开启设备 $target？")
            .setMessage("确认现场是这台设备，用水结束后请点击关阀。")
            .setNegativeButton("取消", null).setPositiveButton("确认开阀") { _, _ ->
                run(Action("open", state.s("revision"), target = target))
            }.show()
    }
    private fun requestWidgetPin() {
        if (pinPending) return
        val manager = AppWidgetManager.getInstance(this)
        try {
            if (!manager.isRequestPinAppWidgetSupported) {
                pinHint = "当前桌面不支持快捷添加，请按说明从桌面添加。"
                render(); manualWidgetGuide(); return
            }
            pinBaseline = manager.getAppWidgetIds(ComponentName(this, WidgetProvider::class.java))
            pinPending = true
            pinHint = "正在等待桌面确认；如未出现弹窗，会显示手动添加说明。"
            render()
            val callback = PendingIntent.getBroadcast(this, 902, Intent(this, WidgetPinnedReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val preview = Bundle().apply {
                putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, WidgetProvider.createSizedViews(this@MainActivity, runtime.snapshot(), false, 0, 166, 200, bindActions = false))
            }
            val accepted = manager.requestPinAppWidget(ComponentName(this, WidgetProvider::class.java), preview, callback)
            if (!accepted) {
                pinPending = false
                pinHint = "桌面未接受添加请求，请按说明从桌面添加。"
                render(); manualWidgetGuide(); return
            }
            // A true return only acknowledges the request. Some launchers immediately
            // finish their confirmation activity, so verify a new widget after returning.
            schedulePinVerification(1800)
        } catch (_: Exception) {
            pinPending = false
            pinHint = "快捷添加暂时不可用，请按说明从桌面添加。"
            render(); manualWidgetGuide()
        }
    }
    private fun schedulePinVerification(delay: Long) {
        handler.removeCallbacks(verifyPin)
        handler.postDelayed(verifyPin, delay)
    }
    private fun verifyWidgetPin() {
        if (!pinPending || !resumed || isFinishing || isDestroyed) return
        val ids = AppWidgetManager.getInstance(this).getAppWidgetIds(ComponentName(this, WidgetProvider::class.java))
        pinPending = false
        if (ids.any { it !in pinBaseline }) {
            pinHint = "小组件已添加到桌面。"
            render()
        } else {
            pinHint = "桌面未完成添加，请按说明手动添加。"
            render(); manualWidgetGuide()
        }
    }
    private fun manualWidgetGuide() {
        pinPending = false
        handler.removeCallbacks(verifyPin)
        render()
        AlertDialog.Builder(this).setTitle("从桌面添加小组件")
            .setMessage("若没有出现系统确认窗口，或已取消添加，请按以下步骤操作：\n\n1. 点击“返回桌面”，长按桌面空白处，或双指向内捏合。\n2. 打开“小组件”“插件”“添加工具”或“小部件”；部分桌面需再选“安卓小组件”。\n3. 找到“淋浴小助手”，拖动到桌面，预留 2 列、2 行空间。\n\n若无法放置，请先在桌面设置中关闭“锁定桌面布局”，并留出空白位置。已有小组件可直接使用；需要调整尺寸时，请长按缩小。")
            .setNegativeButton("留在应用", null)
            .setPositiveButton("返回桌面") { _, _ -> startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }.show()
    }
}

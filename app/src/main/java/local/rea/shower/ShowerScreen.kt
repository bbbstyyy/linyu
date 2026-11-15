package local.rea.shower

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.google.gson.JsonObject
import local.rea.shower.core.*
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.*

/** Native app layout. All effects are supplied by the host, so previews use the same views offline. */
class ShowerScreen(context: Context,
    private val onAction: (Action) -> Unit,
    private val onOpen: (JsonObject, String) -> Unit,
    private val onLogout: (JsonObject) -> Unit,
    private val onPin: () -> Unit,
    private val onGuide: () -> Unit
) : LinearLayout(context) {
    private val ink get() = context.getColor(R.color.ui_text)
    private val muted get() = context.getColor(R.color.ui_muted)
    private val accent get() = context.getColor(R.color.ui_primary)
    private val scroll = ScrollView(context).apply { isFillViewport = true; clipToPadding = false }
    private val content = LinearLayout(context).apply { orientation = VERTICAL }
    private val navigation = RadioGroup(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
    private val tabs = mutableListOf<RadioButton>()
    var selectedTab = 0
        private set
    private val positions = intArrayOf(0, 0)
    private var phone: EditText? = null
    private var sms: EditText? = null
    private var phoneDraft = ""
    private var smsDraft = ""
    private var sendButton: Button? = null
    private var state = JsonObject()
    private var busy = false
    private var busyLabel = ""
    private var widgetCount = 0
    private var pinHint = ""
    private var pinPending = false
    private var key = ""
    private var loggedBefore = false
    private var receivedSnapshot = false

    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.ui_background))
        scroll.addView(content, FrameLayout.LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, 0, 1f))
        addView(View(context).apply { setBackgroundColor(context.getColor(R.color.ui_outline)) }, LayoutParams(-1, dp(1)))
        navigation.setBackgroundColor(context.getColor(R.color.ui_surface))
        navigation.setPadding(dp(16), dp(8), dp(16), dp(8))
        listOf("设备" to R.drawable.ic_tab_device, "我的" to R.drawable.ic_tab_profile).forEachIndexed { index, (label, icon) ->
            val colors = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, muted))
            val tab = RadioButton(context).apply {
                id = View.generateViewId(); tag = "tab_$index"; text = label; textSize = 12f
                buttonDrawable = null; gravity = Gravity.CENTER; minHeight = dp(64)
                setPadding(dp(8), dp(8), dp(8), dp(8)); setTextColor(colors)
                setCompoundDrawables(null, context.getDrawable(icon)?.apply { setBounds(0, 0, dp(24), dp(24)) }, null, null)
                compoundDrawableTintList = colors; compoundDrawablePadding = dp(4)
                background = context.getDrawable(R.drawable.tab_background)
                setOnClickListener { selectTab(index) }
            }
            tabs.add(tab)
            navigation.addView(tab, LayoutParams(0, -2, 1f).apply { if (index == 0) marginEnd = dp(8) })
        }
        tabs[0].isChecked = true
        addView(navigation, LayoutParams(-1, -2))
    }

    fun saveState(out: Bundle) {
        captureDrafts(); positions[selectedTab] = scroll.scrollY
        out.putInt("screen_tab", selectedTab); out.putIntArray("screen_positions", positions)
        out.putString("screen_phone", phoneDraft); out.putString("screen_sms", smsDraft)
    }
    fun restoreState(saved: Bundle?) {
        if (saved == null) return
        selectedTab = saved.getInt("screen_tab").coerceIn(0, 1)
        saved.getIntArray("screen_positions")?.takeIf { it.size == 2 }?.copyInto(positions)
        phoneDraft = saved.getString("screen_phone", ""); smsDraft = saved.getString("screen_sms", "")
    }
    private fun captureDrafts() {
        phone?.let { phoneDraft = it.text.toString() }; sms?.let { smsDraft = it.text.toString() }
    }
    fun selectTab(tab: Int) {
        if (tab !in 0..1 || selectedTab == tab) return
        captureDrafts(); positions[selectedTab] = scroll.scrollY
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
        clearFocus(); selectedTab = tab; key = ""
        rebuild(positions[tab])
    }
    fun render(snapshot: JsonObject, isBusy: Boolean, status: String, count: Int, hint: String, awaitingPin: Boolean = false) {
        val nextKey = snapshot.s("revision") + "|$isBusy|$status|$count|$hint|$selectedTab|$awaitingPin"
        state = snapshot; busy = isBusy; busyLabel = status; widgetCount = count; pinHint = hint; pinPending = awaitingPin
        sendButton?.apply { text = smsButtonLabel(); isEnabled = !busy && smsWait() == 0L }
        if (key == nextKey) return
        captureDrafts()
        val logged = state.o("auth").has("user")
        val authChanged = receivedSnapshot && logged != loggedBefore
        if (authChanged) {
            phoneDraft = ""; smsDraft = ""; positions.fill(0)
            if (logged) selectedTab = 0
        }
        loggedBefore = logged; receivedSnapshot = true
        val position = if (key.isEmpty() || authChanged) positions[selectedTab] else scroll.scrollY
        key = nextKey
        rebuild(position)
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun smsWait() = maxOf(0L, (60_000 - (System.currentTimeMillis() - state.n("sms_requested_at")) + 999) / 1000)
    private fun smsButtonLabel() = smsWait().let { if (it > 0) "${it} 秒后可重新获取" else "获取验证码" }
    private fun date(time: Long, format: String = "MM-dd HH:mm") = SimpleDateFormat(format, Locale.CHINA).format(Date(time))
    private fun text(value: String, size: Float = 16f, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); setLineSpacing(dp(3).toFloat(), 1f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun LinearLayout.label(value: String, size: Float = 16f, color: Int = ink, bold: Boolean = false): TextView =
        text(value, size, color, bold).also { addView(it, LayoutParams(-1, -2).apply { bottomMargin = dp(8) }) }
    private fun surface(color: Int, radius: Int = 24) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), context.getColor(R.color.ui_outline))
    }
    private fun card(color: Int = context.getColor(R.color.ui_surface)) = LinearLayout(context).apply {
        orientation = VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(12)); background = surface(color)
        content.addView(this, LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    }
    private fun LinearLayout.button(label: String, primary: Boolean = false, enabled: Boolean = true,
        close: Boolean = false, tagName: String = "", onClick: () -> Unit): Button {
        val button = Button(context).apply {
            text = label; tag = tagName; textSize = 16f; isAllCaps = false; isEnabled = enabled; minHeight = dp(56); minimumWidth = 0
            val foreground = context.getColor(if (primary) { if (close) R.color.ui_on_active else R.color.ui_on_primary } else R.color.ui_primary)
            setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_selected), intArrayOf()),
                intArrayOf(muted, context.getColor(R.color.ui_on_primary), foreground)))
            backgroundTintList = null
            background = context.getDrawable(if (!enabled) R.drawable.button_disabled else if (primary && close) R.drawable.button_close else if (primary) R.drawable.button_primary else R.drawable.button_secondary)
            setPadding(dp(12), dp(12), dp(12), dp(12)); setOnClickListener { onClick() }
        }
        addView(button, LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        return button
    }
    private fun LinearLayout.input(label: String, value: String, type: Int, fieldTag: String): EditText {
        val title = label(label, 14f, muted)
        return EditText(context).apply {
            id = View.generateViewId(); tag = fieldTag; title.labelFor = id; minHeight = dp(56)
            hint = label; setText(value); textSize = 17f; setTextColor(ink); inputType = type; setSingleLine(); isSaveEnabled = false
            setPadding(dp(4), dp(10), dp(4), dp(10)); isEnabled = !busy
            this@input.addView(this, LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }
    private fun rebuild(position: Int) {
        content.removeAllViews(); phone = null; sms = null; sendButton = null
        val gutter = if (resources.configuration.screenWidthDp >= 600) maxOf(32, (resources.configuration.screenWidthDp - 600) / 2) else 20
        content.setPadding(dp(gutter), dp(24), dp(gutter), dp(16))
        tabs.forEachIndexed { i, view -> view.isChecked = selectedTab == i }
        content.label("淋浴小助手", 14f, accent, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setCompoundDrawables(context.getDrawable(R.drawable.ic_water)?.apply { setBounds(0, 0, dp(28), dp(28)) }, null, null, null)
            compoundDrawablePadding = dp(8)
        }
        content.label(if (selectedTab == 0) "设备" else "我的", 30f, ink, true)
        content.label(if (selectedTab == 0) "选好设备，安心用水" else "账户与偏好，都在这里", 14f, muted)
        val logged = state.o("auth").has("user")
        if (selectedTab == 0) devicesPage(logged) else profilePage(logged)
        scroll.post { scroll.scrollTo(0, position) }
    }
    private fun statusCard() {
        if (!busy && !state.b("error") && state.s("phase") !in setOf("opening", "open", "closing", "uncertain")) return
        val warning = state.s("phase") == "uncertain"
        card(context.getColor(if (warning) R.color.ui_warning_surface else if (state.b("error")) R.color.ui_problem_surface else R.color.ui_active_surface)).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            label(if (busy) busyLabel else WidgetProvider.phaseLabel(state.s("phase")), 16f,
                context.getColor(if (warning) R.color.ui_warning else if (state.b("error")) R.color.ui_problem else R.color.ui_active), true)
            state.s("message").takeIf { it.isNotEmpty() }?.let { label(it, 14f, muted) }
        }
    }
    private fun balanceCard() {
        val balance = widgetBalance(state)
        val color = context.getColor(when (balance.tone) { "above" -> R.color.ui_positive; "low" -> R.color.ui_problem; else -> R.color.ui_muted })
        card().apply {
            label("用水余额", 14f, muted)
            label(balance.text.removePrefix("余额 "), 34f, color, true).apply {
                tag = "balance"; contentDescription = "用水${balance.text}"; fontFeatureSettings = "tnum"
            }
            val funds = state.o("funds")
            val cap = funds.s("limit_cents").toBigDecimalOrNull()?.movePointLeft(2)?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()
            label(when (balance.tone) {
                "low" -> "余额不高于单次限额 ¥$cap，仍可开阀"
                "above" -> "单次限额 ¥$cap"
                else -> "暂未获取余额，请刷新重试"
            }, 13f, if (balance.tone == "low") color else muted)
            if (funds.n("checked_at") > 0) label("最近更新 ${date(funds.n("checked_at"))}", 12f, muted)
            if (selectedTab == 1) button(if (busy) "更新中…" else "刷新余额", enabled = !busy, tagName = "refresh_balance") {
                onAction(Action("refresh", state.s("revision")))
            }
        }
    }
    private fun devicesPage(logged: Boolean) {
        if (!logged) {
            card().apply {
                label("登录后，连接你的淋浴设备", 22f, ink, true)
                label("查看用水余额，选择已绑定设备，随时开关阀。", 16f, muted)
                button("前往「我的」登录", primary = true) { selectTab(1) }
            }
            return
        }
        balanceCard()
        statusCard()
        val active = state.s("phase") in setOf("opening", "open", "closing", "uncertain")
        val target = if (active) state.o("journal").s("termid") else state.s("selected")
        val model = widgetState(state, busy)
        val device = state.a("devices").firstOrNull { it.asJsonObject.s("term_id") == target }?.asJsonObject
        val (statusSurface, statusInk) = when {
            model.status == "结果未确认" -> R.color.ui_warning_surface to R.color.ui_warning
            model.status in setOf("设备故障", "操作失败", "编号重复", "状态未知") -> R.color.ui_problem_surface to R.color.ui_problem
            model.action == "close" -> R.color.ui_active_surface to R.color.ui_active
            model.status == "空闲" -> R.color.ui_ready_surface to R.color.ui_primary
            else -> R.color.ui_surface to R.color.ui_muted
        }
        card(context.getColor(statusSurface)).apply {
            label("当前设备", 14f, muted)
            label(if (target.isEmpty()) "尚未选择设备" else target, 26f, ink, true).tag = "current_device"
            device?.s("term_name")?.takeIf { it.isNotEmpty() }?.let { label(it, 14f, muted) }
            label(model.status, 14f, context.getColor(statusInk), true)
            if (state.o("auth").b("renewal_blocked")) {
                button("前往「我的」重新登录") { selectTab(1) }
            } else {
                button(if (model.action == "close") "关阀 · 结束用水" else "开阀 · 开始用水", primary = true,
                    close = model.action == "close", enabled = model.enabled, tagName = "primary_control") {
                    if (model.action == "close") onAction(Action("close", state.s("revision"), target = target))
                    else onOpen(state, target)
                }
            }
            // Preserve the existing explicit close path when a previous result or list is incomplete.
            if (target.isNotEmpty() && !model.enabled && !busy)
                button("尝试关阀", tagName = "fallback_close") { onAction(Action("close", state.s("revision"), target = target)) }
            label(if (active) "用水结束请及时关阀；结果未确认时，请检查现场水流。" else "开阀前请核对现场设备编号。", 12f, muted)
        }
        card().apply {
            label("已绑定设备", 20f, ink, true)
            button(if (busy) "更新中…" else "刷新设备与余额", enabled = !busy, tagName = "refresh_devices") { onAction(Action("refresh", state.s("revision"))) }
            if (state.a("devices").size() == 0) label("暂无设备。请先在wmxy绑定淋浴设备，再刷新列表。", 14f, muted)
            if (active) label("当前用水尚未结束，关阀后可切换设备。", 13f, muted)
            for (item in state.a("devices")) {
                val d = item.asJsonObject
                val selected = d.s("term_id") == target
                val status = when (d.s("term_status")) { "0" -> "空闲"; "1" -> "本账号使用中"; "2" -> "故障"; "3" -> "他人使用中"; else -> "状态未知" }
                val reason = WaterApi.reason(d, state.a("devices"))
                button("${if (selected) "已选择 · " else ""}${d.s("term_name").ifEmpty { "淋浴设备" }}\n${d.s("term_id")} · $status",
                    enabled = !busy && !active && reason.isEmpty(), tagName = "device_${d.s("term_id")}") {
                    onAction(Action("select", state.s("revision"), target = d.s("term_id")))
                }.apply { isSelected = selected; contentDescription = text.toString() + if (reason.isNotEmpty()) "。$reason" else "" }
            }
            if (state.n("listed_at") > 0) label("列表更新于 ${date(state.n("listed_at"))} · 操作前会重新查询", 12f, muted)
        }
    }
    private fun profilePage(logged: Boolean) {
        statusCard()
        if (state.s("phase") in setOf("opening", "open", "closing", "uncertain"))
            card().button("返回设备，查看用水状态") { selectTab(0) }
        if (!logged) loginCard() else {
            card().apply {
                label("wmxy账号", 14f, muted)
                val p = state.s("phone")
                label(if (p.length >= 7) "${p.take(3)} **** ${p.takeLast(4)}" else "已登录", 24f, ink, true)
                label(if (state.o("auth").b("renewal_blocked")) "登录已失效，请退出后重新登录" else "已登录 · 淋浴服务", 14f,
                    context.getColor(if (state.o("auth").b("renewal_blocked")) R.color.ui_problem else R.color.ui_primary))
            }
            balanceCard()
            card().apply {
                label("会话管理", 20f, ink, true)
                val t = state.o("auth").n("authenticated_at")
                label("上次登录 / 续期：${if (t > 0) date(t) else "尚未完成"}", 13f, muted)
                addView(Switch(context).apply {
                    text = "后台自动续期"; textSize = 16f; setTextColor(ink); minHeight = dp(56)
                    isChecked = state.b("auto_renew"); isEnabled = !busy; tag = "auto_renew"
                    setPadding(0, dp(8), dp(8), dp(8)); switchPadding = dp(16)
                    setOnCheckedChangeListener { _, checked -> onAction(Action(if (checked) "auto_on" else "auto_off", state.s("revision"))) }
                }, LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                label("约每小时维护一次，时间由系统安排；操作前也会检查会话。", 13f, muted)
                button("立即续期", enabled = !busy && !state.o("auth").b("renewal_blocked"), tagName = "renew") { onAction(Action("renew", state.s("revision"))) }
            }
        }
        card().apply {
            label("桌面小组件", 20f, ink, true)
            label("把开关放到桌面", 16f, ink)
            label("2×2 小组件，一眼查看设备、状态与余额，一键开关阀。", 14f, muted)
            label(if (widgetCount > 0) "已添加 $widgetCount 个小组件" else "添加后，可直接在桌面使用。", 13f, muted)
            if (pinHint.isNotEmpty()) label(pinHint, 13f, muted).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            button(if (pinPending) "等待桌面确认…" else "添加桌面小组件", enabled = !pinPending, tagName = "add_widget", onClick = onPin)
            button("手动添加方法", onClick = onGuide)
        }
        if (logged) card().button("退出本机登录", enabled = !busy, tagName = "logout") { onLogout(state) }
        content.label("淋浴小助手 0.2.8 · MVP\n充值与设备绑定请在wmxy完成", 12f, muted)
    }
    private fun loginCard() {
        card().apply {
            label("短信登录", 22f, ink, true)
            label("登录wmxy，获取已绑定的淋浴设备。", 14f, muted)
            phone = input("手机号", phoneDraft.ifEmpty { state.s("phone") }, InputType.TYPE_CLASS_PHONE, "phone")
            sendButton = button(smsButtonLabel(), enabled = !busy && smsWait() == 0L) {
                onAction(Action("send", state.s("revision"), phone = phone?.text.toString().trim()))
            }
            sms = input("4–8 位验证码", smsDraft, InputType.TYPE_CLASS_NUMBER, "sms")
            button("登录并获取设备", primary = true, enabled = !busy && state.b("sms_sent")) {
                if (phone?.text.toString().trim() != state.s("phone")) {
                    phone?.error = "手机号已变更，请重新获取验证码"; phone?.requestFocus()
                } else onAction(Action("login", state.s("revision"), code = sms?.text.toString().trim()))
            }
            label("验证码需手动填写，请勿向他人提供。", 12f, muted)
        }
    }
}

package local.rea.shower.core

import com.google.gson.JsonObject
import java.math.BigDecimal
import java.math.RoundingMode

data class WidgetState(val target: String, val number: String, val status: String,
    val action: String = "open", val enabled: Boolean = false,
    val balanceText: String = "余额 --", val balanceTone: String = "unknown") {
    val buttonText get() = if (action == "close") "关阀" else "开阀"
}

data class BalanceDisplay(val text: String = "余额 --", val tone: String = "unknown")
fun widgetBalance(state: JsonObject): BalanceDisplay {
    if (!state.o("auth").has("user")) return BalanceDisplay()
    val funds = state.o("funds")
    val amount = funds.s("amount").toBigDecimalOrNull() ?: return BalanceDisplay()
    val cap = funds.s("limit_cents").toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO } ?: return BalanceDisplay()
    return BalanceDisplay("余额 ¥" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString(),
        if (amount > cap.movePointLeft(2)) "above" else "low")
}

/** One decision shared by the widget label, enabled state and PendingIntent. */
fun widgetState(state: JsonObject, busy: Boolean = false): WidgetState {
    val phase = state.s("phase")
    val active = phase in setOf("opening", "open", "closing", "uncertain")
    val target = if (active) state.o("journal").s("termid") else state.s("selected")
    val number = target.filter { it in '0'..'9' }.ifEmpty { "—" }
    val balance = widgetBalance(state)
    fun view(status: String, action: String = "open", enabled: Boolean = false) =
        WidgetState(target, number, status, action, enabled, balance.text, balance.tone)
    if (!state.o("auth").has("user")) return view("请登录")
    if (state.o("auth").b("renewal_blocked")) return view("请重新登录")
    if (target.isEmpty()) return view("请选择设备")
    val matches = state.a("devices").filter { it.isJsonObject && it.asJsonObject.s("term_id") == target }
    val device = matches.singleOrNull()?.asJsonObject
    val owned = device?.s("term_status") == "1" || active
    val action = if (owned) "close" else "open"
    if (busy) return view(when (phase) {
        "opening" -> "开阀中…"
        "closing" -> "关阀中…"
        else -> "更新中…"
    }, action)
    if (matches.size > 1) return view("编号重复")
    if (device == null) return view("请刷新设备")
    when (device.s("term_status")) {
        "3" -> return view("他人使用中")
        "2" -> return view("设备故障")
    }
    if (state.a("devices").any { it.isJsonObject && it.asJsonObject.s("term_status") == "1" &&
            it.asJsonObject.s("term_id") != target }) return view("其他设备使用中")
    if (phase == "uncertain" || phase == "opening" || phase == "closing") return view("结果未确认", "close", true)
    if (owned) return view(if (state.b("error")) "操作失败" else "自己使用中", "close", true)
    if (device.s("term_status") == "0") return view(if (state.b("error")) "操作失败" else "空闲", "open", true)
    return view("状态未知")
}

package local.rea.shower.core

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import java.net.URI
import java.util.UUID

data class Action(val name: String, val revision: String, val phone: String = "", val code: String = "",
    val target: String = "", val confirmedStopped: Boolean = false)

class Engine(private val store: StateStore, private val http: Transport,
    private val now: () -> Long = System::currentTimeMillis, private val pause: (Long) -> Unit = Thread::sleep) {
    private val native = NativeApi(http)
    private var state = store.read()
    private var nativeRenewed = false
    private var passiveRefresh = false
    private fun save() { store.write(state) }
    private fun water(config: JsonObject = state.o("water")) = WaterApi(http, config, now, pause)
    private fun fields() = Profile.defaults().apply { put("deviceId", state.s("device_id")) }
    private fun active() = state.s("phase") in setOf("opening", "open", "closing", "uncertain")
    private fun saveFunds(funds: JsonObject) {
        state.add("funds", funds.deepCopy().apply { put("checked_at", now()) })
        save()
    }
    private fun refreshFunds(client: WaterApi) {
        // A display-only balance query must not turn a successful operation
        // into a failure. Never query it before dispatching an emergency close.
        try { saveFunds(client.funds()) } catch (_: WaterError) { state.remove("funds"); save() }
    }
    private fun setDevices(devices: JsonArray) {
        state.add("devices", devices)
        state.put("listed_at", now())
        // A fresh query may observe that a completed session ended elsewhere.
        // Never discard an uncertain control, or skip an explicit close in flight.
        if (passiveRefresh && state.s("phase") == "open") {
            val journal = state.o("journal")
            val matches = devices.filter { it.asJsonObject.s("term_id") == journal.s("termid") }
            val status = matches.singleOrNull()?.asJsonObject?.s("term_status")
            val account = compact(obj("identity" to WaterApi.identity(state.o("water"))))
            if (journal.s("account") == account && status in setOf("0", "3")) {
                state.put("phase", if (status == "0") "closed" else "unknown")
                state.remove("journal")
            }
        }
    }
    init {
        if (state.s("revision").isEmpty()) state.put("revision", UUID.randomUUID().toString())
        if (state.s("device_id").isEmpty()) state.put("device_id", UUID.randomUUID().toString().replace("-", ""))
        if (!state.has("auto_renew")) state.put("auto_renew", true)
        // A process restart must not turn an interrupted control into a success or replay it.
        if (state.s("phase") in setOf("opening", "closing")) {
            state.put("phase", "uncertain"); state.put("message", "上次控制被中断，请检查水流，需要停水时点击关阀。")
        }
        save()
    }
    @Synchronized fun execute(action: Action): Boolean {
        state = store.read()
        if (action.revision != state.s("revision")) return false
        nativeRenewed = false
        passiveRefresh = action.name in setOf("refresh", "renew", "maintain")
        var success = false
        try {
            val message = when (action.name) {
                "send" -> send(action.phone)
                "login" -> login(action.code)
                "refresh", "renew", "maintain" -> {
                    refreshFunds(ensure(force = action.name == "renew"))
                    state.put("checked_at", now())
                    if (action.name == "renew") "续期成功，设备选择已保留。" else "设备状态已更新。"
                }
                "select" -> {
                    if (active()) throw WaterError("请先关阀，再切换设备。")
                    val client = ensure()
                    val device = WaterApi.choose(action.target, state.a("devices"))
                    state.put("selected", device.s("term_id")); state.put("selected_name", device.s("term_name"))
                    state.put("phase", "unknown"); state.remove("journal")
                    refreshFunds(client)
                    "已选择设备 ${device.s("term_id")}。"
                }
                "open", "close" -> control(action)
                "logout" -> {
                    if (!action.confirmedStopped) throw WaterError("退出前请确认现场已停水。")
                    state = obj("device_id" to state.s("device_id"), "sms_requested_at" to state.n("sms_requested_at"),
                        "auto_renew" to state.b("auto_renew"), "revision" to state.s("revision"))
                    "已清除本机登录信息。"
                }
                "auto_on", "auto_off" -> {
                    state.put("auto_renew", action.name == "auto_on")
                    if (state.b("auto_renew")) "已开启后台会话维护，操作前也会自动检查。" else "已关闭后台维护，操作前仍会按需续期。"
                }
                else -> throw WaterError("未知操作。")
            }
            state.put("message", message); state.put("error", false); success = true
        } catch (e: Exception) {
            state.put("message", if (e is WaterError) e.message else "请求未完成，请检查网络后重试。")
            state.put("error", true)
        } finally {
            state.put("revision", UUID.randomUUID().toString()); state.put("updated_at", now()); save()
        }
        return success
    }
    private fun send(phone: String): String {
        if (state.o("auth").has("user")) throw WaterError("已经登录，请先退出再更换账号。")
        if (!Regex("1[0-9]{10}").matches(phone)) throw WaterError("请输入 11 位手机号。")
        if (now() - state.n("sms_requested_at") < 60_000) throw WaterError("请间隔 60 秒再获取验证码。")
        if (state.s("phone") != phone) { state.remove("auth"); state.put("sms_sent", false) }
        state.put("phone", phone)
        if (state.o("auth").s("session").isEmpty() || now() - state.o("auth").n("created_at") >= 600_000) {
            state.add("auth", native.exchange().apply { put("created_at", now()) })
            state.put("sms_sent", false); save()
        }
        val check = native.call(state.o("auth"), Profile.CHECK, obj("mobile" to phone))
        if (check.s("code_") != "0" || !check.b("result_")) throw WaterError("服务端未允许发送验证码，请稍后再试或在wmxy处理验证。")
        state.put("sms_requested_at", now()); state.put("sms_sent", false); save()
        native.success(native.call(state.o("auth"), Profile.SMS, obj("action" to "registAndLogin", "confirm" to false,
            "deviceId" to state.s("device_id"), "mobile" to phone, "type" to "sms")))
        state.put("sms_sent", true)
        return "验证码已发送，请输入短信中的数字。"
    }
    private fun login(code: String): String {
        if (state.o("auth").has("user")) throw WaterError("已经登录。")
        if (!state.b("sms_sent")) throw WaterError("请先在此应用获取验证码。")
        if (!Regex("[0-9]{4,8}").matches(code)) throw WaterError("请输入 4–8 位数字验证码。")
        val auth = state.o("auth")
        auth.add("user", native.user(native.call(auth, Profile.LOGIN, fields().apply {
            put("mobile", state.s("phone")); put("sms", code)
        }), state.s("phone")))
        auth.put("authenticated_at", now()); auth.put("water_needs_refresh", true)
        state.put("sms_sent", false); save()
        refreshFunds(ensure())
        return "登录成功，请选择淋浴设备。"
    }
    private fun renew() {
        if (nativeRenewed) throw WaterError("本次续期仍未恢复会话，请稍后再试。")
        nativeRenewed = true
        val previous = state.o("auth")
        if (previous.b("renewal_blocked")) throw LoginRequired()
        val user = previous.o("user")
        if (user.s("mobile") != state.s("phone") || listOf("id", "token").any { user.s(it).isEmpty() }) throw LoginRequired()
        val candidate = native.exchange()
        val response = native.call(candidate, Profile.TOKEN, fields().apply {
            put("type", "3"); put("userId", user.s("id")); put("userName", state.s("phone")); put("token", user.s("token"))
        })
        if (response.s("code_") != "0") { previous.put("renewal_blocked", true); save(); throw LoginRequired() }
        candidate.add("user", native.user(response, state.s("phone"), user))
        candidate.put("authenticated_at", now()); candidate.put("water_needs_refresh", true)
        state.add("auth", candidate)
        save() // Keep a rotated token even if H5 export subsequently fails.
    }
    private fun exportWater() {
        val auth = state.o("auth")
        val params = Profile.entryParams().apply {
            put("customerId", auth.o("user").s("customId")); put("token", auth.s("session"))
        }
        val url = Profile.ENTRY + "?" + form(params)
        val response = http.request(url, null, mapOf("User-Agent" to Profile.WATER_UA))
        if (response.status !in listOf(301, 302, 303, 307, 308)) {
            if (response.status == 200) throw EntryError()
            throw WaterError("水控入口返回 HTTP ${response.status}。")
        }
        val target = URI(url).resolve(response.header("Location"))
        if (target.scheme != "https" || target.rawAuthority != "xqh5.17wanxiao.com" || target.path != "/water3to1/index.html")
            throw WaterError("水控入口返回了未知地址。")
        val config = obj("page_url" to target.toString(), "native_session_sha256" to sha256(auth.s("session").toByteArray()))
        val client = water(config)
        val previous = state.o("water")
        if (previous.s("page_url").isNotEmpty() && WaterApi.identity(previous) != WaterApi.identity(config))
            throw WaterError("新的水控账号或学校不一致，未替换会话。")
        val devices = client.devices()
        state.add("water", config); setDevices(devices)
        auth.put("water_needs_refresh", false); save()
    }
    private fun repairWater() {
        try { exportWater() } catch (e: WaterError) {
            if (nativeRenewed || (e !is EntryError && e !is GatewayError)) throw e
            renew(); exportWater()
        }
    }
    private fun ensure(force: Boolean = false): WaterApi {
        val auth = state.o("auth")
        if (state.s("phone").isEmpty() || auth.o("user").s("mobile") != state.s("phone")) throw LoginRequired()
        if (auth.b("renewal_blocked")) throw LoginRequired()
        if (force || now() - auth.n("authenticated_at") >= Profile.RENEW_AFTER) {
            renew(); exportWater()
        } else if (auth.b("water_needs_refresh") || state.o("water").s("native_session_sha256") != sha256(auth.s("session").toByteArray())) repairWater()
        fun verify(): WaterApi = water().also {
            setDevices(it.devices()); save()
        }
        return try { verify() } catch (e: GatewayError) {
            if (nativeRenewed) throw e
            repairWater(); verify()
        }
    }
    private fun control(action: Action): String {
        val closing = action.name == "close"
        if (!closing && active()) throw WaterError("上次用水尚未结束，请先关阀。")
        val target = if (closing && active()) state.o("journal").s("termid") else state.s("selected")
        if (target.isEmpty() || target != action.target) throw WaterError("设备选择已变化，请重新确认。")
        var client = ensure()
        val account = compact(obj("identity" to WaterApi.identity(client.config)))
        if (active() && state.o("journal").s("account") != account) throw WaterError("未结束的控制记录属于其他账号。")
        var device = WaterApi.choose(target, state.a("devices"))
        val param = if (closing) {
            if (device.s("term_status") == "0" && !active()) {
                state.put("phase", "closed"); return "设备当前空闲，无需发送关阀。"
            }
            client.closeParam(device)
        } else {
            if (device.s("term_status") != "0") throw WaterError("设备当前不空闲，未发送开阀。")
            val prepared = try { client.preflight(device) } catch (_: GatewayError) {
                // Only pre-control reads may recover. Never retry controlterm.
                if (nativeRenewed) throw GatewayError()
                repairWater(); client = ensure(); device = WaterApi.choose(target, state.a("devices"))
                if (device.s("term_status") != "0") throw WaterError("设备当前不空闲。")
                client.preflight(device)
            }
            // User preference: continue normally below the per-session fare cap.
            // The server still validates the actual control request.
            saveFunds(prepared.funds)
            prepared.param
        }
        state.put("phase", if (closing) "closing" else "opening")
        state.add("journal", obj("termid" to target, "account" to account)); save()
        try { client.control(param) } catch (_: Exception) {
            state.put("phase", "uncertain"); save()
            throw WaterError("控制结果未确认，未自动重发。请检查现场水流，需要停水时点击关阀。")
        }
        state.put("phase", if (closing) "closed" else "open")
        state.put("controlled_at", now())
        // Preflight's device list predates this confirmed command.
        state.a("devices").filter { it.asJsonObject.s("term_id") == target }.forEach {
            it.asJsonObject.put("term_status", if (closing) 0 else 1)
        }
        if (closing) { save(); refreshFunds(client) }
        return if (closing) "服务端已确认关阀，请核实现场已停水。" else "服务端已确认开阀，用水结束请点击关阀。"
    }
}

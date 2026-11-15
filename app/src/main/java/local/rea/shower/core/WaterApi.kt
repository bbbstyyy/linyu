package local.rea.shower.core

import com.google.gson.*
import java.math.BigDecimal

class WaterApi(private val http: Transport, val config: JsonObject,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: (Long) -> Unit = Thread::sleep) {
    val identity = local.rea.shower.core.query(config.s("page_url"))
    private val timestamp = now()
    init {
        if (identity["source"] != "wx" || listOf("uuid", "outid", "customerCode").any { identity[it].isNullOrBlank() })
            throw WaterError("水控会话信息不完整。")
    }
    private val headers = mapOf("Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
        "Accept" to "application/json, text/plain, */*", "Origin" to "https://xqh5.17wanxiao.com",
        "Referer" to config.s("page_url"), "User-Agent" to Profile.WATER_UA,
        "uuid" to identity.getValue("uuid"), "timestamp" to timestamp.toString(),
        "encrypt" to Crypto.header(identity.getValue("uuid"), timestamp), "X-Requested-With" to "com.newcapec.mobile.ncp")
    private fun post(path: String, fields: Map<String, String>): JsonObject {
        val r = http.request(Profile.WATER + path, form(fields), headers)
        if (r.status != 200) throw WaterError("水控接口返回 HTTP ${r.status}。")
        return parseObject(r.text)
    }
    private fun extra(cmd: String) = linkedMapOf("code" to cmd, "customercode" to identity.getValue("customerCode"))
    fun call(param: JsonObject, withExtra: Boolean = false): JsonObject {
        val data = param.deepCopy().apply { put("ver", "2.0"); put("source", "wx") }
        val fields = if (withExtra) extra(param.s("cmd")) else linkedMapOf()
        fields["param"] = compact(data); fields["source"] = "wx"
        val outer = post("/api/use/water/h5/WXInvokFront/", fields)
        if (outer.s("result_") != "true" && outer.s("result") != "200") throw GatewayError()
        val body = if (outer["body"]?.isJsonPrimitive == true) parseObject(outer.s("body")) else outer.o("body")
        if (body.s("result") != "0") throw WaterError("水控业务未成功，请检查设备或在wmxy查看。")
        return body
    }
    fun query(cmd: String, vararg fields: Pair<String, Any?>): JsonObject = call(obj("cmd" to cmd,
        "outid" to identity.getValue("outid"), *fields))
    fun devices(): JsonArray {
        val result = call(obj("cmd" to "getbindtermlist", "outid" to identity.getValue("outid"),
            "timestamp" to appTime(now()), "often_use_term" to "-1", "scene" to 0), true)
        val list = result["data"]?.takeIf { it.isJsonArray }?.asJsonArray ?: throw WaterError("设备列表格式异常。")
        if (list.any { !it.isJsonObject || it.asJsonObject.s("term_id").isBlank() ||
                    it.asJsonObject["term_id"].let { id -> !id.isJsonPrimitive || id.asJsonPrimitive.isBoolean } })
            throw WaterError("设备编号无效。")
        return list
    }
    fun preflight(device: JsonObject): Prepared {
        val limit = query("zfbquerylimit", "limittype" to 3, "timestamp" to appTime(now())).o("data")
        val modules = post("/api/three/one/apply/list", mapOf("source" to "wx"))
        if (modules.s("code") != "0" || modules["data"]?.isJsonArray != true) throw WaterError("无法取得支付配置。")
        val shower = modules.a("data").firstOrNull { it.isJsonObject && it.asJsonObject.s("moduleType") == "shower" }
            ?.asJsonObject ?: throw WaterError("当前学校没有淋浴模块。")
        var paycode: String? = null
        val withhold = shower.s("withhold") == "1"
        if (withhold) {
            val contract = query("queryContractual", "timestamp" to appTime(now())).o("data")
            paycode = if (!contract.has("paycode")) "undefined" else if (contract["paycode"].isJsonNull) "null" else contract.s("paycode")
            if (contract.s("payway") != "1") {
                if (contract.s("status") == "0") throw WaterError("请先在wmxy完成代扣签约。")
                if (contract.s("status") == "1") throw WaterError("存在待扣费检查，请先在wmxy处理。")
            }
        }
        val funds = readFunds(limit)
        if (withhold && paycode == "9999") query("getposworktype", "poscode" to device["term_id"], "timestamp" to appTime(now()))
        // Preserve the server's JSON types and the H5 fallback for zero/null limits.
        fun value(key: String): JsonElement = limit[key]?.takeUnless { it.isJsonNull || it.asString == "0" } ?: JsonPrimitive("")
        val param = obj("cmd" to "controlterm", "termid" to device["term_id"], "outid" to identity.getValue("outid"),
            "max_fare_limit" to value("max_fare_limit"), "max_time_limit" to value("max_time_limit"),
            "optype" to 1, "scene" to 0, "paycode" to paycode)
        return Prepared(param, funds)
    }
    fun funds(): JsonObject = readFunds(query("zfbquerylimit", "limittype" to 3, "timestamp" to appTime(now())).o("data"))
    private fun readFunds(limit: JsonObject): JsonObject {
        val amount = query("zfbgetcustoddfare", "timestamp" to appTime(now())).s("oddfare").toBigDecimalOrNull()
            ?: throw WaterError("无法取得有效余额。")
        val cap = limit.s("max_fare_limit").ifEmpty { "0" }.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO }
            ?: throw WaterError("用水限额格式异常。")
        return obj("amount" to amount.toPlainString(), "limit_cents" to cap.toPlainString())
    }
    fun closeParam(device: JsonObject) = obj("cmd" to "controlterm", "termid" to device["term_id"],
        "optype" to 2, "closeFrom" to device.s("closeFrom").ifEmpty { "00" }, "outid" to identity.getValue("outid"))
    fun control(param: JsonObject) {
        // No recovery/retry wraps this call. Only read-only confirmation is polled.
        val data = call(param, true).o("data")
        if (data.s("opresult") == "0") return
        if (data.s("opresult") != "2" || !data.has("recno") || data["recno"].isJsonNull) throw WaterError("设备未确认控制结果。")
        val poll = obj("cmd" to "getcontroltermbyrecno")
        if (data.has("term_id")) poll.add("termid", data["term_id"])
        poll.add("recno", data["recno"]); poll.put("outid", identity.getValue("outid"))
        repeat(7) { attempt ->
            pause(1000)
            val result = call(poll, true).o("data")
            if (result.s("opresult") == "0" || (attempt >= 4 && result.s("term_status") ==
                    if (param.s("optype") == "1") "1" else "0")) return
        }
        throw WaterError("查询结束仍未确认控制结果。")
    }
    companion object {
        fun reason(device: JsonObject, devices: JsonArray): String {
            if (devices.any { it.asJsonObject.s("term_status") == "1" && it.asJsonObject.s("term_id") != device.s("term_id") })
                return "本账号还有另一台设备在使用，请先选择并关闭该设备。"
            return when (device.s("term_status")) {
                "0", "1" -> ""
                "2" -> "设备故障，请选择其他设备。"
                "3" -> "他人正在使用，请选择空闲设备。"
                else -> "设备状态未知，暂不能操作。"
            }
        }
        fun choose(id: String, devices: JsonArray): JsonObject {
            val matches = devices.filter { it.asJsonObject.s("term_id") == id }
            if (matches.size != 1) throw WaterError("设备不在绑定列表中或编号重复，请刷新并重新选择。")
            val device = matches.single().asJsonObject
            reason(device, devices).takeIf { it.isNotEmpty() }?.let { throw WaterError(it) }
            return device
        }
        fun identity(config: JsonObject): List<String?> = query(config.s("page_url")).let {
            listOf(it["outid"], it["customerCode"], it["source"])
        }
    }
}
data class Prepared(val param: JsonObject, val funds: JsonObject)

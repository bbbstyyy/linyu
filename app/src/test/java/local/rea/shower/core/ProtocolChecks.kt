package local.rea.shower.core

import com.google.gson.*
import java.net.URI
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.crypto.Cipher

private const val KEY = "0123456789abcdef0123456789abcdef"
private const val NOW = 20_000_000L
private const val PHONE = "13800000000"
private fun user() = obj("mobile" to PHONE, "id" to 123, "customId" to 456, "token" to "old-token")
private fun config(uuid: String = "old-uuid", outid: String = "test-outid") = obj(
    "page_url" to "https://xqh5.17wanxiao.com/water3to1/index.html?uuid=$uuid&outid=$outid&customerCode=test-school&source=wx",
    "native_session_sha256" to sha256("old-session".toByteArray()))
private fun seed() = obj("revision" to "revision-1", "phone" to PHONE, "device_id" to "test-device", "auto_renew" to true,
    "auth" to obj("session" to "old-session", "key" to KEY, "user" to user(), "authenticated_at" to NOW - 1000),
    "water" to config(), "selected" to "000123", "phase" to "unknown")
private fun device(id: String = "000123", status: Int = 0) = obj("term_id" to id, "term_status" to status, "closeFrom" to "01", "term_name" to "测试淋浴")
private class Memory(initial: JsonObject) : StateStore {
    var state = initial.deepCopy()
    val writes = mutableListOf<JsonObject>()
    override fun read() = state.deepCopy()
    override fun write(state: JsonObject) { this.state = state.deepCopy(); writes.add(state.deepCopy()) }
}
private fun response(value: JsonObject, status: Int = 200) = Response(status, compact(value).toByteArray())
private fun ok(data: JsonObject) = response(obj("result_" to "true", "body" to compact(obj("result" to 0, "data" to data))))
private class Fake : Transport {
    val seen = mutableListOf<Pair<String, JsonObject>>()
    val handlers = mutableMapOf<String, (JsonObject) -> Response>()
    var devices = JsonArray().apply { add(device()) }
    var loginUser = user().apply { put("token", "new-token") }
    var balance = "100"
    var entryOutid = "test-outid"
    var badSignature = false
    fun count(name: String) = seen.count { it.first == name }
    override fun request(url: String, body: String?, headers: Map<String, String>): Response {
        check(url.startsWith("https://"))
        val path = URI(url).path
        if (path.endsWith("exchangeSecretkey.action")) {
            val fields = parseObject(body!!); seen.add("exchange" to fields)
            handlers["exchange"]?.let { return it(fields) }
            val public = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(fields.s("key"))))
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, public) }
            val plain = compact(obj("session" to "new-session", "key" to KEY)).toByteArray()
            val encrypted = plain.asList().chunked(117).flatMap { cipher.doFinal(it.toByteArray()).asList() }.toByteArray()
            return Response(200, Base64.getEncoder().encode(encrypted))
        }
        if (path.contains("/campus/")) {
            check(headers["campusSign"] == sha256(body!!.toByteArray()))
            val envelope = parseObject(body)
            val plain = Crypto.decrypt(envelope.s("data"), KEY)
            val fields = parseObject(plain)
            check(plain == compact(fields, true))
            val method = path.removePrefix("/campus/")
            check(fields.s("requestMethod") == method)
            seen.add(method to fields)
            handlers[method]?.let { return it(fields) }
            val value = when (method) {
                Profile.CHECK -> obj("code_" to 0, "result_" to true)
                Profile.SMS -> obj("code_" to 0)
                Profile.LOGIN, Profile.TOKEN -> obj("code_" to 0, "data" to Crypto.encrypt(compact(obj("user" to loginUser)), KEY))
                else -> error("Unexpected native request: $method")
            }
            return signed(value)
        }
        if (path.endsWith("/entrance")) {
            val fields = obj("url" to url); seen.add("entry" to fields)
            handlers["entry"]?.let { return it(fields) }
            return Response(302, byteArrayOf(), mapOf("Location" to config("new-uuid", entryOutid).s("page_url")))
        }
        check(headers["encrypt"] == Crypto.header(headers.getValue("uuid"), headers.getValue("timestamp").toLong()))
        val form = body!!.split("&").associate { val p = it.split("=", limit = 2); URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p[1], "UTF-8") }
        if (path.endsWith("/apply/list")) {
            seen.add("modules" to obj()); handlers["modules"]?.let { return it(obj()) }
            return response(obj("code" to 0, "data" to listOf(obj("moduleType" to "shower", "withhold" to 0))))
        }
        val fields = parseObject(form.getValue("param"))
        val cmd = fields.s("cmd")
        check(fields.s("source") == "wx" && fields.s("ver") == "2.0")
        if (cmd in listOf("controlterm", "getcontroltermbyrecno", "getbindtermlist")) {
            check(form["code"] == cmd && form["customercode"] == "test-school")
        }
        seen.add(cmd to fields)
        handlers[cmd]?.let { return it(fields) }
        return when (cmd) {
            "getbindtermlist" -> response(obj("result" to 200, "body" to obj("result" to 0, "data" to devices)))
            "zfbquerylimit" -> ok(obj("max_fare_limit" to 500, "max_time_limit" to 120))
            "zfbgetcustoddfare" -> response(obj("result_" to "true", "body" to obj("result" to 0, "oddfare" to balance)))
            "controlterm", "getcontroltermbyrecno" -> ok(obj("opresult" to 0))
            else -> error("Unexpected water request: $cmd")
        }
    }
    fun signed(value: JsonObject): Response {
        val bytes = compact(value).toByteArray()
        return Response(200, bytes, mapOf("campusSign" to if (badSignature) "invalid" else sha256(bytes)))
    }
}
private class Fixture(initial: JsonObject = seed()) {
    val store = Memory(initial)
    val http = Fake()
    val engine = Engine(store, http, { NOW }, {})
    fun run(name: String, target: String = "000123", phone: String = PHONE, code: String = "123456") =
        engine.execute(Action(name, store.state.s("revision"), phone, code, target))
}
private fun expectError(block: () -> Unit) { var threw = false; try { block() } catch (_: Exception) { threw = true }; check(threw) }

fun main() {
    var passed = 0
    fun test(name: String, block: () -> Unit) {
        try { block(); println("PASS $name"); passed++ }
        catch (e: Throwable) { throw AssertionError("FAIL $name", e) }
    }
    test("3DES matches independently generated Java/Python vector") {
        val plain = "{\"mobile\":\"13800000000\",\"requestMethod\":\"cam_iface46/checkBeforeSendRegisterCode.action\"}"
        val expected = "FZ9RnLmg5EH1dmTQZHQOAFLowpuYUtxzWf9sH9GxHkGXy9oRbRHlk1jBbVclMlmcTpS90F2/ypqJ\nX0BloUpT410Deygy7cDUuTdls15PYhIcrwVdb8Rx1zylF3wLTXPX\n"
        check(Crypto.encrypt(plain, KEY) == expected); check(Crypto.decrypt(expected, KEY) == plain)
        check(Crypto.header("test-user", 123) == "FuYR9cq3w9iaRnpXbFNWKg==")
    }
    test("RSA multiple blocks and truncated response") {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, pair.public) }
        val expected = "a".repeat(117) + "b".repeat(80)
        val bytes = expected.chunked(117).flatMap { c.doFinal(it.toByteArray()).asList() }.toByteArray()
        check(Crypto.rsaDecode(Base64.getEncoder().encodeToString(bytes), pair.private) == expected)
        expectError { Crypto.rsaDecode(Base64.getEncoder().encodeToString(bytes.copyOf(bytes.size - 1)), pair.private) }
    }
    test("wire timestamp, RFC3986 and explicit null") {
        check(appTime(java.time.Instant.parse("2026-11-13T03:23:45Z").toEpochMilli()) == "20261113114523")
        check(form(mapOf("x" to "a b+中*~")) == "x=a%20b%2B%E4%B8%AD%2A~")
        check(compact(obj("paycode" to null)) == "{\"paycode\":null}")
    }
    test("SMS login succeeds and creates account-bound water session") {
        val f = Fixture(obj())
        check(f.run("send")); check(f.store.state.b("sms_sent")); check(f.run("login"))
        check(f.store.state.o("auth").o("user").s("token") == "new-token")
        check(f.store.state.a("devices").size() == 1); check(f.store.state.s("selected").isEmpty())
        val fields = f.http.seen.single { it.first == Profile.LOGIN }.second
        check(fields.s("deviceId").length == 32 && fields.s("wanxiaoVersion") == "1050833101")
        check(f.http.count("controlterm") == 0)
    }
    test("SMS requires explicit permission; ambiguous send retains cooldown") {
        val f = Fixture(obj())
        f.http.handlers[Profile.CHECK] = { f.http.signed(obj("code_" to 0, "result_" to false)) }
        check(!f.run("send")); check(f.http.count(Profile.SMS) == 0)
        f.http.handlers.remove(Profile.CHECK)
        f.http.handlers[Profile.SMS] = { throw WaterError("timeout") }
        check(!f.run("send")); check(!f.run("send")); check(f.http.count(Profile.SMS) == 1)
        check(!f.store.state.b("sms_sent")); check(!f.run("login"))
    }
    test("signature and login identity are validated before saving") {
        val f = Fixture(obj())
        check(f.run("send")); f.http.badSignature = true
        check(!f.run("login")); check(!f.store.state.o("auth").has("user"))
        f.http.badSignature = false; f.http.loginUser.put("mobile", "13900000000")
        check(!f.run("login")); check(!f.store.state.o("auth").has("user"))
    }
    test("recent session is reused without token login") {
        val f = Fixture(); check(f.run("refresh")); check(f.http.count("exchange") == 0 && f.http.count("entry") == 0)
    }
    test("due session renews once with token fields and preserves selection") {
        val s = seed().apply { o("auth").put("authenticated_at", NOW - Profile.RENEW_AFTER - 1) }
        val f = Fixture(s); check(f.run("refresh"))
        check(f.http.count(Profile.TOKEN) == 1); check(f.store.state.s("selected") == "000123")
        val p = f.http.seen.single { it.first == Profile.TOKEN }.second
        check(p.s("type") == "3" && p.s("userId") == "123" && p.s("token") == "old-token")
        check(!p.has("sms") && !p.has("password"))
    }
    test("rotated token survives H5 failure; next operation resumes export") {
        val f = Fixture(); f.http.handlers["entry"] = { throw WaterError("timeout") }
        check(!f.run("renew")); check(f.store.state.o("auth").o("user").s("token") == "new-token")
        check(f.store.state.o("auth").b("water_needs_refresh")); check(f.store.state.o("water") == config())
        f.http.handlers.remove("entry"); check(f.run("refresh")); check(f.http.count(Profile.TOKEN) == 1)
    }
    test("renewal rejection blocks subsequent automatic attempts") {
        val f = Fixture(); f.http.handlers[Profile.TOKEN] = { f.http.signed(obj("code_" to "revoked")) }
        check(!f.run("renew")); check(!f.run("refresh")); check(!f.run("renew"))
        check(f.http.count(Profile.TOKEN) == 1 && f.store.state.o("auth").b("renewal_blocked"))
    }
    test("renewal transport failure and school change preserve old token") {
        val f = Fixture(); f.http.handlers[Profile.TOKEN] = { throw WaterError("timeout") }
        check(!f.run("renew")); check(f.store.state.o("auth").o("user").s("token") == "old-token")
        f.http.handlers.remove(Profile.TOKEN); f.http.loginUser.put("customId", 999)
        check(!f.run("renew")); check(f.store.state.o("auth").s("session") == "old-session")
    }
    test("water identity change cannot replace previous config") {
        val f = Fixture(); f.http.entryOutid = "different"
        check(!f.run("renew")); check(f.store.state.o("water") == config())
    }
    test("pending export can recover rejected native session only once") {
        val s = seed().apply { o("auth").put("water_needs_refresh", true) }
        val f = Fixture(s)
        f.http.handlers["entry"] = {
            if (f.http.count("entry") == 1) Response(200, byteArrayOf())
            else Response(302, byteArrayOf(), mapOf("Location" to config("new-uuid").s("page_url")))
        }
        check(f.run("refresh")); check(f.http.count(Profile.TOKEN) == 1)
    }
    test("gateway recovery is limited to reads before control") {
        val f = Fixture()
        f.http.handlers["getbindtermlist"] = {
            if (f.http.count("getbindtermlist") == 1) response(obj("result_" to "false"))
            else response(obj("result_" to "true", "body" to obj("result" to 0, "data" to f.http.devices)))
        }
        check(f.run("open")); check(f.http.count("controlterm") == 1 && f.http.count("entry") == 1)
        val param = f.http.seen.single { it.first == "controlterm" }.second
        check(param.s("termid") == "000123" && param.s("max_fare_limit") == "500" && param["paycode"].isJsonNull)
    }
    test("selection validates current binding, duplicates and other occupied devices") {
        val f = Fixture(); f.http.devices.add(device("000124", 1))
        check(!f.run("select")); check(f.run("select", "000124"))
        f.http.devices.add(device("000124", 1)); check(!f.run("select", "000124"))
        check(!f.run("select", "missing")); check(f.http.count("controlterm") == 0)
    }
    test("stale widget revision cannot replay an action") {
        val f = Fixture(); val revision = f.store.state.s("revision")
        check(f.run("open")); check(!f.engine.execute(Action("open", revision, target = "000123")))
        check(!f.run("open")); check(f.http.count("controlterm") == 1)
    }
    test("control timeout is uncertain with no replay, preserving close target") {
        val f = Fixture(); f.http.handlers["controlterm"] = { throw WaterError("timeout") }
        check(!f.run("open")); check(f.store.state.s("phase") == "uncertain")
        check(f.http.count("controlterm") == 1); check(!f.run("select", "000124"))
        f.store.state.put("selected", "000124"); f.http.handlers.remove("controlterm")
        check(f.run("close")); val param = f.http.seen.last { it.first == "controlterm" }.second
        check(param.s("termid") == "000123" && param.s("closeFrom") == "01" && param.s("optype") == "2")
    }
    test("control gateway failure does not renew or replay") {
        val f = Fixture(); f.http.handlers["controlterm"] = { response(obj("result_" to "false")) }
        check(!f.run("open")); check(f.http.count("entry") == 0 && f.http.count(Profile.TOKEN) == 0 && f.http.count("controlterm") == 1)
    }
    test("read-only async confirmation uses recno and term_id, not termid") {
        val f = Fixture(); f.http.handlers["controlterm"] = { ok(obj("opresult" to 2, "recno" to "record-1", "termid" to "wrong")) }
        check(f.run("open")); val poll = f.http.seen.single { it.first == "getcontroltermbyrecno" }.second
        check(poll.s("recno") == "record-1" && !poll.has("termid"))
        val g = Fixture(); g.http.handlers["controlterm"] = { ok(obj("opresult" to 2, "recno" to "record-2", "term_id" to "000123")) }
        check(g.run("open")); check(g.http.seen.single { it.first == "getcontroltermbyrecno" }.second.s("termid") == "000123")
    }
    test("seven inconclusive polls remain uncertain; late server status can confirm") {
        val f = Fixture(); f.http.handlers["controlterm"] = { ok(obj("opresult" to 2, "recno" to "record")) }
        f.http.handlers["getcontroltermbyrecno"] = { ok(obj("opresult" to 2, "term_status" to 4)) }
        check(!f.run("open")); check(f.http.count("getcontroltermbyrecno") == 7 && f.store.state.s("phase") == "uncertain")
        val g = Fixture(); g.http.handlers["controlterm"] = { ok(obj("opresult" to 2, "recno" to "record")) }
        g.http.handlers["getcontroltermbyrecno"] = { ok(obj("opresult" to 2, "term_status" to 1)) }
        check(g.run("open")); check(g.http.count("getcontroltermbyrecno") == 5)
    }
    test("balance at or below cap opens by default without an extra checkbox") {
        for (balance in listOf("5", "4.99", "0")) {
            val f = Fixture(); f.http.balance = balance
            check(f.run("open")); check(f.http.count("controlterm") == 1)
            check(f.store.state.s("phase") == "open" && widgetState(f.store.state).action == "close")
        }
    }
    test("busy or faulty devices cannot be opened") {
        for (status in 1..4) {
            val f = Fixture(); f.http.devices = JsonArray().apply { add(device(status = status)) }
            check(!f.run("open")); check(f.http.count("controlterm") == 0)
        }
    }
    test("process restart preserves uncertainty and does not replay") {
        val s = seed().apply { put("phase", "opening"); add("journal", obj("termid" to "000123", "account" to compact(obj("identity" to WaterApi.identity(config()))))) }
        val f = Fixture(s); check(f.store.state.s("phase") == "uncertain")
        check(f.http.seen.isEmpty()); check(!f.run("open")); check(f.run("close"))
    }
    test("logout requires stopped confirmation and erases credentials") {
        val f = Fixture(); check(!f.run("logout")); check(f.store.state.o("auth").has("user"))
        check(f.engine.execute(Action("logout", f.store.state.s("revision"), confirmedStopped = true)))
        check(!f.store.state.has("auth") && !f.store.state.has("water") && !f.store.state.has("selected"))
        check(f.http.seen.isEmpty())
    }
    test("background maintenance never sends SMS or valve actions") {
        val s = seed().apply { o("auth").put("authenticated_at", NOW - Profile.RENEW_AFTER - 1) }
        val f = Fixture(s); check(f.run("maintain")); check(f.http.count(Profile.TOKEN) == 1)
        check(f.http.count(Profile.SMS) == 0 && f.http.count("controlterm") == 0)
    }
    test("compact widget maps free, own and other occupancy to one action") {
        for ((status, action, enabled) in listOf(Triple(0, "open", true), Triple(1, "close", true), Triple(3, "open", false))) {
            val s = seed().apply { add("devices", JsonArray().apply { add(device(status = status)) }); put("selected_name", "浴室 526531 · 526531") }
            val model = widgetState(s)
            check(model.number == "000123" && model.target == "000123")
            check(model.action == action && model.enabled == enabled)
            if (status == 3) check(model.status == "他人使用中" && model.buttonText == "开阀")
        }
    }
    test("compact widget disables missing, faulty, duplicate and logged-out targets") {
        check(!widgetState(obj()).enabled)
        val s = seed()
        check(!widgetState(s).enabled)
        s.add("devices", JsonArray().apply { add(device(status = 2)) })
        check(!widgetState(s).enabled && widgetState(s).status == "设备故障")
        s.add("devices", JsonArray().apply { add(device()); add(device()) })
        check(!widgetState(s).enabled)
        s.add("devices", JsonArray().apply { add(device()); add(device("another", 1)) })
        check(!widgetState(s).enabled)
    }
    test("compact widget preserves uncertain close target and disables while busy") {
        val s = seed().apply { put("phase", "uncertain"); put("selected", "other"); add("journal", obj("termid" to "000123"));
            add("devices", JsonArray().apply { add(device()) }) }
        check(widgetState(s).target == "000123" && widgetState(s).buttonText == "关阀" && widgetState(s).enabled)
        check(!widgetState(s, true).enabled)
        s.a("devices")[0].asJsonObject.put("term_status", 3)
        check(!widgetState(s).enabled && widgetState(s).buttonText == "开阀")
    }
    test("confirmed commands immediately switch the single widget button") {
        val f = Fixture(); check(f.run("refresh")); check(widgetState(f.store.state).action == "open")
        check(f.run("open")); check(widgetState(f.store.state).action == "close")
        check(f.store.state.a("devices")[0].asJsonObject.s("term_status") == "1")
        // Server preflight may still say free; explicit close must still be sent.
        check(f.run("close")); check(f.http.count("controlterm") == 2)
        check(widgetState(f.store.state).action == "open" && widgetState(f.store.state).enabled)
        check(f.store.state.a("devices")[0].asJsonObject.s("term_status") == "0")
    }
    test("fresh idle query ends completed local session and permits opening") {
        val f = Fixture(); check(f.run("open")); check(f.run("refresh"))
        check(f.store.state.s("phase") == "closed" && widgetState(f.store.state).action == "open")
        check(f.run("open")); check(f.http.count("controlterm") == 2)
    }
    test("fresh other-user occupancy disables widget without a control request") {
        val f = Fixture(); check(f.run("open")); f.http.devices = JsonArray().apply { add(device(status = 3)) }
        check(f.run("refresh")); check(f.store.state.s("phase") == "unknown")
        check(widgetState(f.store.state).status == "他人使用中" && !widgetState(f.store.state).enabled)
        check(!f.run("open")); check(f.http.count("controlterm") == 1)
    }
    test("fresh idle query never clears an uncertain control") {
        val f = Fixture(); f.http.handlers["controlterm"] = { throw WaterError("timeout") }
        check(!f.run("open")); check(f.run("refresh"))
        check(f.store.state.s("phase") == "uncertain" && widgetState(f.store.state).action == "close")
        check(!f.run("open")); check(f.http.count("controlterm") == 1)
    }
    test("balance color compares yuan with server cents without float rounding") {
        for ((amount, tone) in listOf("5.01" to "above", "5.00" to "low", "4.99" to "low", "0" to "low")) {
            val s = seed().apply { add("funds", obj("amount" to amount, "limit_cents" to "500")) }
            check(widgetBalance(s).tone == tone)
            check(widgetState(s).balanceTone == tone)
        }
        val s = seed().apply { add("funds", obj("amount" to "3.22", "limit_cents" to "321")) }
        check(widgetBalance(s).text == "余额 ¥3.22" && widgetBalance(s).tone == "above")
    }
    test("unknown and invalid balance stays neutral, never fabricates zero") {
        check(widgetBalance(seed()).text == "余额 --")
        for (amount in listOf("NaN", "Infinity", "invalid")) {
            val s = seed().apply { add("funds", obj("amount" to amount, "limit_cents" to "500")) }
            check(widgetBalance(s).tone == "unknown")
        }
    }
    test("refresh fetches real balance and cap without payment or valve commands") {
        val f = Fixture(); f.http.balance = "3.45"; check(f.run("refresh"))
        check(f.store.state.o("funds").s("amount") == "3.45")
        check(f.store.state.o("funds").s("limit_cents") == "500")
        check(widgetState(f.store.state).balanceTone == "low")
        check(f.http.count("modules") == 0 && f.http.count("controlterm") == 0)
    }
    test("failed balance refresh clears old number but retains device controls") {
        val f = Fixture(); check(f.run("refresh"))
        f.http.handlers["zfbgetcustoddfare"] = { throw WaterError("timeout") }
        check(f.run("refresh")); check(!f.store.state.has("funds"))
        check(widgetState(f.store.state).balanceText == "余额 --" && widgetState(f.store.state).enabled)
    }
    test("low balance is cached from preflight and still opens directly") {
        val f = Fixture(); f.http.balance = "2.01"; check(f.run("open"))
        check(widgetState(f.store.state).balanceText == "余额 ¥2.01")
        check(widgetState(f.store.state).balanceTone == "low" && widgetState(f.store.state).action == "close")
        check(f.http.count("controlterm") == 1)
    }
    test("balance lookup happens after confirmed close and cannot prevent it") {
        val f = Fixture(); check(f.run("open"))
        f.http.handlers["zfbgetcustoddfare"] = {
            check(f.http.seen.last { it.first == "controlterm" }.second.s("optype") == "2")
            throw WaterError("balance unavailable")
        }
        check(f.run("close")); check(f.store.state.s("phase") == "closed")
        check(widgetState(f.store.state).action == "open" && widgetState(f.store.state).balanceTone == "unknown")
    }
    println("$passed offline protocol/state-machine checks passed. No live network was used.")
}

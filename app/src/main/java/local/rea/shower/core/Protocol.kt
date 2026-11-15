package local.rea.shower.core

import com.google.gson.*
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.*
import java.security.interfaces.RSAPrivateKey
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

open class WaterError(message: String) : Exception(message)
class GatewayError : WaterError("水控会话未被接受，需要重新连接。")
class EntryError : WaterError("水控入口未返回有效会话。")
class LoginRequired : WaterError("自动登录凭据已失效，请重新短信登录。")

val json: Gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()
fun obj(vararg fields: Pair<String, Any?>): JsonObject = JsonObject().apply {
    fields.forEach { (k, v) -> add(k, json.toJsonTree(v)) }
}
fun JsonObject.s(key: String, fallback: String = ""): String = get(key)?.takeUnless { it.isJsonNull }?.let {
    if (it.isJsonPrimitive) it.asString else fallback
} ?: fallback
fun JsonObject.o(key: String): JsonObject = get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
fun JsonObject.a(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
fun JsonObject.b(key: String): Boolean = s(key) == "true"
fun JsonObject.n(key: String): Long = s(key).toLongOrNull() ?: 0L
fun JsonObject.put(key: String, value: Any?) { add(key, json.toJsonTree(value)) }
fun parseObject(raw: String): JsonObject = try { JsonParser.parseString(raw).asJsonObject }
    catch (_: Exception) { throw WaterError("服务器响应格式异常，请稍后重试。") }
fun compact(value: JsonObject, sorted: Boolean = false): String = if (!sorted) json.toJson(value) else
    json.toJson(JsonObject().apply { value.keySet().sorted().forEach { add(it, value[it]) } })
fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data)
    .joinToString("") { "%02x".format(it) }
fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    .replace("*", "%2A").replace("%7E", "~")
fun form(fields: Map<String, String>): String = fields.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
fun query(url: String): Map<String, String> = (URI(url).rawQuery ?: "").split("&").filter { it.contains("=") }
    .associate { val p = it.split("=", limit = 2); URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p[1], "UTF-8") }
fun appTime(now: Long): String = SimpleDateFormat("yyyyMMddHHssmm", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("Asia/Shanghai")
}.format(Date(now))

object Crypto {
    private fun des(mode: Int, data: ByteArray, key: String): ByteArray {
        val bytes = key.toByteArray(UTF_8)
        if (bytes.size < 24) throw WaterError("登录业务密钥长度异常。")
        return Cipher.getInstance("DESede/CBC/PKCS5Padding").run {
            init(mode, SecretKeySpec(bytes.copyOf(24), "DESede"), IvParameterSpec("66666666".toByteArray()))
            doFinal(data)
        }
    }
    fun encrypt(text: String, key: String): String = Base64.getEncoder().encodeToString(
        des(Cipher.ENCRYPT_MODE, text.toByteArray(UTF_8), key)).chunked(76).joinToString("\n", postfix = "\n")
    fun decrypt(text: String, key: String): String = String(des(Cipher.DECRYPT_MODE,
        Base64.getMimeDecoder().decode(text), key), UTF_8)
    fun rsaDecode(text: String, key: PrivateKey): String {
        val bytes = Base64.getMimeDecoder().decode(text)
        val size = ((key as RSAPrivateKey).modulus.bitLength() + 7) / 8
        if (bytes.isEmpty() || bytes.size % size != 0) throw WaterError("密钥交换响应不完整。")
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, key) }
        return String(bytes.asList().chunked(size).flatMap { cipher.doFinal(it.toByteArray()).asList() }.toByteArray(), UTF_8)
    }
    fun header(uuid: String, timestamp: Long): String = Cipher.getInstance("AES/ECB/PKCS5Padding").run {
        // Public constant from the H5 client; not a user credential.
        init(Cipher.ENCRYPT_MODE, SecretKeySpec("wanmeixiaoyuan11".toByteArray(), "AES"))
        Base64.getEncoder().encodeToString(doFinal("$uuid-$timestamp".toByteArray(UTF_8)))
    }
}

data class Response(val status: Int, val bytes: ByteArray, val headers: Map<String, String> = emptyMap()) {
    val text get() = String(bytes, UTF_8)
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value ?: ""
}
fun interface Transport {
    fun request(url: String, body: String?, headers: Map<String, String>): Response
}
interface StateStore { fun read(): JsonObject; fun write(state: JsonObject) }

object Profile {
    const val NATIVE = "https://app.17wanxiao.com/campus/"
    const val WATER = "https://vwater.59wanmei.com/usewater"
    const val ENTRY = "$WATER/api/use-water/entrance"
    const val CHECK = "cam_iface46/checkBeforeSendRegisterCode.action"
    const val SMS = "cam_iface46/gainMatrixCaptcha543.action"
    const val LOGIN = "cam_iface46/registerUsersByTelAndLoginNew.action"
    const val TOKEN = "cam_iface46/loginnew.action"
    const val EXCHANGE = "cam_iface46/exchangeSecretkey.action"
    const val RENEW_AFTER = 3_600_000L // Maintenance policy, not a server token TTL.
    // Client compatibility metadata. No account data is bundled.
    const val NATIVE_UA = "Dalvik/2.1.0 (Linux; U; Android 8.0.0; MI 5s Build/OPR1.170623.032)"
    const val WATER_UA = "Mozilla/5.0 (Linux; Android 8.0.0; MI 5s Build/OPR1.170623.032; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/71.0.3578.99 Mobile Safari/537.36 Wanxiao/5.8.9 Wmxy/5.8.33"
    fun defaults() = obj("appCode" to "M002", "netWork" to "wifi", "qudao" to "oppo",
        "shebeixinghao" to "capricorn", "systemType" to "android", "telephoneInfo" to "8.0.0",
        "telephoneModel" to "MI 5s", "wanxiaoVersion" to 1050833101)
    fun entryParams() = linkedMapOf("source" to "wx", "tVer" to "20260918", "versionnum" to "1050833101",
        "systemType" to "Android", "UAinfo" to "wanxiao", "versioncode" to "10589101")
}

class NativeApi(private val http: Transport) {
    private fun post(method: String, body: String, signed: Boolean = false): String {
        val headers = mutableMapOf("Content-Type" to "application/json;charset=UTF-8", "User-Agent" to Profile.NATIVE_UA)
        if (signed) headers["campusSign"] = sha256(body.toByteArray(UTF_8))
        val r = http.request(Profile.NATIVE + method, body, headers)
        if (r.status != 200) throw WaterError("登录接口返回 HTTP ${r.status}。")
        if (signed && !MessageDigest.isEqual(r.header("campusSign").lowercase(Locale.US).toByteArray(),
                sha256(r.bytes).toByteArray())) throw WaterError("登录响应签名校验失败。")
        return r.text
    }
    fun exchange(): JsonObject {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val data = parseObject(Crypto.rsaDecode(post(Profile.EXCHANGE,
            compact(obj("key" to Base64.getEncoder().encodeToString(pair.public.encoded)))), pair.private))
        if (data.s("session").isEmpty() || data.s("key").toByteArray(UTF_8).size < 24) throw WaterError("密钥交换结果不完整。")
        return data
    }
    fun call(auth: JsonObject, method: String, fields: JsonObject): JsonObject {
        if (auth.s("session").isEmpty()) throw WaterError("请先获取验证码。")
        val payload = fields.deepCopy().apply { put("requestMethod", method) }
        val encoded = Crypto.encrypt(compact(payload, sorted = true), auth.s("key"))
        val result = parseObject(post(method, compact(obj("session" to auth.s("session"), "data" to encoded)), true))
        if (result.s("data").isNotEmpty()) {
            val plain = Crypto.decrypt(result.s("data"), auth.s("key"))
            result.add("data", try { JsonParser.parseString(plain) } catch (_: Exception) { JsonPrimitive(plain) })
        }
        return result
    }
    fun success(response: JsonObject): JsonObject {
        if (response.s("code_") != "0") throw WaterError("登录未成功，请检查验证码或稍后重试（${response.s("code_").take(20)}）。")
        return response.o("data")
    }
    fun user(response: JsonObject, mobile: String, previous: JsonObject? = null): JsonObject {
        val data = success(response)
        val user = if (data["user"]?.isJsonPrimitive == true) parseObject(data.s("user")) else data.o("user")
        if (user.s("mobile") != mobile) throw WaterError("返回账号不一致，未保存登录。")
        if (listOf("id", "customId", "token").any { user.s(it).isEmpty() }) throw WaterError("登录信息不完整，可能需要先在wmxy选择学校。")
        if (previous != null && listOf("id", "customId").any { user.s(it) != previous.s(it) })
            throw WaterError("续期返回账号或学校发生变化，未替换会话。")
        return user
    }
}

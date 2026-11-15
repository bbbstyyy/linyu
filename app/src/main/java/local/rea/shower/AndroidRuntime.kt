package local.rea.shower

import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.JsonObject
import local.rea.shower.core.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedStore(context: Context) : StateStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "account.enc"))
    private val key: SecretKey by lazy {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("shower-account-v1", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("shower-account-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    private var cached: JsonObject? = null
    @Synchronized override fun read(): JsonObject {
        cached?.let { return it.deepCopy() }
        val value = if (!file.baseFile.exists()) JsonObject() else {
            val bytes = file.readFully()
            require(bytes.size >= 29 && bytes[0] == 1.toByte())
            val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
                doFinal(bytes.copyOfRange(13, bytes.size))
            }
            parseObject(String(plain, Charsets.UTF_8))
        }
        cached = value
        return value.deepCopy()
    }
    @Synchronized override fun write(state: JsonObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(compact(state).toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream); cached = state.deepCopy() }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}

class AndroidTransport : Transport {
    var deadline: Long = Long.MAX_VALUE
    override fun request(url: String, body: String?, headers: Map<String, String>): Response {
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0 || Thread.currentThread().isInterrupted) throw WaterError("本次请求超时，请检查网络。")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = minOf(15_000L, remaining).toInt()
            connection.readTimeout = minOf(15_000L, remaining).toInt()
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            connection.setRequestProperty("Accept-Encoding", "gzip")
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.requestMethod = "POST"; connection.doOutput = true
                // Streaming bodies are not replayable by HttpURLConnection.
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val responseHeaders = connection.headerFields.filterKeys { it != null }.mapValues { it.value.firstOrNull() ?: "" }
            val source = if (code >= 400) connection.errorStream else connection.inputStream
            val bytes = if (source == null) byteArrayOf() else {
                val stream = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(source) else source
                stream.use {
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (SystemClock.elapsedRealtime() >= deadline) throw WaterError("请求超时。")
                        val count = it.read(buffer); if (count < 0) break
                        out.write(buffer, 0, count)
                        if (out.size() > 2_000_000) throw WaterError("服务器响应过大。")
                    }
                    out.toByteArray()
                }
            }
            return Response(code, bytes, responseHeaders)
        } catch (e: WaterError) { throw e }
        catch (_: Exception) { throw WaterError("网络请求未得到确认，请检查网络；操作没有自动重发。") }
        finally { connection.disconnect() }
    }
}

class AppRuntime private constructor(val context: Context) {
    val store = EncryptedStore(context)
    private val http = AndroidTransport()
    private val engine = Engine(store, http)
    private val executor = Executors.newSingleThreadExecutor()
    val busy = AtomicBoolean(false)
    @Volatile var busyLabel = ""
    fun snapshot() = store.read()
    fun run(action: Action, finished: () -> Unit): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        busyLabel = when (action.name) { "open" -> "正在开阀…"; "close" -> "正在关阀…"; "send" -> "正在获取验证码…"; "login" -> "正在登录…"; else -> "正在更新会话与设备…" }
        WidgetProvider.updateAll(context)
        executor.execute {
            try { http.deadline = SystemClock.elapsedRealtime() + 120_000; engine.execute(action) }
            catch (_: Exception) {
                // Keep existing encrypted state. Never clear credentials or replay a request on storage failure.
                busyLabel = "本地保存失败，请重新打开应用检查。"
            } finally {
                Handler(Looper.getMainLooper()).post {
                    busy.set(false)
                    try { WidgetProvider.updateAll(context); schedule(context) } finally { finished() }
                }
            }
        }
        return true
    }
    companion object {
        @Volatile private var instance: AppRuntime? = null
        fun get(context: Context): AppRuntime = instance ?: synchronized(this) {
            instance ?: AppRuntime(context.applicationContext).also { instance = it }
        }
        fun schedule(context: Context) {
            val state = get(context).snapshot()
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (state.b("auto_renew") && state.o("auth").has("user") && !state.o("auth").b("renewal_blocked")) {
                if (scheduler.getPendingJob(901) == null) scheduler.schedule(JobInfo.Builder(901,
                    ComponentName(context, RenewalJob::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(Profile.RENEW_AFTER).setPersisted(true).build())
            } else scheduler.cancel(901)
        }
        fun intent(context: Context, action: Action) = Intent(context, CommandService::class.java).apply {
            putExtra("name", action.name); putExtra("revision", action.revision); putExtra("phone", action.phone)
            putExtra("code", action.code); putExtra("target", action.target)
            putExtra("confirmed_stopped", action.confirmedStopped)
        }
        fun action(intent: Intent) = Action(intent.getStringExtra("name") ?: "", intent.getStringExtra("revision") ?: "",
            intent.getStringExtra("phone") ?: "", intent.getStringExtra("code") ?: "", intent.getStringExtra("target") ?: "",
            intent.getBooleanExtra("confirmed_stopped", false))
    }
}

class CommandService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var ownsOperation = false
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("water-operations", "用水操作", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "water-operations").setSmallIcon(R.drawable.ic_water)
            .setContentTitle("淋浴小助手").setContentText("正在处理用水操作，请稍候")
            .setContentIntent(pending).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(100, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(100, notification)
        if (intent == null) { finish(); return START_NOT_STICKY }
        val runtime = AppRuntime.get(this)
        if (runtime.busy.get()) {
            if (!ownsOperation) finish()
            return START_NOT_STICKY
        }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "shower:operation").apply { acquire(150_000) }
        ownsOperation = true
        if (!runtime.run(AppRuntime.action(intent)) { finish() }) finish()
        return START_NOT_STICKY // Android must never redeliver an interrupted valve command.
    }
    private fun finish() {
        ownsOperation = false
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { wakeLock?.let { if (it.isHeld) it.release() }; super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) { finish() }
}

class RenewalJob : JobService() {
    private var stopped = false
    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        val runtime = AppRuntime.get(this)
        val s = runtime.snapshot()
        if (!s.b("auto_renew") || !s.o("auth").has("user") || s.o("auth").b("renewal_blocked")) return false
        return runtime.run(Action("maintain", s.s("revision"))) { if (!stopped) jobFinished(params, false) }
    }
    override fun onStopJob(params: JobParameters): Boolean { stopped = true; return false }
}

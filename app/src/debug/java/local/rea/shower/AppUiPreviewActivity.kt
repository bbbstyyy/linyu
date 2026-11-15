package local.rea.shower

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import local.rea.shower.core.*
import java.io.File

/** Renders the production app layout with fake states and inert actions, only in the preview package. */
class AppUiPreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!packageName.endsWith(".preview")) { finish(); return }
        val label = TextView(this).apply { text = "正在检查应用界面…"; textSize = 18f; setPadding(32, 80, 32, 32) }
        setContentView(label)
        label.post {
            val result = try { checkLayouts() } catch (e: Throwable) { "FAIL: ${e.stackTraceToString()}" }
            val dir = File(getExternalFilesDir(null), "app-preview").apply { mkdirs() }
            File(dir, "result.txt").writeText(result); label.text = result
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun checkLayouts(): String {
        val base = obj("revision" to "preview", "phone" to "13800001234", "phase" to "unknown", "selected" to "000123",
            "auth" to obj("user" to obj(), "authenticated_at" to 1794547200000L), "auto_renew" to true,
            "devices" to listOf(obj("term_id" to "000123", "term_name" to "宿舍淋浴", "term_status" to "0")),
            "listed_at" to 1794547200000L,
            "funds" to obj("amount" to "28.60", "limit_cents" to "500", "checked_at" to 1794547200000L))
        val cases = listOf("idle" to base, "low" to base.deepCopy().apply { o("funds").put("amount", "3.50") },
            "open" to base.deepCopy().apply { put("phase", "open"); add("journal", obj("termid" to "000123")); a("devices")[0].asJsonObject.put("term_status", "1") },
            "unknown" to base.deepCopy().apply { remove("funds") },
            "empty" to base.deepCopy().apply { put("selected", ""); add("devices", com.google.gson.JsonArray()) },
            "uncertain" to base.deepCopy().apply { put("phase", "uncertain"); add("journal", obj("termid" to "000123")); put("error", true); put("message", "控制结果未确认，请检查现场水流。") },
            "occupied" to base.deepCopy().apply { a("devices")[0].asJsonObject.put("term_status", "3") },
            "blocked" to base.deepCopy().apply { o("auth").put("renewal_blocked", true) },
            "logged-out" to obj("revision" to "logged-out"))
        val dir = File(getExternalFilesDir(null), "app-preview").apply { mkdirs() }
        for ((name, resource) in listOf("water-icon" to R.drawable.ic_launcher, "adaptive-icon" to R.mipmap.ic_launcher)) {
            val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
            getDrawable(resource)!!.apply { setBounds(0, 0, 432, 432); draw(Canvas(bitmap)) }
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        var checks = 0
        for (night in listOf(false, true)) for (font in listOf(1f, 2f)) for ((w, h) in listOf(375 to 740, 740 to 375, 800 to 1024)) {
            val config = Configuration(resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                fontScale = font; screenWidthDp = w; screenHeightDp = h
            }
            val themed = ContextThemeWrapper(createConfigurationContext(config), R.style.AppTheme)
            val density = themed.resources.displayMetrics.density
            fun dp(i: Int) = (i * density).toInt()
            for ((name, state) in cases) for (tab in 0..1) {
                val actions = mutableListOf<Action>()
                val screen = ShowerScreen(themed, { actions.add(it) }, { _, _ -> }, {}, {}, {})
                screen.render(state, false, "", 0, ""); screen.selectTab(tab)
                screen.measure(View.MeasureSpec.makeMeasureSpec(dp(w), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(dp(h), View.MeasureSpec.EXACTLY))
                screen.layout(0, 0, dp(w), dp(h))
                val views = descendants(screen)
                val scroll = views.filterIsInstance<ScrollView>().single()
                val bar = views.filterIsInstance<RadioGroup>().single()
                check(scroll.bottom <= bar.top) { "Navigation overlaps scroll content" }
                check(bar.bottom <= screen.height) { "Navigation outside screen" }
                for (v in views) {
                    if (v is Button || v is Switch) check(v.height >= dp(48)) { "Small touch target ${v.tag}" }
                    if (v is TextView) {
                        check(v.layout != null && v.layout.height <= v.height - v.compoundPaddingTop - v.compoundPaddingBottom + 1) { "Clipped text: ${v.text}, $name $w $font" }
                        for (line in 0 until v.layout.lineCount) check(v.layout.getEllipsisCount(line) == 0) { "Ellipsized: ${v.text}" }
                    }
                    val bounds = Rect(0, 0, v.width, v.height)
                    screen.offsetDescendantRectToMyCoords(v, bounds)
                    check(bounds.left >= 0 && bounds.right <= screen.width) { "Horizontal overflow: ${v.tag}, $name $w $font" }
                }
                check(views.filterIsInstance<RadioButton>().single { it.isChecked }.text == if (tab == 0) "设备" else "我的")
                if (tab == 0 && name == "idle") {
                    check(screen.findViewWithTag<Button>("renew") == null)
                    check(screen.findViewWithTag<Button>("primary_control").isEnabled)
                }
                if (tab == 0 && name == "occupied") check(!screen.findViewWithTag<Button>("primary_control").isEnabled)
                if (tab == 0 && name in listOf("open", "uncertain")) {
                    screen.findViewWithTag<Button>("primary_control").performClick()
                    check(actions.single().name == "close" && actions.single().target == "000123")
                }
                if (name in listOf("idle", "low", "unknown")) {
                    val balance = screen.findViewWithTag<TextView>("balance").text.toString()
                    check(balance == when (name) { "idle" -> "¥28.60"; "low" -> "¥3.50"; else -> "--" })
                }
                if (tab == 1 && name == "logged-out") {
                    screen.findViewWithTag<EditText>("phone").setText("13800005678")
                    screen.findViewWithTag<EditText>("sms").setText("123456")
                    screen.selectTab(0); screen.selectTab(1)
                    check(screen.findViewWithTag<EditText>("phone").text.toString() == "13800005678")
                    check(screen.findViewWithTag<EditText>("sms").text.toString() == "123456")
                    val saved = Bundle(); screen.saveState(saved)
                    val restored = ShowerScreen(themed, {}, { _, _ -> }, {}, {}, {})
                    restored.restoreState(saved); restored.render(state, false, "", 0, "")
                    check(restored.selectedTab == 1 && restored.findViewWithTag<EditText>("sms").text.toString() == "123456")
                    // Re-measure after rebuilding the detached screen for the snapshot.
                    screen.measure(View.MeasureSpec.makeMeasureSpec(dp(w), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(dp(h), View.MeasureSpec.EXACTLY)); screen.layout(0, 0, dp(w), dp(h))
                }
                if ((name == "idle" && (w == 375 || (w == 740 && font == 2f))) || (w == 375 && font == 1f && ((name == "open" && tab == 0) || (!night && name in listOf("low", "unknown", "logged-out", "uncertain"))))) {
                    val bitmap = Bitmap.createBitmap(dp(w), dp(h), Bitmap.Config.ARGB_8888)
                    screen.draw(Canvas(bitmap))
                    File(dir, "${if (night) "dark" else "light"}-$name-tab$tab-${w}x$h-font$font.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
                checks++
            }
        }
        return "PASS: $checks native app layout cases; device/profile tabs, balance high/low/unknown, empty/occupied/uncertain/expired/login states, tab selection, retained login drafts, activity restoration and close target. Light/dark; 1x/2x fonts; 375x740, 740x375, 800x1024dp. Isolated callbacks: no account access, network or real valve actions."
    }
}

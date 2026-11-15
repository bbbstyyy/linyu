package local.rea.shower

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.View
import java.io.File
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import local.rea.shower.core.obj
import local.rea.shower.core.widgetState

import android.content.Context

/** Offline production RemoteViews rendering, shared by instrumentation and the isolated preview app. */
object WidgetUiChecks {
    fun run(context: Context): String {
        var checked = 0
        var saved = 0
        val cases = listOf(
            obj("revision" to "ui-test-logged-out"),
            obj("revision" to "ui-test-idle", "selected" to "000123", "phase" to "unknown", "auth" to obj("user" to obj()),
                "devices" to listOf(obj("term_id" to "000123", "term_status" to 0)),
                "funds" to obj("amount" to "8.50", "limit_cents" to "500")),
            obj("revision" to "ui-test-owned", "selected" to "000123", "auth" to obj("user" to obj()),
                "devices" to listOf(obj("term_id" to "000123", "term_status" to 1)),
                "funds" to obj("amount" to "3.50", "limit_cents" to "500")),
            obj("revision" to "ui-test-other", "selected" to "000123", "auth" to obj("user" to obj()),
                "devices" to listOf(obj("term_id" to "000123", "term_status" to 3))),
            obj("revision" to "ui-test-active", "selected" to "000123", "phase" to "uncertain",
                "journal" to obj("termid" to "000124"), "auth" to obj("user" to obj()),
                "devices" to listOf(obj("term_id" to "000124", "term_status" to 0)))
        )
        val output = File(context.getExternalFilesDir(null), "ui-preview").apply { mkdirs() }
        val sizes = listOf(166 to 200, 110 to 110, 200 to 140, 260 to 240)
        for (night in listOf(false, true)) for (fontScale in listOf(0.85f, 1f, 2f)) {
            val configuration = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                this.fontScale = fontScale
            }
            val themed = ContextThemeWrapper(context.createConfigurationContext(configuration), R.style.AppTheme)
            val density = themed.resources.displayMetrics.density
            cases.forEachIndexed { index, state ->
                for (busy in listOf(false, true)) for ((width, height) in sizes) {
                    val model = widgetState(state, busy)
                    val views = WidgetProvider.createSizedViews(themed, state, busy, 100000 + index,
                        width, height, bindActions = false)
                    // apply() executes the same reflected methods used by an actual launcher.
                    val rendered = views.apply(themed, FrameLayout(themed))
                    val w = (width * density).toInt(); val h = (height * density).toInt()
                    rendered.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    rendered.layout(0, 0, w, h)
                    val button = rendered.findViewById<Button>(R.id.widget_action)
                    val header = rendered.findViewById<View>(R.id.widget_header)
                    check(button.isEnabled == model.enabled)
                    check(button.text.toString() == model.buttonText)
                    check(button.height >= (48 * density).toInt())
                    check(header.height >= (48 * density).toInt())
                    check(button.top - header.bottom >= (8 * density).toInt())
                    check(button.bottom <= h - rendered.paddingBottom)
                    check(header.contentDescription.toString().contains(model.status))
                    check(button.contentDescription.isNotEmpty())
                    check((rendered.background as GradientDrawable).color?.defaultColor == themed.getColor(R.color.ui_surface))
                    val balance = rendered.findViewById<TextView>(R.id.widget_balance)
                    check(balance.text.toString() == model.balanceText)
                    if (index == 1) check(balance.currentTextColor == themed.getColor(R.color.ui_positive))
                    if (index == 2) check(balance.currentTextColor == themed.getColor(R.color.ui_problem))
                    val number = rendered.findViewById<TextView>(R.id.widget_device)
                    check(number.text.isNotEmpty())
                    if (index == 4) check(number.text.toString() == "000124")
                    for (text in listOf(number, balance, rendered.findViewById<TextView>(R.id.widget_status), button)) {
                        check(text.layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom) {
                            "Clipped ${text.text}, ${width}x${height}, font=$fontScale, night=$night; textHeight=${text.layout.height}, available=${text.height - text.compoundPaddingTop - text.compoundPaddingBottom}, textSize=${text.textSize}"
                        }
                    }
                    checked++
                    if (!busy && ((width == 166 && fontScale == 1f) ||
                            (index == 1 && (width == 110 || (width == 166 && fontScale == 2f))))) {
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        rendered.draw(Canvas(bitmap))
                        val name = "${if (night) "dark" else "light"}-state$index-${width}x$height-font$fontScale.png"
                        File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle(); saved++
                    }
                }
            }
        }
        return "PASS: $checked widget render cases, $saved native PNG previews; day/night, font 0.85x/1x/2x, four sizes. No network or control actions."
    }
}

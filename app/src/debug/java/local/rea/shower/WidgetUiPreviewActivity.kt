package local.rea.shower

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import java.io.File

/** Debug-only, empty-data preview. It never creates AppRuntime or registers real widget actions. */
class WidgetUiPreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!packageName.endsWith(".preview")) { finish(); return }
        val label = TextView(this).apply { text = "正在验证离线界面…"; textSize = 18f; setPadding(32, 80, 32, 32) }
        setContentView(label)
        label.post {
            val result = try { WidgetUiChecks.run(this) }
                catch (e: Throwable) { "FAIL: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}" }
            val output = File(getExternalFilesDir(null), "ui-preview").apply { mkdirs() }
            File(output, "result.txt").writeText(result)
            label.text = result
        }
    }
}

package local.rea.shower

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle

/** Executes the same offline native layout checks as the isolated preview app. */
class WidgetSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            runOnMainSync { result.putString("stream", WidgetUiChecks.run(targetContext) + "\n") }
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: ${e.javaClass.simpleName}: ${e.message}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}

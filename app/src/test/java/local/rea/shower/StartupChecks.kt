package local.rea.shower

import android.app.Activity
import android.view.WindowInsetsController
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs real Android window code in an isolated JVM, with no account or network access. */
@RunWith(RobolectricTestRunner::class)
class StartupChecks {
    @Test
    @Config(sdk = [30, 36])
    fun originalControllerAccessCrashesBeforeDecorExists() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val window = controller.get().window
        assertNull(window.peekDecorView())
        // The safe call guards the returned controller, not the dereference inside PhoneWindow.
        assertThrows(NullPointerException::class.java) {
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        }
    }

    @Test
    @Config(sdk = [26, 30, 36])
    fun coldCreateReachesContentOnLegacyAndModernAndroid() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        assertNull(controller.get().window.peekDecorView())
        controller.create()
        val activity = controller.get()
        assertNotNull(activity.window.peekDecorView())
        assertTrue(activity.findViewById<FrameLayout>(android.R.id.content).childCount > 0)
        controller.destroy()
    }
}

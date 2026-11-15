package local.rea.shower

import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowAppWidgetManager
import java.time.Duration
import com.google.gson.JsonObject

/** No Android Keystore or real account files are involved in the launcher tests. */
@Implements(value = EncryptedStore::class, isInAndroidSdk = false)
class MemoryPinStore {
    private var state = JsonObject()
    @Implementation fun read(): JsonObject = state.deepCopy()
    @Implementation fun write(value: JsonObject) { state = value.deepCopy() }
}

/** Simulates a launcher that acknowledges pin requests without binding the widget. */
@Implements(AppWidgetManager::class)
class DeferredPinManager : ShadowAppWidgetManager() {
    companion object { var requests = 0 }
    @Implementation
    override fun requestPinAppWidget(provider: ComponentName, extras: Bundle?, callback: PendingIntent?): Boolean {
        requests++
        return true
    }
}

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [30], shadows = [DeferredPinManager::class, MemoryPinStore::class], instrumentedPackages = ["local.rea.shower"])
@LooperMode(LooperMode.Mode.PAUSED)
class WidgetPinChecks {
    @Before fun resetRequests() { DeferredPinManager.requests = 0 }

    private fun activity(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible().also {
            shadowOf(Looper.getMainLooper()).idle()
            it.get().findViewById<ViewGroup>(android.R.id.content)
                .getChildAt(0).let { view -> (view as ShowerScreen).selectTab(1) }
        }
    private fun manager(activity: MainActivity) = shadowOf(AppWidgetManager.getInstance(activity))
    private fun button(activity: MainActivity) = activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<Button>("add_widget")
    private fun advance() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
    private fun texts(view: View): List<String> = (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
    private fun showingGuide() = ShadowAlertDialog.getLatestAlertDialog()?.takeIf { it.isShowing }

    @Test fun unsupportedLauncherShowsGuideImmediately() {
        val c = activity()
        manager(c.get()).setRequestPinAppWidgetSupported(false)
        button(c.get()).performClick()
        assertNotNull(showingGuide())
        assertEquals("返回桌面", showingGuide()!!.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals(0, DeferredPinManager.requests)
        c.pause().stop().destroy()
    }

    @Test fun acknowledgedButSuppressedRequestShowsGuideEvenWithExistingWidget() {
        val c = activity()
        manager(c.get()).apply {
            setRequestPinAppWidgetSupported(true)
            bindAppWidgetId(12, ComponentName(c.get(), WidgetProvider::class.java))
        }
        button(c.get()).performClick()
        assertFalse(button(c.get()).isEnabled)
        advance()
        assertNotNull(showingGuide())
        assertTrue(button(c.get()).isEnabled)
        val labels = texts(c.get().findViewById(android.R.id.content))
        assertTrue(labels.contains("已添加 1 个小组件"))
        assertTrue(labels.contains("桌面未完成添加，请按说明手动添加。"))
        c.pause().stop().destroy()
    }

    @Test fun newWidgetConfirmsSuccessWithoutFallback() {
        val c = activity()
        manager(c.get()).setRequestPinAppWidgetSupported(true)
        button(c.get()).performClick()
        manager(c.get()).bindAppWidgetId(42, ComponentName(c.get(), WidgetProvider::class.java))
        advance()
        assertNull(showingGuide())
        assertTrue(texts(c.get().findViewById(android.R.id.content)).contains("小组件已添加到桌面。"))
        c.pause().stop().destroy()
    }

    @Test fun waitsWhileSystemConfirmationIsInForeground() {
        val c = activity()
        manager(c.get()).setRequestPinAppWidgetSupported(true)
        button(c.get()).performClick()
        c.pause()
        advance()
        assertNull(showingGuide())
        c.resume()
        advance()
        assertNotNull(showingGuide())
        c.pause().stop().destroy()
    }

    @Test fun repeatedTapDoesNotSubmitDuplicateRequests() {
        val c = activity()
        manager(c.get()).setRequestPinAppWidgetSupported(true)
        button(c.get()).performClick()
        button(c.get()).performClick()
        assertEquals(1, DeferredPinManager.requests)
        advance()
        c.pause().stop().destroy()
    }

    @Test fun pendingRequestSurvivesActivityRecreation() {
        val c = activity()
        manager(c.get()).setRequestPinAppWidgetSupported(true)
        button(c.get()).performClick()
        c.recreate()
        advance()
        assertNotNull(showingGuide())
        assertEquals(1, DeferredPinManager.requests)
        c.pause().stop().destroy()
    }
}

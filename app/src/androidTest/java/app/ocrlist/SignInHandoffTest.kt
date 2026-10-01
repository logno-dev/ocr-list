package app.ocrlist

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.ocrlist.chatgpt.AndroidNetworkDiagnostics
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Test APK only; no browser/network/account is needed for lifecycle regression coverage. */
class TestBrowserActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "External browser stand-in" })
    }
}

@RunWith(AndroidJUnit4::class)
class SignInHandoffTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun returnLinkResumesOriginalActivityAndOnlyThenAllowsExchange() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var original: MainActivity
            lateinit var model: ListViewModel
            scenario.onActivity { activity ->
                original = activity
                model = ViewModelProvider(activity)[ListViewModel::class.java]
                assertTrue(model.signInForeground.isResumed)
                // Launch another Activity above OCR List in the same task, as a browser can do.
                activity.startActivity(Intent().setComponent(ComponentName(instrumentation.context, TestBrowserActivity::class.java)))
            }
            waitUntil { !model.signInForeground.isResumed }
            val exchange = CountDownLatch(1)
            lateinit var pending: Job
            instrumentation.runOnMainSync {
                pending = model.viewModelScope.launch { model.signInForeground.awaitResumed(); exchange.countDown() }
            }
            assertFalse("Token exchange must not run behind the browser", exchange.await(1, TimeUnit.SECONDS))

            instrumentation.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("ocrlist://oauth-complete"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue("Returning to OCR List should release the pending exchange", exchange.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().single()
                assertSame("Callback must reuse the Activity that owns the login", original, resumed)
                assertSame(model, ViewModelProvider(resumed)[ListViewModel::class.java])
                assertTrue(model.signInForeground.isResumed)
                assertTrue(pending.isCompleted)
            }

            // Configuration changes preserve the ViewModel but correctly pause/resume the gate.
            scenario.recreate()
            scenario.onActivity {
                assertSame(model, ViewModelProvider(it)[ListViewModel::class.java])
                assertTrue(model.signInForeground.isResumed)
            }
        }
    }

    @Test fun diagnosticsCanReadAndroidNetworkPolicyWithoutRuntimePermission() {
        val details = AndroidNetworkDiagnostics(instrumentation.targetContext) { true }.describe()
        assertTrue(details.contains("app foreground"))
        assertTrue(details.contains("Data Saver"))
        assertFalse(details.contains("network state unavailable"))
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for the app/browser lifecycle transition")
    }
}

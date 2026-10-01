package app.ocrlist

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppInstrumentedTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun reset() { ListStore(app).save(emptyList()) }

    @Test fun checklistSurvivesRecreationAndKeepsCheckedItemsInPlace() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            onView(withText("Start a blank list")).perform(click())
            addItem("Milk")
            addItem("Bread")
            onView(withContentDescription("Mark Milk complete")).perform(click())
            val saved = ListStore(app).load().single()
            assertEquals(listOf("Milk", "Bread"), saved.items.map { it.text })
            assertTrue(saved.items.first().checked)
            scenario.recreate()
            onView(withContentDescription("Mark Milk incomplete")).check(matches(isChecked()))
            onView(withText("Bread")).check(matches(isDisplayed()))
        }
    }

    @Test fun bundledOcrPreservesOriginalAndPhotoBounds() {
        val bitmap = Bitmap.createBitmap(1200, 700, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f }
        canvas.drawText("2x Milk", 80f, 180f, paint)
        canvas.drawText("Bread", 80f, 340f, paint)
        canvas.drawText("Apples x3", 80f, 500f, paint)
        val original = File(app.cacheDir, "ocr-test.png")
        original.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val viewModelStore = ViewModelStore()
        lateinit var model: ListViewModel
        instrumentation.runOnMainSync {
            model = ViewModelProvider(viewModelStore, ViewModelProvider.AndroidViewModelFactory(app))[ListViewModel::class.java]
        }
        waitUntil { model.busy.value == false }
        instrumentation.runOnMainSync { model.scan(Uri.fromFile(original)) }
        waitUntil { model.busy.value == false }
        val list = ListStore(app).load().single()
        assertEquals(listOf("Milk", "Bread", "Apples"), list.items.map { it.text })
        assertEquals(listOf("2", "", "3"), list.items.map { it.quantity })
        assertTrue(list.items.all { it.bounds != null })
        assertTrue(list.items[0].bounds!!.top < list.items[1].bounds!!.top)
        assertArrayEquals(original.readBytes(), File(app.filesDir, list.photo!!).readBytes())
        ActivityScenario.launch(MainActivity::class.java).use {
            instrumentation.waitForIdleSync()
            onView(withContentDescription("${list.title}, 0 of 3 complete")).perform(click())
            onView(withContentDescription("Edit Milk, with photo reference")).perform(click())
            onView(withContentDescription("Original handwriting for Milk. Tap to view the whole photo.")).perform(click())
            onView(isAssignableFrom(PhotoView::class.java)).check(matches(isDisplayed()))
            onView(withText("Done")).perform(click())
            onView(allOf(isAssignableFrom(android.widget.EditText::class.java), withHint("Item")))
                .perform(replaceText("Oat milk"), closeSoftKeyboard())
            onView(withText("Save")).perform(click())
            val corrected = ListStore(app).load().single().items.first()
            assertEquals("Oat milk", corrected.text)
            assertEquals(list.items.first().bounds, corrected.bounds)
            assertEquals(list.items.first().originalText, corrected.originalText)
        }
        instrumentation.runOnMainSync { model.delete(list); viewModelStore.clear() }
        assertTrue(ListStore(app).load().isEmpty())
        original.delete()
    }

    @Test fun storageRoundTripsCorrectionsAndPhotoReferences() {
        val bounds = PhotoBounds(.1f, .2f, .8f, .3f)
        val list = Checklist(title = "Groceries", photo = "photos/example.image", items = listOf(
            ListItem(text = "Oat milk", quantity = "2", checked = true, originalText = "2x 0at milk", bounds = bounds),
            ListItem(text = "Bread"),
        ))
        val store = ListStore(app)
        store.save(listOf(list))
        assertEquals(listOf(list), store.load())
        val revised = list.copy(items = list.items.map { it.copy(checked = false) })
        store.save(listOf(revised))
        assertEquals(listOf(revised), store.load())
    }

    private fun addItem(text: String) {
        onView(withText("+  Add item")).perform(click())
        onView(allOf(isAssignableFrom(android.widget.EditText::class.java), withHint("Item"))).perform(typeText(text), closeSoftKeyboard())
        onView(withText("Save")).perform(click())
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(100)
        }
        fail("Timed out waiting for on-device operation")
    }
}

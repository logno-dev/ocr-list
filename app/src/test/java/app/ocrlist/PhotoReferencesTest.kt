package app.ocrlist

import org.junit.Assert.*
import org.junit.Test

class PhotoReferencesTest {
    private val bounds = PhotoBounds(.1f, .2f, .8f, .3f)
    @Test fun onlyAttachesUniqueExactNormalizedMatches() {
        val items = listOf(ListItem(text = "Milk", quantity = "2"), ListItem(text = "Bread"))
        val lines = listOf(ListItem(text = "MILK", bounds = bounds), ListItem(text = "Brcad", bounds = bounds))
        val result = PhotoReferences.attach(items, lines)
        assertEquals(bounds, result[0].bounds)
        assertNull(result[1].bounds)
        assertEquals("2", result[0].quantity)
    }
    @Test fun neverGuessesBetweenDuplicateEntries() {
        val item = ListItem(text = "Milk")
        val line = ListItem(text = "milk", bounds = bounds)
        assertNull(PhotoReferences.attach(listOf(item), listOf(line, line))[0].bounds)
        assertTrue(PhotoReferences.attach(listOf(item, item), listOf(line)).all { it.bounds == null })
    }
}

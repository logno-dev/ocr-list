package app.ocrlist

import org.junit.Assert.*
import org.junit.Test

class ItemParserTest {
    @Test fun normalizesExplicitQuantities() {
        for (raw in listOf("2x milk", "2 x milk", "milk x2", "milk × 2")) {
            val item = ItemParser.parse(raw)!!
            assertEquals("milk", item.text)
            assertEquals("2", item.quantity)
            assertEquals(raw, item.originalText)
        }
    }
    @Test fun removesListMarkersButPreservesMeaningfulNumbers() {
        assertEquals("Bread", ItemParser.parse("• Bread")!!.text)
        assertEquals("Bread", ItemParser.parse("[x] Bread")!!.text)
        assertFalse(ItemParser.parse("[x] Bread")!!.checked)
        assertEquals("Apples", ItemParser.parse("1. Apples")!!.text)
        for (raw in listOf("2% milk", "12 eggs", "Vitamin B12", "2x4 lumber", "Xbox")) {
            assertEquals(raw, ItemParser.parse(raw)!!.text)
            assertEquals("", ItemParser.parse(raw)!!.quantity)
        }
    }
    @Test fun retainsPhotoReferenceAndNormalizesDecimalQuantity() {
        val bounds = PhotoBounds(.1f, .2f, .8f, .3f)
        val item = ItemParser.parse("- 1,5x flour", bounds)!!
        assertEquals("flour", item.text)
        assertEquals("1.5", item.quantity)
        assertEquals(bounds, item.bounds)
    }
    @Test fun skipsEmptyLines() {
        assertNull(ItemParser.parse("  "))
        assertNull(ItemParser.parse("• "))
    }
}

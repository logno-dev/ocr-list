package app.ocrlist

import java.util.UUID

data class PhotoBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

data class ListItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val quantity: String = "",
    val checked: Boolean = false,
    val originalText: String = "",
    val bounds: PhotoBounds? = null,
)

data class Checklist(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val photo: String? = null,
    val items: List<ListItem> = emptyList(),
)

object ItemParser {
    private val bullet = Regex("^\\s*(?:[-•●▪□☐☑✓✔]|\\[\\s*[xX]?\\s*]|\\d+[.)])(?:\\s+|$)")
    private val prefix = Regex("^(\\d+(?:[.,]\\d+)?)\\s*[xX×]\\s+(.+)$")
    private val suffix = Regex("^(.+?)\\s+[xX×]\\s*(\\d+(?:[.,]\\d+)?)$")

    fun parse(raw: String, bounds: PhotoBounds? = null): ListItem? {
        val text = raw.trim().replace(bullet, "").trim()
        if (text.isBlank()) return null
        val start = prefix.matchEntire(text)
        val end = suffix.matchEntire(text)
        val name = start?.groupValues?.get(2) ?: end?.groupValues?.get(1) ?: text
        val quantity = start?.groupValues?.get(1) ?: end?.groupValues?.get(2) ?: ""
        return ListItem(text = name.trim(), quantity = quantity.replace(',', '.'), originalText = raw, bounds = bounds)
    }
}

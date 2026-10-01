package app.ocrlist

import java.util.Locale

object PhotoReferences {
    // Never guess a crop from model-generated coordinates or fuzzy handwriting matches.
    // Only a unique exact normalized line match is safe enough to attach a reference.
    fun attach(items: List<ListItem>, lines: List<ListItem>): List<ListItem> {
        fun key(text: String) = text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
        val references = lines.groupBy { key(it.text) }
        val counts = items.groupingBy { key(it.text) }.eachCount()
        return items.map { item ->
            val name = key(item.text)
            val reference = references[name]?.singleOrNull()
            if (name.isNotEmpty() && counts[name] == 1 && reference != null) item.copy(bounds = reference.bounds) else item
        }
    }
}

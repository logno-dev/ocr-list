package app.ocrlist

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ListStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "lists.json"))

    fun load(): List<Checklist> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val array = JSONArray(file.openRead().bufferedReader().use { it.readText() })
        return (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            val items = obj.getJSONArray("items")
            Checklist(
                id = obj.getString("id"), title = obj.getString("title"),
                createdAt = obj.getLong("createdAt"),
                photo = if (obj.isNull("photo")) null else obj.getString("photo"),
                items = (0 until items.length()).map { itemIndex ->
                    val item = items.getJSONObject(itemIndex)
                    val bounds = item.optJSONArray("bounds")
                    ListItem(
                        id = item.getString("id"), text = item.getString("text"),
                        quantity = item.optString("quantity"), checked = item.optBoolean("checked"),
                        originalText = item.optString("originalText"),
                        bounds = bounds?.let { PhotoBounds(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat(), it.getDouble(3).toFloat()) },
                    )
                },
            )
        }
    }

    fun save(lists: List<Checklist>) {
        val array = JSONArray()
        lists.forEach { list ->
            val items = JSONArray()
            list.items.forEach { item ->
                items.put(JSONObject().apply {
                    put("id", item.id); put("text", item.text); put("quantity", item.quantity)
                    put("checked", item.checked); put("originalText", item.originalText)
                    item.bounds?.let { put("bounds", JSONArray(listOf(it.left, it.top, it.right, it.bottom))) }
                })
            }
            array.put(JSONObject().apply {
                put("id", list.id); put("title", list.title); put("createdAt", list.createdAt)
                put("photo", list.photo ?: JSONObject.NULL); put("items", items)
            })
        }
        val stream = file.startWrite()
        try {
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}

package app.ocrlist

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class ListViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ListStore(application)
    val lists = MutableLiveData<List<Checklist>>(emptyList())
    val busy = MutableLiveData(true)
    val message = MutableLiveData<String?>()
    val openList = MutableLiveData<String?>()
    var loadFailed = false
        private set

    init {
        viewModelScope.launch {
            try { lists.value = withContext(Dispatchers.IO) { store.load() } }
            catch (_: Exception) {
                loadFailed = true
                message.value = "Your saved lists could not be read. Restart the app to try again. Your files have been kept."
            }
            finally { busy.value = false }
        }
    }

    // Commit before publishing: a failed disk write never looks like a successful edit.
    private fun commit(next: List<Checklist>): Boolean {
        if (loadFailed) return false
        return try { store.save(next); lists.value = next; true }
        catch (_: Exception) { message.value = "Could not save. Check your device’s free space and try again."; false }
    }

    fun update(list: Checklist) = commit(lists.value.orEmpty().map { if (it.id == list.id) list else it })

    fun create() {
        val list = Checklist(title = "Untitled list")
        if (commit(listOf(list) + lists.value.orEmpty())) openList.value = list.id
    }

    fun delete(list: Checklist) {
        if (commit(lists.value.orEmpty().filterNot { it.id == list.id })) {
            list.photo?.let { name -> viewModelScope.launch(Dispatchers.IO) { File(getApplication<Application>().filesDir, name).delete() } }
        }
    }

    fun scan(uri: Uri, capturedFile: File? = null) {
        if (busy.value == true || loadFailed) return
        busy.value = true
        viewModelScope.launch {
            val app = getApplication<Application>()
            val relative = "photos/${UUID.randomUUID()}.image"
            val file = File(app.filesDir, relative)
            var retained = false
            try {
                val items = withContext(Dispatchers.IO) {
                    file.parentFile!!.mkdirs()
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    } ?: error("Photo unavailable")
                    val bitmap = PhotoLoader.decode(file)
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    try {
                        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                        // Preserve ML Kit's block reading order (including multi-column lists).
                        result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                            val bounds = line.boundingBox?.let {
                                PhotoBounds(it.left.toFloat() / bitmap.width, it.top.toFloat() / bitmap.height,
                                    it.right.toFloat() / bitmap.width, it.bottom.toFloat() / bitmap.height)
                            }
                            ItemParser.parse(line.text, bounds)
                        }
                    } finally { recognizer.close(); bitmap.recycle() }
                }
                val title = "List · " + SimpleDateFormat("MMM d", Locale.getDefault()).format(Date())
                val list = Checklist(title = title, photo = relative, items = items)
                retained = commit(listOf(list) + lists.value.orEmpty())
                if (retained) {
                    openList.value = list.id
                    message.value = if (items.isEmpty()) "No text found. Your photo is saved; add items manually or try a clearer photo."
                    else "${items.size} items found. Tap any item to check the photo and correct it."
                }
            } catch (_: Exception) {
                message.value = "Could not read this photo. Try a different image or take another photo in good light."
            } finally {
                withContext(Dispatchers.IO) {
                    if (!retained) file.delete()
                    capturedFile?.delete()
                }
                busy.value = false
            }
        }
    }
}

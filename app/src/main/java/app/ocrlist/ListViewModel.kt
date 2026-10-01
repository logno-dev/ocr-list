package app.ocrlist

import android.app.Application
import android.net.Uri
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import app.ocrlist.chatgpt.ChatGptClient
import app.ocrlist.chatgpt.ChatGptException
import app.ocrlist.chatgpt.ChatGptState
import app.ocrlist.chatgpt.LoopbackSignIn
import app.ocrlist.chatgpt.OpenAiHttpFailure
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    private val chat = ChatGptClient(application)
    private var signIn: LoopbackSignIn? = null
    private var authJob: Job? = null
    val lists = MutableLiveData<List<Checklist>>(emptyList())
    val busy = MutableLiveData(true)
    val status = MutableLiveData("Loading lists…")
    val chatState = MutableLiveData(ChatGptState())
    val authBusy = MutableLiveData(false)
    val browserUrl = MutableLiveData<String?>()
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
            finally {
                chat.initialize()
                chatState.value = chat.state
                busy.value = false
            }
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

    fun connectChatGpt(accountId: String? = null) = accountAction {
        try {
            val attempt = chat.beginSignIn(accountId)
            signIn = attempt
            browserUrl.value = attempt.url
            val callback = attempt.awaitCallback()
            attempt.close()
            chat.finishSignIn(attempt, callback)
            try { chat.refreshModels() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val detail = (error as? ChatGptException)?.message ?: "OpenAI returned an unexpected model list."
                val recovery = if (error is OpenAiHttpFailure && error.status == 401) "Sign in again in Recognition settings."
                    else "Open Recognition → Refresh available models; no need to sign in again."
                message.value = "Sign-in completed, but models could not load. $detail $recovery"
                return@accountAction
            }
            message.value = "ChatGPT connected. Scans now send a photo to OpenAI and use your ChatGPT plan."
        } finally { signIn?.close(); signIn = null; browserUrl.value = null }
    }

    fun cancelSignIn() { authJob?.cancel(); signIn?.close() }

    fun useOffline() = accountAction { chat.useOffline() }
    fun chooseAccount(id: String) = accountAction { chat.selectAccount(id) }
    fun chooseModel(id: String) = accountAction { chat.selectModel(id) }
    fun refreshModels() = accountAction { chat.refreshModels(); message.value = "Available ChatGPT models refreshed." }
    fun signOut() = accountAction {
        message.value = if (chat.signOut()) "Signed out. Scans now use on-device recognition."
        else "Signed out on this device. Remote revocation was not confirmed; disconnect OCR List in ChatGPT Settings."
    }
    fun resetConnections() = accountAction { chat.resetUnreadableStore() }

    private fun accountAction(action: suspend () -> Unit) {
        if (authBusy.value == true || busy.value == true) return
        authBusy.value = true
        authJob = viewModelScope.launch {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                message.value = (error as? ChatGptException)?.message ?: "Could not finish connecting ChatGPT. Try again in recognition settings."
            } finally { chatState.value = chat.state; authBusy.value = false }
        }
    }

    fun rescan(list: Checklist) {
        list.photo?.let { scan(Uri.fromFile(File(getApplication<Application>().filesDir, it)), title = list.title) }
    }

    fun scan(uri: Uri, capturedFile: File? = null, title: String? = null) {
        if (busy.value == true || authBusy.value == true || loadFailed) return
        val cloud = chatState.value?.useCloud == true
        status.value = if (cloud) "Reading with ChatGPT…" else "Reading on your device…"
        busy.value = true
        viewModelScope.launch {
            val app = getApplication<Application>()
            val relative = "photos/${UUID.randomUUID()}.image"
            val file = File(app.filesDir, relative)
            var retained = false
            var copied = false
            try {
                val items = withContext(Dispatchers.IO) {
                    file.parentFile!!.mkdirs()
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    } ?: error("Photo unavailable")
                    copied = true
                    val bitmap = PhotoLoader.decode(file)
                    try {
                        if (cloud) {
                            val recognized = chat.transcribe(bitmap)
                            val lines = try { offlineItems(bitmap) }
                            catch (error: CancellationException) { throw error }
                            catch (_: Exception) { emptyList() }
                            PhotoReferences.attach(recognized, lines)
                        } else {
                            offlineItems(bitmap)
                        }
                    } finally { bitmap.recycle() }
                }
                val list = Checklist(title = title ?: datedTitle(), photo = relative, items = items)
                retained = commit(listOf(list) + lists.value.orEmpty())
                if (retained) {
                    openList.value = list.id
                    message.value = if (items.isEmpty()) "No text found. Your photo is saved; add items manually or try a clearer photo."
                    else "${items.size} items found. Review the text against your saved photo."
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // A failed cloud request must not discard a one-off camera photo or replace existing edits.
                if (copied) {
                    val list = Checklist(title = title ?: datedTitle(), photo = relative)
                    retained = commit(listOf(list) + lists.value.orEmpty())
                    if (retained) openList.value = list.id
                }
                val detail = (error as? ChatGptException)?.message ?: "Could not read this photo. Try a different image or better lighting."
                message.value = detail + if (retained) " Photo saved. Use List options → Scan photo again to retry." else ""
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    if (!retained) file.delete()
                    capturedFile?.delete()
                }
                chatState.value = chat.state
                busy.value = false
            }
        }
    }

    private fun datedTitle() = "List · " + SimpleDateFormat("MMM d", Locale.getDefault()).format(Date())

    private suspend fun offlineItems(bitmap: Bitmap): List<ListItem> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            return result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val bounds = line.boundingBox?.let {
                    PhotoBounds(it.left.toFloat() / bitmap.width, it.top.toFloat() / bitmap.height,
                        it.right.toFloat() / bitmap.width, it.bottom.toFloat() / bitmap.height)
                }
                ItemParser.parse(line.text, bounds)
            }
        } finally { recognizer.close() }
    }

    override fun onCleared() {
        signIn?.close()
        chat.close()
        super.onCleared()
    }
}

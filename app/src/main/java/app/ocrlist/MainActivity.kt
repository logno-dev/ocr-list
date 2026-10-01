package app.ocrlist

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private val model: ListViewModel by viewModels()
    private lateinit var root: LinearLayout
    private var scroll: ScrollView? = null
    private var selectedId: String? = null
    private var capturePath: String? = null
    private var photoName: String? = null
    private var photoBitmap: Bitmap? = null
    private val night get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val ink get() = (if (night) "#EEF3ED" else "#202D26").toColorInt()
    private val muted get() = (if (night) "#B4BFB7" else "#65746A").toColorInt()
    private val paper get() = (if (night) "#141C17" else "#F7F8F2").toColorInt()
    private val surface get() = (if (night) "#202B23" else "#FFFFFF").toColorInt()

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) model.scan(uri)
    }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        capturePath?.let { path ->
            val file = File(path)
            if (success) model.scan(Uri.fromFile(file), file) else file.delete()
        }
        capturePath = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedId = savedInstanceState?.getString("selected")
        capturePath = savedInstanceState?.getString("capture")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        root = column().apply { setBackgroundColor(paper) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectedId != null) { selectedId = null; render() }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        model.lists.observe(this) { render() }
        model.busy.observe(this) { render() }
        model.status.observe(this) { render() }
        model.chatState.observe(this) { render() }
        model.authBusy.observe(this) { render() }
        model.browserUrl.observe(this) { url ->
            if (url != null) {
                model.browserUrl.value = null
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                catch (_: Exception) {
                    model.cancelSignIn()
                    model.message.value = "Install or enable a web browser to sign in with ChatGPT."
                }
            }
        }
        model.openList.observe(this) { id ->
            if (id != null) { selectedId = id; model.openList.value = null; render() }
        }
        model.message.observe(this) { message ->
            if (message != null) {
                Snackbar.make(root, message, Snackbar.LENGTH_INDEFINITE).setAction("OK") {}.apply {
                    view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).maxLines = 10
                }.show()
                model.message.value = null
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected", selectedId)
        outState.putString("capture", capturePath)
        super.onSaveInstanceState(outState)
    }

    private fun current() = model.lists.value.orEmpty().firstOrNull { it.id == selectedId }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun label(value: String, size: Float = 16f, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
    }
    private fun button(value: String, outlined: Boolean = false, action: () -> Unit) = MaterialButton(
        this, null, if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle else com.google.android.material.R.attr.materialButtonStyle
    ).apply {
        text = value; isAllCaps = false; minHeight = dp(52); cornerRadius = dp(16)
        setOnClickListener { action() }
    }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun card(content: View) = MaterialCardView(this).apply {
        radius = dp(20).toFloat(); cardElevation = 0f; strokeWidth = 0; setCardBackgroundColor(surface)
        addView(content)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    }

    private fun render() {
        if (!::root.isInitialized) return
        val oldY = scroll?.scrollY ?: 0
        val oldTag = scroll?.tag
        root.removeAllViews()
        val list = current()
        if (list == null && model.busy.value != true) selectedId = null
        val header = column().apply { setPadding(dp(24), dp(16), dp(24), dp(8)) }
        root.addView(header)
        if (list == null) {
            header.addView(label("OCR List", 32f).apply { setTypeface(typeface, Typeface.BOLD) })
            header.addView(label("From paper to done.", 16f, muted))
            header.addView(button(if (model.chatState.value?.useCloud == true) "Recognition: ChatGPT" else "Recognition: on-device", true) {
                recognitionSettings()
            }.apply { isEnabled = model.busy.value != true && model.authBusy.value != true })
        } else {
            header.addView(button("‹  All lists", true) { selectedId = null; render() }.apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2)
            })
            header.addView(label(list.title, 28f).apply {
                setTypeface(typeface, Typeface.BOLD)
                contentDescription = "${list.title}. Tap to rename"
                setPadding(0, dp(8), 0, dp(8)); setOnClickListener { rename(list) }
            })
            val done = list.items.count { it.checked }
            header.addView(label("$done of ${list.items.size} complete · items stay in order", 14f, muted))
            header.addView(LinearProgressIndicator(this).apply {
                max = maxOf(1, list.items.size); progress = done
                layoutParams = LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(12) }
            })
        }
        if (model.busy.value == true) {
            header.addView(LinearProgressIndicator(this).apply { isIndeterminate = true })
            header.addView(label(model.status.value.orEmpty(), 14f, muted))
        }
        if (model.authBusy.value == true) {
            header.addView(LinearProgressIndicator(this).apply { isIndeterminate = true })
            header.addView(label("Connecting to ChatGPT…", 14f, muted))
            header.addView(button("Cancel connection", true) { model.cancelSignIn() })
        }
        val body = column().apply { setPadding(dp(20), dp(16), dp(20), dp(20)) }
        scroll = ScrollView(this).apply {
            tag = selectedId; isFillViewport = true; addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (list == null) renderHome(body) else renderList(body, list)
        val footer = column().apply { setPadding(dp(20), dp(4), dp(20), dp(12)) }
        if (list == null) {
            footer.addView(button("Scan a list") { scanOptions() }.apply { isEnabled = model.busy.value != true && model.authBusy.value != true && !model.loadFailed })
            footer.addView(button("Start a blank list", true) { model.create() }.apply { isEnabled = model.busy.value != true && !model.loadFailed })
            val privacy = if (model.chatState.value?.useCloud == true) "Photos sent to OpenAI · uses your ChatGPT plan" else "On-device recognition · no photo uploads"
            footer.addView(label(privacy, 12f, muted).apply { gravity = Gravity.CENTER })
        } else {
            footer.addView(button("+  Add item") { editItem(null) })
        }
        root.addView(footer)
        if (oldTag == selectedId) scroll?.post { scroll?.scrollTo(0, oldY) }
    }

    private fun renderHome(body: LinearLayout) {
        val lists = model.lists.value.orEmpty()
        if (lists.isEmpty()) {
            val intro = column().apply { setPadding(dp(24), dp(32), dp(24), dp(32)) }
            intro.addView(label("A little less rewriting.", 26f).apply { setTypeface(typeface, Typeface.BOLD) })
            intro.addView(space(16))
            intro.addView(label("Snap your handwritten list and turn each line into a checkable item.", 18f, muted))
            intro.addView(space(24))
            intro.addView(label("1   Take a photo or choose an image\n\n2   Review the text beside your photo\n\n3   Check things off, right where they are", 16f))
            intro.addView(space(20))
            intro.addView(label("Your original photo stays with the list, so a missed word is easy to fix.", 14f, muted))
            body.addView(card(intro))
        } else {
            body.addView(label("YOUR LISTS", 12f, muted).apply { setPadding(dp(4), 0, 0, dp(12)); letterSpacing = .12f })
            lists.forEach { list ->
                val content = column().apply { setPadding(dp(20), dp(20), dp(20), dp(20)) }
                content.addView(label(list.title, 21f).apply { setTypeface(typeface, Typeface.BOLD) })
                content.addView(space(6))
                val done = list.items.count { it.checked }
                content.addView(label("$done / ${list.items.size} complete  ·  ${if (list.photo != null) "Photo saved" else "Manual list"}", 14f, muted))
                val preview = list.items.take(3).joinToString(" · ") { it.text }
                if (preview.isNotEmpty()) content.addView(label(preview, 14f, muted).apply { maxLines = 2; setPadding(0, dp(10), 0, 0) })
                body.addView(card(content).apply {
                    isClickable = true; isFocusable = true
                    contentDescription = "${list.title}, $done of ${list.items.size} complete"
                    setOnClickListener { selectedId = list.id; render() }
                })
            }
        }
    }

    private fun renderList(body: LinearLayout, list: Checklist) {
        val tools = row()
        if (list.photo != null) tools.addView(button("View original photo", true) { showPhoto(list) }, LinearLayout.LayoutParams(0, -2, 1f))
        tools.addView(button("•••", true) { listOptions(list) }.apply { contentDescription = "List options" }, LinearLayout.LayoutParams(dp(64), -2))
        body.addView(tools)
        body.addView(space(8))
        if (list.items.isEmpty()) body.addView(label("Your list is ready. Add your first item below.", 18f, muted).apply { setPadding(dp(8), dp(32), dp(8), dp(32)) })
        list.items.forEach { item ->
            val line = row().apply { setPadding(dp(4), dp(6), dp(12), dp(6)) }
            line.addView(MaterialCheckBox(this).apply {
                isChecked = item.checked
                contentDescription = "Mark ${item.text} ${if (item.checked) "incomplete" else "complete"}"
                minWidth = dp(48); minHeight = dp(56)
                setOnCheckedChangeListener { _, checked ->
                    current()?.let { fresh -> model.update(fresh.copy(items = fresh.items.map { if (it.id == item.id) it.copy(checked = checked) else it })) }
                }
            })
            val words = column().apply {
                setPadding(dp(4), dp(10), dp(8), dp(10))
                minimumHeight = dp(56); gravity = Gravity.CENTER_VERTICAL
                isClickable = true; isFocusable = true
                contentDescription = "Edit ${item.text}${if (item.bounds != null) ", with photo reference" else ""}"
                setOnClickListener { editItem(item.id) }
            }
            words.addView(label(item.text, 18f, if (item.checked) muted else ink).apply {
                if (item.checked) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            })
            if (item.bounds != null) words.addView(label("Tap to edit · photo reference", 11f, muted))
            if (item.needsReview) words.addView(label("Review handwriting", 12f, muted).apply { setTypeface(typeface, Typeface.BOLD) })
            line.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            if (item.quantity.isNotBlank()) line.addView(label("× ${item.quantity}", 16f, muted).apply { setOnClickListener { editItem(item.id) } })
            body.addView(card(line))
        }
    }

    private fun scanOptions() {
        if (model.chatState.value?.useCloud == true && model.chatState.value?.ready != true) {
            recognitionSettings(); return
        }
        MaterialAlertDialogBuilder(this).setTitle(if (model.chatState.value?.useCloud == true) "Scan with ChatGPT" else "Scan on-device")
            .setItems(arrayOf("Take a photo", "Choose a photo")) { _, which ->
                if (which == 1) picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                else takePhoto()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun takePhoto() {
        try {
            val folder = File(filesDir, "pending").apply { mkdirs() }
            val file = File(folder, "${UUID.randomUUID()}.jpg")
            capturePath = file.absolutePath
            camera.launch(FileProvider.getUriForFile(this, "$packageName.files", file))
        } catch (_: Exception) {
            capturePath?.let { File(it).delete() }; capturePath = null
            Snackbar.make(root, "No camera available. Choose an existing photo instead.", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun field(container: LinearLayout, hint: String, value: String): TextInputEditText {
        val wrapper = TextInputLayout(this).apply {
            this.hint = hint
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
        val input = TextInputEditText(wrapper.context).apply { setText(value); textSize = 18f }
        wrapper.addView(input); container.addView(wrapper)
        return input
    }

    private fun rename(list: Checklist) {
        val content = column().apply { setPadding(dp(24), 0, dp(24), 0) }
        val title = field(content, "List name", list.title)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Rename list").setView(content)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                val value = title.text.toString().trim()
                if (value.isEmpty()) title.error = "Enter a name"
                else if (model.update((current() ?: list).copy(title = value))) dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun listOptions(list: Checklist) {
        MaterialAlertDialogBuilder(this).setTitle(list.title)
            .setItems(arrayOf("Rename list", "Uncheck all items", "Delete list", "Scan photo again", "Recognition settings")) { _, which ->
                when (which) {
                    0 -> rename(list)
                    1 -> MaterialAlertDialogBuilder(this).setTitle("Uncheck all items?")
                        .setMessage("Keep the same items and their order, ready to use again.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Uncheck all") { _, _ ->
                            current()?.let { model.update(it.copy(items = it.items.map { item -> item.copy(checked = false) })) }
                        }.show()
                    2 -> MaterialAlertDialogBuilder(this).setTitle("Delete this list?")
                        .setMessage("This removes the list and its saved photo from this device.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> model.delete(list) }.show()
                    3 -> {
                        if (list.photo == null) model.message.value = "This list has no saved photo. Scan a new photo from All lists."
                        else if (model.busy.value == true || model.authBusy.value == true) model.message.value = "Wait for the current operation to finish."
                        else if (model.chatState.value?.useCloud == true && model.chatState.value?.ready != true) recognitionSettings()
                        else MaterialAlertDialogBuilder(this).setTitle("Scan photo again?")
                            .setMessage("Create a new list from this photo using ${if (model.chatState.value?.useCloud == true) "ChatGPT (photo sent to OpenAI)" else "on-device recognition"}. Your current list and corrections stay saved.")
                            .setNegativeButton("Cancel", null).setPositiveButton("Scan") { _, _ -> model.rescan(list) }.show()
                    }
                    4 -> if (model.busy.value != true && model.authBusy.value != true) recognitionSettings()
                }
            }.show()
    }

    private fun editItem(itemId: String?) {
        val list = current() ?: return
        val item = list.items.firstOrNull { it.id == itemId }
        val content = column().apply { setPadding(dp(24), dp(4), dp(24), dp(16)) }
        val dialogScroll = ScrollView(this).apply { addView(content) }
        if (item?.bounds != null && list.photo != null) {
            val crop = ImageView(this).apply {
                adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Original handwriting for ${item.text}. Tap to view the whole photo."
                setBackgroundColor(Color.WHITE)
                setOnClickListener { showPhoto(list, item.bounds) }
            }
            content.addView(crop, LinearLayout.LayoutParams(-1, dp(130)))
            content.addView(label("PHOTO REFERENCE · tap to expand", 11f, muted))
            loadPhoto(list) { bitmap ->
                val b = item.bounds
                val left = ((b.left - .025f) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                val top = ((b.top - .015f) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                val right = ((b.right + .025f) * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
                val bottom = ((b.bottom + .015f) * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
                crop.setImageBitmap(Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top))
            }
        } else if (list.photo != null) {
            content.addView(button("View original photo", true) { showPhoto(list) })
            content.addView(label("No reliable per-item crop. Check the full photo for this entry.", 12f, muted))
        }
        if (item?.needsReview == true) content.addView(label("ChatGPT was unsure of this handwriting. Check the photo; saving marks it reviewed.", 14f, muted))
        val name = field(content, "Item", item?.text.orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val quantity = field(content, "Quantity (optional)", item?.quantity.orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        if (!item?.originalText.isNullOrEmpty()) {
            content.addView(label("Recognized: ${item!!.originalText}", 12f, muted).apply { setPadding(0, dp(12), 0, 0) })
        }
        val builder = MaterialAlertDialogBuilder(this).setTitle(if (item == null) "Add item" else "Edit item")
            .setView(dialogScroll).setNegativeButton("Cancel", null).setPositiveButton("Save", null)
        if (item != null) builder.setNeutralButton("Delete", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                val text = name.text.toString().trim()
                if (text.isBlank()) { name.error = "Enter an item"; return@setOnClickListener }
                val count = quantity.text.toString().trim().replace(',', '.')
                if (count.isNotEmpty() && (count.toDoubleOrNull()?.let { it > 0 && it.isFinite() } != true)) {
                    quantity.error = "Enter a quantity greater than zero"; return@setOnClickListener
                }
                val parsed = ItemParser.parse(text)
                if (parsed == null) { name.error = "Enter an item after the list marker"; return@setOnClickListener }
                val updated = item?.copy(text = text, quantity = count, needsReview = false)
                    ?: parsed.copy(quantity = count.ifBlank { parsed.quantity })
                val fresh = current() ?: return@setOnClickListener
                val items = if (item == null) fresh.items + updated else fresh.items.map { if (it.id == item.id) updated else it }
                if (model.update(fresh.copy(items = items))) dialog.dismiss()
            }
            if (item != null) dialog.getButton(-3).setOnClickListener {
                MaterialAlertDialogBuilder(this).setTitle("Delete “${item.text}”?")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                        current()?.let { fresh ->
                            if (model.update(fresh.copy(items = fresh.items.filterNot { it.id == item.id }))) dialog.dismiss()
                        }
                    }.show()
            }
        }
        dialog.show()
    }

    private fun loadPhoto(list: Checklist, ready: (Bitmap) -> Unit) {
        val name = list.photo ?: return
        photoBitmap?.takeIf { photoName == name }?.let { ready(it); return }
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { PhotoLoader.decode(File(filesDir, name)) }
                photoName = name; photoBitmap = bitmap; ready(bitmap)
            } catch (_: Exception) { Snackbar.make(root, "The saved photo could not be opened.", Snackbar.LENGTH_LONG).show() }
        }
    }

    private fun showPhoto(list: Checklist, bounds: PhotoBounds? = null) {
        val container = column().apply { setPadding(dp(12), 0, dp(12), 0) }
        val photo = PhotoView(this)
        container.addView(photo, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * .58f).toInt()))
        container.addView(label("Pinch to zoom · drag to move · double-tap to reset", 12f, muted).apply { gravity = Gravity.CENTER })
        container.addView(label(DateFormat.getDateInstance().format(Date(list.createdAt)), 12f, muted).apply { gravity = Gravity.CENTER })
        MaterialAlertDialogBuilder(this).setTitle("Original photo").setView(container).setPositiveButton("Done", null).show()
        loadPhoto(list) { photo.setPhoto(it, bounds) }
    }

    private fun recognitionSettings() {
        val state = model.chatState.value ?: return
        val content = column().apply { setPadding(dp(24), dp(8), dp(24), dp(16)) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Recognition")
            .setView(ScrollView(this).apply { addView(content) }).setPositiveButton("Done", null).create()
        content.addView(label("On-device", 20f).apply { setTypeface(typeface, Typeface.BOLD) })
        content.addView(label("Works offline. Best for printed text; handwriting recognition is limited.", 14f, muted))
        content.addView(button(if (!state.useCloud) "On-device selected" else "Use on-device recognition", true) {
            dialog.dismiss(); model.useOffline()
        }.apply { isEnabled = state.useCloud })
        content.addView(space(16))
        content.addView(label("ChatGPT", 20f).apply { setTypeface(typeface, Typeface.BOLD) })
        content.addView(label("Send a copy of your photo to OpenAI for recognition. Uses an eligible ChatGPT plan’s allowance; no API key or automatic API-billing fallback. Handwritten results still need review.", 14f, muted))
        content.addView(space(8))
        content.addView(label("In ChatGPT’s usage settings, disable additional credits for OCR List if you only want included plan usage.", 13f, muted))
        content.addView(button("ChatGPT usage & app access", true) { openWeb("https://chatgpt.com/settings/usage") })
        if (state.storageError) {
            content.addView(label("Saved connections could not be decrypted. Reset them and sign in again. Lists and photos are kept.", 14f, muted))
            content.addView(button("Reset saved connections", true) { dialog.dismiss(); model.resetConnections() })
        } else {
            state.active?.let { account ->
                content.addView(label("Account: ${account.label}", 14f, muted))
                if (account.connected) {
                    if (!state.useCloud) content.addView(button("Use ChatGPT") { dialog.dismiss(); model.chooseAccount(account.id) })
                    content.addView(label("Model: ${state.models.firstOrNull { it.id == state.model }?.name ?: "Choose a model"}", 14f, muted))
                    content.addView(button("Choose model", true) {
                        dialog.dismiss()
                        if (state.models.isEmpty()) model.refreshModels()
                        else MaterialAlertDialogBuilder(this).setTitle("ChatGPT model")
                            .setSingleChoiceItems(state.models.map { it.name }.toTypedArray(), state.models.indexOfFirst { it.id == state.model }) { picker, which ->
                                picker.dismiss(); model.chooseModel(state.models[which].id)
                            }.setNegativeButton("Cancel", null).show()
                    })
                    content.addView(button("Refresh available models", true) { dialog.dismiss(); model.refreshModels() })
                }
                content.addView(button("Sign in again", true) { dialog.dismiss(); model.connectChatGpt(account.id) })
                if (account.connected) content.addView(button("Sign out", true) { dialog.dismiss(); model.signOut() })
            }
            if (state.accounts.size > 1) content.addView(button("Switch account", true) {
                dialog.dismiss()
                MaterialAlertDialogBuilder(this).setTitle("ChatGPT account")
                    .setItems(state.accounts.map { it.label + if (!it.connected) " (signed out)" else "" }.toTypedArray()) { _, which ->
                        val account = state.accounts[which]
                        if (account.connected) model.chooseAccount(account.id) else model.connectChatGpt(account.id)
                    }.setNegativeButton("Cancel", null).show()
            })
            content.addView(button(if (state.accounts.isEmpty()) "Continue with ChatGPT" else "Connect another ChatGPT account", true) {
                dialog.dismiss(); model.connectChatGpt()
            })
        }
        dialog.show()
    }

    private fun openWeb(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { model.message.value = "No web browser is available on this device." }
    }
}

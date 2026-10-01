package app.ocrlist

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ocrlist.chatgpt.CredentialStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {
    @Test fun encryptsCredentialsAndDetectsTampering() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test-credentials.enc"
        val file = File(context.noBackupFilesDir, name)
        val store = CredentialStore(context, name, "ocr-list-test-only")
        try {
            val value = JSONObject().put("accessToken", "secret-test-access-token").put("refreshToken", "secret-test-refresh-token")
            store.save(value)
            assertEquals(value.toString(), store.load().toString())
            val encrypted = file.readBytes()
            assertFalse(String(encrypted).contains("secret-test"))
            encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
            file.writeBytes(encrypted)
            assertThrows(Exception::class.java) { store.load() }
            assertTrue(file.exists())
        } finally { store.reset() }
    }
}

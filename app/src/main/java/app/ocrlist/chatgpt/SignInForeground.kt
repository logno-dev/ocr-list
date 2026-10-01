package app.ocrlist.chatgpt

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/** The loopback callback can arrive while the browser still owns the foreground. */
class SignInForeground(private val settleMillis: Long = 500, private val timeoutMillis: Long = 120_000) {
    private data class State(val resumed: Boolean = false, val generation: Long = 0)
    private val state = MutableStateFlow(State())
    val isResumed: Boolean get() = state.value.resumed

    fun setResumed(resumed: Boolean) {
        state.update { State(resumed, it.generation + 1) }
    }

    suspend fun awaitResumed() {
        val ready = withTimeoutOrNull(timeoutMillis) {
            var stable = false
            while (!stable) {
                val observed = state.first { it.resumed }
                // Allow Android's per-UID network policy to catch up with Activity.onResume.
                delay(settleMillis)
                stable = state.value == observed
            }
            true
        }
        if (ready != true) throw ChatGptException("Sign-in waited too long for OCR List to reopen. Start sign-in again, then tap Open OCR List on the browser’s return page.")
    }
}

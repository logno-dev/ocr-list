package app.ocrlist

import app.ocrlist.chatgpt.ChatGptException
import app.ocrlist.chatgpt.SignInForeground
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignInForegroundTest {
    @Test fun callbackWhileBrowserIsForegroundDoesNotExchangeCode() = runTest {
        val foreground = SignInForeground()
        var exchanges = 0
        val pending = async { foreground.awaitResumed(); exchanges++ }
        advanceTimeBy(30_000)
        assertEquals(0, exchanges)
        assertFalse(pending.isCompleted)
        foreground.setResumed(true)
        advanceTimeBy(499)
        assertEquals(0, exchanges)
        advanceTimeBy(1)
        runCurrent()
        pending.await()
        assertEquals(1, exchanges)
    }

    @Test fun pauseDuringHandoffRestartsForegroundWait() = runTest {
        val foreground = SignInForeground()
        foreground.setResumed(true)
        var exchanged = false
        val pending = async { foreground.awaitResumed(); exchanged = true }
        advanceTimeBy(400)
        foreground.setResumed(false)
        advanceTimeBy(200)
        assertFalse(exchanged)
        foreground.setResumed(true)
        advanceTimeBy(499)
        assertFalse(exchanged)
        advanceTimeBy(1)
        runCurrent()
        pending.await()
        assertTrue(exchanged)
    }

    @Test fun quickPauseAndResumeDoesNotReuseOldForegroundSignal() = runTest {
        val foreground = SignInForeground()
        foreground.setResumed(true)
        val pending = async { foreground.awaitResumed() }
        advanceTimeBy(400)
        foreground.setResumed(false)
        foreground.setResumed(true)
        advanceTimeBy(100)
        runCurrent()
        assertFalse(pending.isCompleted)
        advanceTimeBy(500)
        runCurrent()
        pending.await()
    }

    @Test fun abandoningBrowserLoginExpiresInsteadOfSendingInBackground() = runTest {
        val foreground = SignInForeground(timeoutMillis = 5000)
        var exchanged = false
        val pending = async { runCatching { foreground.awaitResumed(); exchanged = true } }
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(pending.await().exceptionOrNull() is ChatGptException)
        assertFalse(exchanged)
    }

    @Test fun cancellationNeverConsumesThePendingCode() = runTest {
        val foreground = SignInForeground()
        var exchanged = false
        val pending = async { foreground.awaitResumed(); exchanged = true }
        runCurrent()
        pending.cancelAndJoin()
        foreground.setResumed(true)
        advanceTimeBy(1000)
        assertFalse(exchanged)
    }
}

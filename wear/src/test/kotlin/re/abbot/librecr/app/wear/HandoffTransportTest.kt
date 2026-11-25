package re.abbot.librecr.app.wear

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HandoffTransportTest {
    @Test fun completedPresenceQueryKeepsItsValue() = runBlocking {
        assertEquals(true, handoffQueryOrNull("test", timeoutMs = 100) { true })
        assertEquals(false, handoffQueryOrNull("test", timeoutMs = 100) { false })
    }

    @Test fun stalledPresenceQueryReleasesCallerAtItsDeadline() = runBlocking {
        val neverCompleted = CompletableDeferred<Boolean>()
        assertNull(handoffQueryOrNull("test", timeoutMs = 20) { neverCompleted.await() })
    }

    @Test fun unavailablePlayServicesQueryReturnsUnknown() = runBlocking {
        assertNull(handoffQueryOrNull<Boolean>("test", timeoutMs = 100) { throw IllegalStateException("unavailable") })
    }

    @Test fun parentCancellationIsNotConvertedIntoAnAbsentPhone() {
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                withTimeout(20) {
                    handoffQueryOrNull("test", timeoutMs = 1_000) { CompletableDeferred<Boolean>().await() }
                }
            }
        }
    }
}

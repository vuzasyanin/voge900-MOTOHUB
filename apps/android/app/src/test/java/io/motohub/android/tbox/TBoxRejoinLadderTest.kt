package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Test

class TBoxRejoinLadderTest {
    private fun step(attempt: Int, elapsedMillis: Long): TBoxRejoinStep = nextTBoxRejoinStep(
        attempt = attempt,
        elapsedMillis = elapsedMillis,
        budgetMillis = BUDGET,
        firstDelayMillis = FIRST,
        baseDelayMillis = BASE,
        maxDelayMillis = MAX
    )

    @Test
    fun `the first attempt retries almost immediately`() {
        assertEquals(TBoxRejoinStep.WaitThenRetry(FIRST), step(attempt = 1, elapsedMillis = 0L))
    }

    @Test
    fun `later attempts back off and then hold at the cap`() {
        assertEquals(TBoxRejoinStep.WaitThenRetry(2_500L), step(attempt = 2, elapsedMillis = 0L))
        assertEquals(TBoxRejoinStep.WaitThenRetry(5_000L), step(attempt = 3, elapsedMillis = 0L))
        assertEquals(TBoxRejoinStep.WaitThenRetry(MAX), step(attempt = 7, elapsedMillis = 0L))
        assertEquals(TBoxRejoinStep.WaitThenRetry(MAX), step(attempt = 40, elapsedMillis = 0L))
    }

    @Test
    fun `the ladder surrenders once the budget is spent`() {
        assertEquals(TBoxRejoinStep.WaitThenRetry(MAX), step(attempt = 9, elapsedMillis = BUDGET - 1))
        assertEquals(TBoxRejoinStep.GiveUp, step(attempt = 9, elapsedMillis = BUDGET))
        assertEquals(TBoxRejoinStep.GiveUp, step(attempt = 9, elapsedMillis = BUDGET * 4))
    }

    /**
     * A rider's diagnostics recorded 39 attempts over nineteen minutes against a bike that was
     * simply switched off, still holding an exclusive Wi-Fi request and fighting the rider's own
     * reconnect. Whatever the attempt count, elapsed time alone has to end the ladder.
     */
    @Test
    fun `no attempt count can outlast the budget`() {
        (1..500).forEach { attempt ->
            assertEquals(TBoxRejoinStep.GiveUp, step(attempt = attempt, elapsedMillis = BUDGET))
        }
    }

    @Test
    fun `a background process waits instead of spending an attempt`() {
        assertEquals(
            TBoxRejoinStep.WaitForForeground(2_000L),
            nextTBoxRejoinStep(
                attempt = 1,
                elapsedMillis = 0L,
                budgetMillis = BUDGET,
                firstDelayMillis = FIRST,
                baseDelayMillis = BASE,
                maxDelayMillis = MAX,
                submissionWouldBeRefused = true,
                backgroundPollMillis = 2_000L
            )
        )
    }

    @Test
    fun `after the backoff is served the next step is a submission`() {
        assertEquals(
            TBoxRejoinStep.SubmitNow,
            nextTBoxRejoinStep(
                attempt = 2,
                elapsedMillis = 0L,
                budgetMillis = BUDGET,
                firstDelayMillis = FIRST,
                baseDelayMillis = BASE,
                maxDelayMillis = MAX,
                backoffElapsed = true
            )
        )
    }

    @Test
    fun `a background process still surrenders once the budget is spent`() {
        assertEquals(
            TBoxRejoinStep.GiveUp,
            nextTBoxRejoinStep(
                attempt = 1,
                elapsedMillis = BUDGET,
                budgetMillis = BUDGET,
                firstDelayMillis = FIRST,
                baseDelayMillis = BASE,
                maxDelayMillis = MAX,
                submissionWouldBeRefused = true,
                backgroundPollMillis = 2_000L
            )
        )
    }

    private companion object {
        const val BUDGET = 180_000L
        const val FIRST = 300L
        const val BASE = 2_500L
        const val MAX = 15_000L
    }
}

package io.motohub.android.tbox

import io.motohub.android.encoding.EncoderProfile
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxVideoNegotiationTest {
    @Test
    fun `captures an area emitted synchronously while transport starts`() = runBlocking {
        val transport = FakeTransport(TBoxEvent.VideoArea(720, 712))

        val result = transport.negotiateVideoConfiguration(HOST, null, 100)

        assertEquals(
            TBoxVideoConfiguration(
                rawArea = TBoxEvent.VideoArea(720, 712),
                encoderProfile = EncoderProfile(720, 704),
                source = TBoxVideoAreaSource.LIVE
            ),
            result.getOrThrow()
        )
    }

    @Test
    fun `uses saved geometry after a short post-handshake wait`() = runBlocking {
        val transport = FakeTransport(null)
        val elapsedMillis = measureTimeMillis {
            val result = transport.negotiateVideoConfiguration(
                HOST,
                savedArea = TBoxEvent.VideoArea(1024, 601),
                timeoutMillis = 10_000L
            )

            assertEquals(TBoxVideoAreaSource.SAVED, result.getOrThrow().source)
            assertEquals(EncoderProfile(1024, 592), result.getOrThrow().encoderProfile)
        }
        assertTrue(
            "saved path waited ${elapsedMillis}ms instead of the short post-handshake window",
            elapsedMillis < POST_HANDSHAKE_SAVED_AREA_WAIT_MS + 750L
        )
    }

    @Test
    fun `live area that arrives in the short window still beats saved geometry`() = runBlocking {
        val transport = FakeTransport(
            areaOnStart = null,
            delayedArea = TBoxEvent.VideoArea(800, 480),
            areaAfterStartMillis = 20,
            emitScope = this
        )

        val result = transport.negotiateVideoConfiguration(
            HOST,
            savedArea = TBoxEvent.VideoArea(1024, 601),
            timeoutMillis = 200
        )

        assertEquals(TBoxVideoAreaSource.LIVE, result.getOrThrow().source)
        assertEquals(TBoxEvent.VideoArea(800, 480), result.getOrThrow().rawArea)
    }

    @Test
    fun `first connect without saved geometry waits the full timeout`() = runBlocking {
        val transport = FakeTransport(null)
        val timeoutMillis = 80L
        val elapsedMillis = measureTimeMillis {
            val result = transport.negotiateVideoConfiguration(
                HOST,
                savedArea = null,
                timeoutMillis = timeoutMillis,
                fallbackArea = TBoxEvent.VideoArea(800, 480)
            )

            assertEquals(TBoxVideoAreaSource.FALLBACK, result.getOrThrow().source)
        }
        assertTrue("first-connect path returned too early (${elapsedMillis}ms)", elapsedMillis >= timeoutMillis - 20)
        assertTrue("first-connect path waited ${elapsedMillis}ms", elapsedMillis < 500)
    }

    @Test
    fun `uses the validated fallback when live and saved geometry are unavailable`() = runBlocking {
        val transport = FakeTransport(null)

        val result = transport.negotiateVideoConfiguration(
            HOST,
            savedArea = null,
            timeoutMillis = 10,
            fallbackArea = TBoxEvent.VideoArea(800, 480)
        )

        assertEquals(TBoxVideoAreaSource.FALLBACK, result.getOrThrow().source)
        assertEquals(TBoxEvent.VideoArea(800, 480, isFallback = true), result.getOrThrow().rawArea)
        assertEquals(EncoderProfile(800, 480), result.getOrThrow().encoderProfile)
    }

    @Test
    fun `fails instead of inventing dimensions when no geometry exists`() = runBlocking {
        val transport = FakeTransport(null)

        val result = transport.negotiateVideoConfiguration(HOST, null, 10)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("no saved or fallback geometry"))
    }

    private class FakeTransport(
        private val areaOnStart: TBoxEvent.VideoArea?,
        private val delayedArea: TBoxEvent.VideoArea? = null,
        private val areaAfterStartMillis: Long = 0,
        private val emitScope: CoroutineScope? = null
    ) : TBoxTransport {
        private val mutableEvents = MutableSharedFlow<TBoxEvent>(extraBufferCapacity = 1)
        override val events: Flow<TBoxEvent> = mutableEvents.asSharedFlow()

        override suspend fun discover(link: TBoxLink, expectedModelId: String?): Result<TBoxHost> =
            Result.success(HOST)

        override suspend fun start(host: TBoxHost): Result<Unit> {
            areaOnStart?.let { mutableEvents.emit(it) }
            val delayed = delayedArea
            val scope = emitScope
            if (delayed != null && scope != null) {
                scope.launch {
                    delay(areaAfterStartMillis)
                    mutableEvents.tryEmit(delayed)
                }
            }
            return Result.success(Unit)
        }

        override fun offerAccessUnit(avcc: ByteArray): Boolean = true

        override suspend fun stop() = Unit
    }

    private companion object {
        val HOST = TBoxHost("192.168.43.1", 10930, "ECARX")
    }
}

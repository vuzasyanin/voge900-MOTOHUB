package io.motohub.android.tbox

import io.motohub.android.encoding.EncoderProfile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

enum class TBoxVideoAreaSource {
    LIVE,
    SAVED,
    FALLBACK
}

data class TBoxVideoConfiguration(
    val rawArea: TBoxEvent.VideoArea,
    val encoderProfile: EncoderProfile,
    val source: TBoxVideoAreaSource
)

/**
 * After EasyConn is up, how long to keep waiting for a live TFT area when this phone already
 * knows one. Field log 2026-09-16: handshake finished at 14:55:15, CAPTURE_CONFIG never came,
 * and the rider sat through the remaining 9s of the 10s budget for a SAVED 800x480 that was
 * already on disk. Live still wins if it arrives in this window.
 */
internal const val POST_HANDSHAKE_SAVED_AREA_WAIT_MS = 1_000L

/** Starts EasyConn while already listening for the runtime TFT capture dimensions. */
suspend fun TBoxTransport.negotiateVideoConfiguration(
    host: TBoxHost,
    savedArea: TBoxEvent.VideoArea?,
    timeoutMillis: Long,
    fallbackArea: TBoxEvent.VideoArea? = null
): Result<TBoxVideoConfiguration> = coroutineScope {
    val liveArea = async(start = CoroutineStart.UNDISPATCHED) {
        events.filterIsInstance<TBoxEvent.VideoArea>().first()
    }
    val startResult = start(host)
    startResult.exceptionOrNull()?.let { failure ->
        liveArea.cancel()
        return@coroutineScope Result.failure(failure)
    }

    val remainingWaitMillis = if (savedArea != null) {
        min(POST_HANDSHAKE_SAVED_AREA_WAIT_MS, timeoutMillis)
    } else {
        timeoutMillis
    }
    val live = withTimeoutOrNull(remainingWaitMillis) { liveArea.await() }
    if (live == null) liveArea.cancel()
    selectVideoConfiguration(live, savedArea, fallbackArea)
}

internal fun selectVideoConfiguration(
    liveArea: TBoxEvent.VideoArea?,
    savedArea: TBoxEvent.VideoArea?,
    fallbackArea: TBoxEvent.VideoArea? = null
): Result<TBoxVideoConfiguration> {
    val selected = when {
        liveArea != null -> liveArea to if (liveArea.isFallback) {
            TBoxVideoAreaSource.FALLBACK
        } else {
            TBoxVideoAreaSource.LIVE
        }
        savedArea != null -> savedArea to TBoxVideoAreaSource.SAVED
        fallbackArea != null -> fallbackArea.copy(isFallback = true) to TBoxVideoAreaSource.FALLBACK
        else -> null
    } ?: return Result.failure(
        IllegalStateException(
            "The T-Box did not provide a valid video area and no saved or fallback geometry is available."
        )
    )
    val area = selected.first
    return runCatching {
        TBoxVideoConfiguration(
            rawArea = area,
            encoderProfile = EncoderProfile.forTBoxArea(area.width, area.height),
            source = selected.second
        )
    }
}

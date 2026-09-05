package io.motohub.android.tbox

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class TBoxHost(
    val ipAddress: String,
    val port: Int,
    val packageName: String
)

sealed interface TBoxEvent {
    data class Capabilities(val value: TBoxCapabilities) : TBoxEvent
    data class VideoArea(
        val width: Int,
        val height: Int,
        /** True when the area came from a compatibility fallback rather than the T-Box. */
        val isFallback: Boolean = false
    ) : TBoxEvent
    data class Touch(val action: Int, val pointerId: Int, val x: Int, val y: Int) : TBoxEvent
    data object VideoStreamStart : TBoxEvent
    data class Warning(val message: String) : TBoxEvent
    data class FatalError(val message: String) : TBoxEvent
    data object Stopped : TBoxEvent
}

/**
 * Which wire protocol a [TBoxModelProfile] speaks, and therefore which [TBoxTransport]
 * implementation a session must be routed through (see SelectingTBoxTransport).
 */
enum class TBoxTransportFamily {
    /** Carbit/EasyConn dashes: the phone is the TCP client of the dash's services. */
    EASYCONN,

    /** ThinkerRide dashes (KOVE family): BLE handshake, then the dash connects to the phone. */
    THINKERRIDE
}

sealed interface TBoxTransportStatus {
    data object Unavailable : TBoxTransportStatus
    data object Ready : TBoxTransportStatus
    data class Failure(val reason: String) : TBoxTransportStatus
}

interface TBoxTransport {
    /** Selects the profile whose wire-level capabilities will be advertised for the next session. */
    fun configureProtocolProfile(profile: TBoxModelProfile) = Unit
    suspend fun discover(link: TBoxLink, expectedModelId: String? = null): Result<TBoxHost>
    /**
     * Recovery path after the dash left the projection page (Back → stock cluster).
     * One short NSD window plus the usual wake-probe fallback; the session service
     * retries this until its dash-return budget expires.
     */
    suspend fun discoverForResume(link: TBoxLink, expectedModelId: String? = null): Result<TBoxHost> =
        discover(link, expectedModelId)
    suspend fun start(host: TBoxHost): Result<Unit>
    fun offerAccessUnit(avcc: ByteArray): Boolean
    suspend fun stop()
    val events: Flow<TBoxEvent>
}

/** Keeps UI and session code honest until the GPL transport AAR is packaged. */
class UnavailableTBoxTransport : TBoxTransport {
    override suspend fun discover(link: TBoxLink, expectedModelId: String?): Result<TBoxHost> = Result.failure(
        IllegalStateException("hudlib.aar is not integrated")
    )

    override suspend fun start(host: TBoxHost): Result<Unit> = Result.failure(
        IllegalStateException("hudlib.aar is not integrated")
    )

    override fun offerAccessUnit(avcc: ByteArray): Boolean = false

    override suspend fun stop() = Unit

    override val events: Flow<TBoxEvent> = emptyFlow()
}

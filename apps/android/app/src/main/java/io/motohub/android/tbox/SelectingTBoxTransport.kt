package io.motohub.android.tbox

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/**
 * The one [TBoxTransport] the app holds. Routes every call to the wire implementation the
 * configured [TBoxModelProfile] belongs to — EasyConn ([RideDaemonTransport]) or ThinkerRide
 * ([ThinkerRideTransport]) — so HubViewModel, CoreTBoxConnector and the session services keep a
 * single transport field and a single event stream regardless of which protocol the motorcycle
 * speaks. [configureProtocolProfile] runs before [discover] on every connect path, which is what
 * makes the profile a safe routing key.
 */
class SelectingTBoxTransport(context: Context) : TBoxTransport {

    private val easyConn = RideDaemonTransport(context)
    private val thinkerRide = ThinkerRideTransport(context)

    @Volatile
    private var active: TBoxTransport = easyConn

    override val events: Flow<TBoxEvent> = merge(easyConn.events, thinkerRide.events)

    override fun configureProtocolProfile(profile: TBoxModelProfile) {
        active = when (profile.transportFamily) {
            TBoxTransportFamily.EASYCONN -> easyConn
            TBoxTransportFamily.THINKERRIDE -> thinkerRide
        }
        active.configureProtocolProfile(profile)
    }

    override suspend fun discover(link: TBoxLink, expectedModelId: String?): Result<TBoxHost> =
        active.discover(link, expectedModelId)

    override suspend fun discoverForResume(link: TBoxLink, expectedModelId: String?): Result<TBoxHost> =
        active.discoverForResume(link, expectedModelId)

    override suspend fun start(host: TBoxHost): Result<Unit> = active.start(host)

    override fun offerAccessUnit(avcc: ByteArray): Boolean = active.offerAccessUnit(avcc)

    override suspend fun stop() {
        // Stopping both is deliberate: a routing change between sessions must never leave the
        // previous family's sockets or BLE link alive, and stop() is a no-op on an idle transport.
        easyConn.stop()
        thinkerRide.stop()
    }
}

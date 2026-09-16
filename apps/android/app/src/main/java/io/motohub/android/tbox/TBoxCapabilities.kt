package io.motohub.android.tbox

import org.json.JSONObject

/** Safe, non-secret subset of CLIENT_INFO reported by the EasyConn T-Box. */
data class TBoxCapabilities(
    val huName: String? = null,
    val carBrand: String? = null,
    val carModel: String? = null,
    val packageName: String? = null,
    val pxcVersion: String? = null,
    val sdkVersion: String? = null,
    val versionName: String? = null,
    val versionCode: String? = null,
    val dpi: Int? = null,
    val dpiEnabled: Boolean? = null,
    val productType: Int? = null,
    val screenType: Int? = null,
    val transportType: Int? = null,
    val supportFunction: Int? = null,
    val socketTimeoutPeriodWifi: Int? = null,
    val socketServerAuth: Boolean? = null,
    val screenTouch: Boolean? = null,
    val screenMirroring: Boolean? = null,
    val mirrorReconnect: Boolean? = null,
    val landscapeAdaptive: Boolean? = null,
    val microphone: Boolean? = null,
    val hid: Boolean? = null,
    val mirrorOverlayTouch: Boolean? = null,
    val thirdPartyApps: Boolean? = null,
    val phoneSignal: Boolean? = null,
    val syncCorrectTime: Boolean? = null,
    val bluetoothCall: Boolean? = null,
    val bluetoothSettings: Boolean? = null,
    /**
     * The EasyConn SDK "flavor": which manufacturer licensed this dashboard. Numeric in
     * shipped firmware (65536 CFMOTO, 65540 CFMOTO international, 65561 ZONTES, 65569 Benda,
     * …), a plain string in the MOTO-HUB simulator, so it is kept as text. The SDK pairs it
     * with a phone package name it expects the companion app to use, which is why a rebadged
     * non-CFMOTO dash can complete the handshake and still refuse to project.
     *
     * Declared last, with [channel], so a positional [TBoxCapabilities] call site cannot
     * silently rebind one of the other nullable strings.
     */
    val flavor: String? = null,
    /** CLIENT_INFO echoes the pairing QR's modelId here; useful to confirm the two agree. */
    val channel: String? = null,
    /**
     * The clock the dashboard believes it has, as it reports it in CLIENT_INFO.
     *
     * A dash with a working real-time clock sends epoch milliseconds (~1.7e12 today). One whose
     * clock was never set - or was reset when EasyConn dropped - sends its own uptime instead,
     * which is orders of magnitude smaller. That difference is the whole diagnosis for riders
     * whose date and time keep going back to the factory value, and nothing in Kotlin read this
     * field until now, so the logs could not say which of the two a dash was doing.
     *
     * Long rather than Int on purpose: an epoch in milliseconds does not fit in 32 bits.
     */
    val currentHuTimeMillis: Long? = null
)

/**
 * Below this, a reported dash clock is its uptime rather than a wall clock. The same threshold
 * the daemon's Go side uses, so a log read here and a decision made there cannot disagree:
 * 1e11 ms is about 3.2 years of uptime, and as an epoch it is March 1973.
 */
internal const val HU_TIME_UPTIME_THRESHOLD_MS = 100_000_000_000L

/** Whether [currentHuTimeMillis] looks like an uptime counter instead of a wall clock. */
internal fun looksLikeDashUptime(currentHuTimeMillis: Long?): Boolean =
    currentHuTimeMillis != null && currentHuTimeMillis < HU_TIME_UPTIME_THRESHOLD_MS

/**
 * How far a dash clock may sit from the value it was given and still count as that value.
 *
 * Wide on purpose: the comparison only has to tell two readings apart that differ by a whole
 * timezone offset, and the smallest of those is 15 minutes.
 */
private const val HU_TIME_MATCH_TOLERANCE_MS = 5 * 60 * 1_000L

/**
 * Describes a dash wall clock against the value the daemon actually pushes it.
 *
 * EasyConn's `QUERY_TIME_ACK` carries both `time` (true UTC epoch) and `currentTime`
 * (`time` plus the zone offset, i.e. local time encoded as an epoch), and the daemon sets the
 * dash from the latter - see the `setTimeZoneOffsetSeconds` call in `RideDaemonTransport`.
 * A dash that took the correction therefore reports a `currentHUTime` an offset ahead of real
 * UTC, and comparing it against a raw `System.currentTimeMillis()` made every correctly
 * synced dash outside UTC look wrong: the Belgrade log of 2026-09-14 reported "7199s away"
 * for a clock that was within a second of what it had just been told.
 *
 * So the local-shifted value is the baseline. Plain UTC is still checked, because a dash
 * holding *that* is the one real failure this log can spot - it means the dash re-applies its
 * own zone on top of ours and the rider sees a clock an offset behind.
 *
 * @param zoneOffsetMillis the phone's UTC offset with DST applied, as sent to the daemon.
 */
internal fun describeDashWallClock(
    reportedMillis: Long,
    nowMillis: Long,
    zoneOffsetMillis: Long
): String {
    val localShiftedSkew = reportedMillis - (nowMillis + zoneOffsetMillis)
    val utcSkew = reportedMillis - nowMillis
    return when {
        kotlin.math.abs(localShiftedSkew) <= HU_TIME_MATCH_TOLERANCE_MS ->
            "matches the local-shifted clock the daemon pushes, ${localShiftedSkew / 1_000L}s off"
        zoneOffsetMillis != 0L && kotlin.math.abs(utcSkew) <= HU_TIME_MATCH_TOLERANCE_MS ->
            "is plain UTC, ${localShiftedSkew / 1_000L}s behind the local-shifted value the " +
                "daemon pushes, so the dash is applying its own zone on top of ours"
        else ->
            "is ${localShiftedSkew / 1_000L}s away from the local-shifted value the daemon pushes"
    }
}

internal fun decodeTBoxCapabilities(payload: ByteArray): TBoxCapabilities? = runCatching {
    val jsonText = payload.toString(Charsets.UTF_8).trim().trimEnd('\u0000')
    val json = JSONObject(jsonText)
    tBoxCapabilitiesFrom(
        CLIENT_INFO_KEYS.associateWith { key ->
            if (!json.has(key) || json.isNull(key)) null else json.get(key)
        }
    )
}.getOrNull()

internal fun tBoxCapabilitiesFrom(fields: Map<String, Any?>): TBoxCapabilities =
    TBoxCapabilities(
        huName = fields["HUName"].asString(),
        carBrand = fields["carBrand"].asString(),
        carModel = fields["carModel"].asString(),
        packageName = fields["package_name"].asString(),
        pxcVersion = fields["pxcVersion"].asString(),
        sdkVersion = fields["sdkVersion"].asString(),
        versionName = fields["version_name"].asString(),
        versionCode = fields["version_code"].asString(),
        dpi = fields["dpi"].asInt(),
        dpiEnabled = fields["enableDPI"].asBoolean(),
        productType = fields["productType"].asInt(),
        screenType = fields["screenType"].asInt(),
        transportType = fields["transportType"].asInt(),
        supportFunction = fields["supportFunction"].asInt(),
        socketTimeoutPeriodWifi = fields["socketTimeoutPeriodWifi"].asInt(),
        socketServerAuth = fields["enableSockServerAuth"].asBoolean(),
        screenTouch = fields["supportScreenTouch"].asBoolean(),
        screenMirroring = fields["supportScreenMirroring"].asBoolean(),
        mirrorReconnect = fields["supportMirrorReconnect"].asBoolean(),
        landscapeAdaptive = fields["supportLandscapeAdaptive"].asBoolean(),
        microphone = fields["supportMic"].asBoolean(),
        hid = fields["supportHID"].asBoolean(),
        mirrorOverlayTouch = fields["supportMirrorOverlayTouch"].asBoolean(),
        thirdPartyApps = fields["supportThirdPartyApp"].asBoolean(),
        phoneSignal = fields["supportPhoneSignal"].asBoolean(),
        syncCorrectTime = fields["supportSyncCorrectTime"].asBoolean(),
        bluetoothCall = fields["supportBTCall"].asBoolean(),
        bluetoothSettings = fields["supportBTSetting"].asBoolean(),
        flavor = fields["flavor"].asString(),
        channel = fields["channel"].asString(),
        currentHuTimeMillis = fields["currentHUTime"].asLong()
    )

private fun Any?.asString(): String? = when (this) {
    is String -> trim().takeIf(String::isNotEmpty)
    is Number -> toString()
    else -> null
}

private fun Any?.asInt(): Int? = when (this) {
    is Number -> toInt()
    is String -> toIntOrNull()
    else -> null
}

/** Kept apart from [asInt] because an epoch in milliseconds overflows a 32-bit read. */
private fun Any?.asLong(): Long? = when (this) {
    is Number -> toLong()
    is String -> toLongOrNull()
    else -> null
}

private fun Any?.asBoolean(): Boolean? = when (this) {
    is Boolean -> this
    is String -> toBooleanStrictOrNull()
    else -> null
}

private val CLIENT_INFO_KEYS = setOf(
    "HUName",
    "carBrand",
    "carModel",
    "flavor",
    "channel",
    "package_name",
    "pxcVersion",
    "sdkVersion",
    "version_name",
    "version_code",
    "dpi",
    "enableDPI",
    "productType",
    "screenType",
    "transportType",
    "supportFunction",
    "socketTimeoutPeriodWifi",
    "enableSockServerAuth",
    "supportScreenTouch",
    "supportScreenMirroring",
    "supportMirrorReconnect",
    "supportLandscapeAdaptive",
    "supportMic",
    "supportHID",
    "supportMirrorOverlayTouch",
    "supportThirdPartyApp",
    "supportPhoneSignal",
    "supportSyncCorrectTime",
    "supportBTCall",
    "supportBTSetting",
    "currentHUTime"
)

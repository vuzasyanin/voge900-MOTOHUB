package io.motohub.android.feature.pairing

import io.motohub.android.session.TBoxConnectionMode
import io.motohub.android.tbox.ThinkerRideProtocol
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * How far the decoded QR corroborates itself.
 *
 * The pairing QR is a Carbit artefact, and Carbit licenses the same dash stack to manufacturers
 * well beyond CFMOTO. A rebadged unit can serve the identical query string from its own OEM host,
 * so treating the host as an entry requirement would turn a cosmetic difference into a hard
 * rejection. The host is therefore corroboration, not a gate: an unfamiliar source still produces
 * a payload, marked so the caller can put the decision in front of the rider instead of guessing.
 */
enum class TBoxQrOrigin {
    /**
     * Shape and source both check out — a known provisioning host, or a dialect specific enough
     * to identify itself (see [TBoxQrParser.parse]). Not a vendor name: several manufacturers
     * reach this level, which is why it is not called `CARBIT`.
     */
    RECOGNISED,

    /** Usable credentials from a source MOTO-HUB cannot vouch for. Confirm before saving. */
    UNVERIFIED
}

data class TBoxQrPayload(
    val ssid: String,
    val password: String,
    val encryption: String?,
    // Opaque T-Box provisioning identifier. It is never interpreted as a motorcycle model.
    val modelId: String?,
    val displayName: String?,
    val origin: TBoxQrOrigin,
    /**
     * Set only by a dialect that identifies the *transport*, not just the network — the
     * ThinkerRide code means "pair over BLE, the dash connects to you", which no SSID shape or
     * modelId could re-derive later. Null leaves the saved profile's mode untouched.
     */
    val suggestedConnectionMode: TBoxConnectionMode? = null
)

object TBoxQrParser {
    private const val WIFI_SCHEME = "WIFI:"

    /**
     * Decodes any of the three pairing codes seen in the field. Failure is reserved for content
     * that carries no network name at all — anything with usable credentials comes back with an
     * [TBoxQrOrigin] describing how much it can be trusted.
     *
     * The MotoFun dialect is tried before the query-string one because it is recognised by shape
     * (`Wifi=<ssid>#<password>`) rather than by a parameter name, and returns null the moment that
     * shape is absent — so a Carbit code carrying an unrelated `wifi=` parameter still falls
     * through to [parseProvisioningUrl]. That one throws instead of returning null, so it is last.
     */
    fun parse(rawValue: String): Result<TBoxQrPayload> = runCatching {
        val trimmed = rawValue.trim()
        parseWifiNetworkCode(trimmed)
            ?: parseCarbitToken(trimmed)
            ?: parseMotoFunUrl(trimmed)
            ?: parseThinkerRideUrl(trimmed)
            ?: parseProvisioningUrl(trimmed)
    }

    /**
     * Opaque `CARBIT` + 12 hex digits printed as a second QR on some Zontes units — the dash
     * joins a hotspot the phone hosts, so there is no SoftAP SSID in the code.
     */
    private fun parseCarbitToken(rawValue: String): TBoxQrPayload? {
        val match = CARBIT_TOKEN.matchEntire(rawValue.trim()) ?: return null
        val mac = formatMac(match.groupValues[1]) ?: return null
        return TBoxQrPayload(
            ssid = "PHONE-HOTSPOT-${match.groupValues[1].takeLast(6)}",
            password = "",
            encryption = null,
            modelId = null,
            displayName = "Phone hotspot (${mac.takeLast(8)})",
            origin = TBoxQrOrigin.RECOGNISED,
            suggestedConnectionMode = TBoxConnectionMode.PHONE_HOTSPOT
        )
    }

    /**
     * The ThinkerRide (KOVE) pairing code:
     *
     *     http://g.thinkerride.com/?<SSID>&<PASSWORD>&ap=1
     *
     * The credentials are *positional* — two bare query components with no `key=` at all — which
     * no other dialect produces, so the shape is recognisable on its own. The host corroborates
     * it; a rebadged unit serving the same shape from an OEM host needs the `ap=` marker as a
     * second witness and still goes to the rider as [TBoxQrOrigin.UNVERIFIED]. Like MotoFun,
     * this one returns null the moment the shape is absent, so Carbit query strings
     * (`ssid=...&pwd=...`) fall through untouched to [parseProvisioningUrl].
     *
     * Beyond the network, this dialect decides the *transport*: a ThinkerRide dash pairs over
     * BLE and then connects to the phone, so the payload carries
     * [TBoxConnectionMode.THINKERRIDE] and the pseudo modelId that routes later sessions to the
     * ThinkerRide profile family.
     */
    private fun parseThinkerRideUrl(rawValue: String): TBoxQrPayload? {
        val uri = runCatching { URI(rawValue) }.getOrNull()
        val query = (uri?.rawQuery ?: rawValue.substringAfter('?', "").substringBefore('#'))
        val components = query.split('&').filter(String::isNotBlank)
        if (components.size < 2) return null
        val positional = components.take(2)
        if (positional.any { it.contains('=') }) return null

        val host = (uri?.host ?: hostOf(rawValue))?.lowercase()
        val thinkerRideHost = host != null && THINKER_RIDE_DOMAINS.any { host == it || host.endsWith(".$it") }
        val accessPointMarker = components.drop(2).any { it.equals("ap=1", ignoreCase = true) }
        if (!thinkerRideHost && !accessPointMarker) return null

        val ssid = decode(positional[0]).trim()
        if (ssid.isEmpty()) return null

        return TBoxQrPayload(
            ssid = ssid,
            password = decode(positional[1]),
            // Every ThinkerRide dash seen runs a WPA2 access point; a passphrase was just read
            // out of the code, so an open network is not a possibility.
            encryption = "wpa2-psk",
            modelId = ThinkerRideProtocol.PROVISIONING_MODEL_ID,
            displayName = null,
            origin = if (thinkerRideHost) TBoxQrOrigin.RECOGNISED else TBoxQrOrigin.UNVERIFIED,
            suggestedConnectionMode = TBoxConnectionMode.THINKERRIDE
        )
    }

    private fun parseProvisioningUrl(rawValue: String): TBoxQrPayload {
        // URI rejects the whole string over one unescaped character - a `%` in a passphrase is
        // enough - so a dash whose QR is slightly off spec would be unpairable. Fall back to
        // reading the query and host by hand; content that carries no SSID is still rejected
        // below, which is the only rejection this parser owes the caller.
        val uri = runCatching { URI(rawValue) }.getOrNull()
        val parameters = (uri?.rawQuery ?: rawValue.substringAfter('?', "").substringBefore('#'))
            .split('&')
            .filter(String::isNotBlank)
            .associate { item ->
                val keyAndValue = item.split('=', limit = 2)
                // Parameter names are folded: OEM firmware is not consistent about their case,
                // and an `SSID=` that reads as absent costs a pairing for a cosmetic difference.
                decode(keyAndValue[0]).lowercase() to decode(keyAndValue.getOrElse(1) { "" })
            }
        val action = parameters["action"]?.toIntOrNull() ?: 0
        val mac = formatMac(parameters["mac"]) ?: formatMac(parameters["bm"])
        val ssid = parameters["ssid"].orEmpty().trim()
        val password = parameters["pwd"].orEmpty()
        val host = (uri?.host ?: hostOf(rawValue))?.lowercase()
        val origin = if (host != null && isKnownProvisioningHost(host)) {
            TBoxQrOrigin.RECOGNISED
        } else {
            TBoxQrOrigin.UNVERIFIED
        }

        // Bit7 / empty SoftAP creds — the dash is a Wi-Fi client and joins a hotspot the phone
        // hosts. The QR often carries only action=128 + bm=<mac>, with no ssid/pwd at all.
        val phoneHotspot = (action and 128) != 0 ||
            (ssid.isEmpty() && mac != null && parameters.containsKey("bm"))
        if (phoneHotspot) {
            val syntheticSsid = ssid.ifEmpty {
                "PHONE-HOTSPOT-${mac?.replace(":", "").orEmpty().takeLast(6)}"
            }
            val displayName = parameters["name"]?.takeIf { it.isNotBlank() }
                ?: mac?.let { "Phone hotspot (${it.takeLast(8)})" }
            return TBoxQrPayload(
                ssid = syntheticSsid,
                password = password,
                encryption = parameters["auth"],
                modelId = parameters["modelid"],
                displayName = displayName,
                origin = origin,
                suggestedConnectionMode = TBoxConnectionMode.PHONE_HOTSPOT
            )
        }

        check(ssid.isNotEmpty()) { describeUnusableCode(rawValue) }

        return TBoxQrPayload(
            ssid = ssid,
            password = password,
            encryption = parameters["auth"],
            modelId = parameters["modelid"],
            displayName = parameters["name"],
            origin = origin,
            suggestedConnectionMode = connectionModeFromAction(action)
        )
    }

    /**
     * Maps the Carbit `action` bitmask to an explicit transport when one mode is advertised
     * without a fallback — bit3 (8) is Wi-Fi Direct-only, bit7 (128) is phone-hosted hotspot.
     */
    private fun connectionModeFromAction(action: Int): TBoxConnectionMode? = when {
        (action and 128) != 0 -> TBoxConnectionMode.PHONE_HOTSPOT
        (action and 8) != 0 && (action and 1) == 0 && (action and 2) == 0 ->
            TBoxConnectionMode.WIFI_DIRECT
        else -> null
    }

    /** `aabbccddeeff` / `aa:bb:…` → colon form; null if not 12 hex digits. */
    private fun formatMac(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val hex = raw.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        if (hex.length != 12) {
            return raw.takeIf { it.contains(':') && it.length >= 11 }
        }
        return hex.chunked(2).joinToString(":") { it.lowercase() }
    }

    /**
     * The Moto Morini / MotoFun dash code, confirmed on the X-Cape 649 / 700 and the Seiemmezzo:
     *
     *     http://admin.motomorini.com/app.html?Wifi=ML174167#12345678#dc0d30da1b6c
     *       &MachineID=dc0d30da1b6c&ProductID=00297
     *
     * `#` is a field separator here, not the start of a URI fragment, so neither [URI] nor the
     * hand-rolled query split can be used: both stop at the first `#` and hand back a query of
     * `Wifi=ML174167`, silently dropping the password. The raw string is scanned instead.
     *
     * `ProductID` takes the place of `modelid` — like it, an opaque provisioning identifier that is
     * never read as a motorcycle model. There is no `action` bitmask to honour: the dash is a plain
     * access point, and the transport is decided from the SSID shape further down.
     */
    private fun parseMotoFunUrl(rawValue: String): TBoxQrPayload? {
        val wifiField = MOTO_FUN_WIFI.find(rawValue) ?: return null
        val ssid = wifiField.groupValues[1].trim()
        if (ssid.isEmpty()) return null

        // The match stops at the `#` that ends the SSID, so the remainder starts on the separator.
        // Its absence means this is some other `wifi=` parameter, not a MotoFun pairing code.
        val remainder = rawValue.substring(wifiField.range.last + 1)
        if (!remainder.startsWith('#')) return null
        val password = remainder.drop(1).substringBefore('#').substringBefore('&').trim()
        if (password.isEmpty()) return null

        val machineId = MOTO_FUN_MACHINE_ID.find(rawValue)?.groupValues?.get(1)
        val productId = MOTO_FUN_PRODUCT_ID.find(rawValue)?.groupValues?.get(1)
        val host = (runCatching { URI(rawValue) }.getOrNull()?.host ?: hostOf(rawValue))?.lowercase()

        // This dialect identifies itself: no other code puts a password behind `Wifi=<ssid>#`, and
        // the MotoFun identifiers alongside it are a second witness. A rebadged unit serving the
        // same shape from an unfamiliar host with neither identifier still goes to the rider.
        val corroborated = (host != null && isKnownProvisioningHost(host)) ||
            machineId != null || productId != null

        return TBoxQrPayload(
            ssid = ssid,
            password = password,
            // Not carried by this dialect. Every dash seen with it runs a WPA2 access point, and a
            // passphrase was just read out of the code, so an open network is not a possibility.
            encryption = "wpa2-psk",
            modelId = productId,
            displayName = null,
            origin = if (corroborated) TBoxQrOrigin.RECOGNISED else TBoxQrOrigin.UNVERIFIED
        )
    }

    /**
     * The standard `WIFI:S:name;T:WPA;P:secret;;` code some dashes print instead of a provisioning
     * URL. It carries no model id, so the dash is identified from CLIENT_INFO on first contact —
     * the same route an unrecognised provisioning URL takes.
     */
    private fun parseWifiNetworkCode(rawValue: String): TBoxQrPayload? {
        if (!rawValue.startsWith(WIFI_SCHEME, ignoreCase = true)) return null
        val fields = splitWifiFields(rawValue.substring(WIFI_SCHEME.length))
        val ssid = fields["S"].orEmpty()
        check(ssid.isNotEmpty()) { "The Wi-Fi QR code does not carry a network name." }

        return TBoxQrPayload(
            ssid = ssid,
            password = fields["P"].orEmpty(),
            encryption = fields["T"],
            modelId = null,
            displayName = null,
            origin = TBoxQrOrigin.UNVERIFIED
        )
    }

    /**
     * Splits the `key:value;` pairs of a Wi-Fi network code. The format escapes its own delimiters
     * with a backslash, so the key/value split has to happen while scanning: an SSID containing an
     * escaped colon would otherwise be cut in half by a later search.
     */
    private fun splitWifiFields(body: String): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        val buffer = StringBuilder()
        var key: String? = null
        var escaped = false

        fun commit() {
            key?.takeIf(String::isNotEmpty)?.let { name ->
                fields.putIfAbsent(name.uppercase(), buffer.toString())
            }
            key = null
            buffer.setLength(0)
        }

        for (character in body) {
            when {
                escaped -> {
                    buffer.append(character)
                    escaped = false
                }
                character == '\\' -> escaped = true
                character == ':' && key == null -> {
                    key = buffer.toString()
                    buffer.setLength(0)
                }
                character == ';' -> commit()
                else -> buffer.append(character)
            }
        }
        commit()
        return fields
    }

    /**
     * Percent-decodes one query component, leaving `+` and a stray `%` exactly as they were.
     *
     * `URLDecoder` implements `application/x-www-form-urlencoded`, where `+` stands for a space.
     * A Carbit provisioning QR is a plain query string, not a submitted form: a passphrase
     * containing a literal `+` was saved with a space in its place, and every join then failed
     * association with nothing in the log to say why. An unescaped `%` made `URLDecoder` throw,
     * which rejected the whole QR - passing the byte through beats refusing to pair at all.
     * Percent-escapes are still decoded, so `%2B` remains a `+` and `%20` remains a space.
     */
    private fun decode(value: String): String {
        if (!value.contains('%')) return value
        val decoded = StringBuilder(value.length)
        val escaped = ByteArrayOutputStream()

        // Consecutive escapes are one UTF-8 sequence: they have to be decoded together, so the
        // bytes are only turned into text once a literal character (or the end) interrupts them.
        fun flushEscaped() {
            if (escaped.size() == 0) return
            decoded.append(String(escaped.toByteArray(), StandardCharsets.UTF_8))
            escaped.reset()
        }

        var index = 0
        while (index < value.length) {
            val byte = if (value[index] == '%') hexByteAt(value, index + 1) else null
            if (byte == null) {
                flushEscaped()
                decoded.append(value[index])
                index++
            } else {
                escaped.write(byte)
                index += 3
            }
        }
        flushEscaped()
        return decoded.toString()
    }

    /** The byte spelled by the two hex digits at [start], or null if they are not two hex digits. */
    private fun hexByteAt(value: String, start: Int): Int? {
        if (start + 1 >= value.length) return null
        val high = Character.digit(value[start], 16)
        val low = Character.digit(value[start + 1], 16)
        if (high < 0 || low < 0) return null
        return (high shl 4) or low
    }

    /** Authority host of a URL [URI] refused to parse, so an off-spec QR can still be vouched for. */
    private fun hostOf(rawValue: String): String? {
        val authority = rawValue.substringAfter("://", missingDelimiterValue = "")
            .takeWhile { it != '/' && it != '?' && it != '#' }
        return authority.substringAfterLast('@').substringBefore(':').takeIf(String::isNotEmpty)
    }

    /**
     * Provisioning domains MOTO-HUB has seen serve a real pairing code. Corroboration only — an
     * absent match costs a confirmation dialog, never the pairing (see [TBoxQrOrigin]), so this
     * list never needs to be complete.
     */
    private val KNOWN_PROVISIONING_DOMAINS = listOf(
        "carbit.com",
        "carbit.com.cn",
        // Moto Morini / MotoFun serves the dialect below from its own domain.
        "motomorini.com"
    )

    /** Hosts the ThinkerRide (KOVE) pairing code has been seen served from. */
    private val THINKER_RIDE_DOMAINS = listOf("thinkerride.com")

    private fun isKnownProvisioningHost(host: String): Boolean =
        KNOWN_PROVISIONING_DOMAINS.any { host == it || host.endsWith(".$it") }

    /** `Wifi=<ssid>`, where the SSID runs up to the `#` that introduces the password. */
    private val MOTO_FUN_WIFI = Regex("""(?:^|[?&])wifi=([^&#\s]+)""", RegexOption.IGNORE_CASE)
    private val MOTO_FUN_MACHINE_ID = Regex("""machineid=([^&#\s]+)""", RegexOption.IGNORE_CASE)
    private val MOTO_FUN_PRODUCT_ID = Regex("""productid=([^&#\s]+)""", RegexOption.IGNORE_CASE)
    private val CARBIT_TOKEN = Regex("""(?i)^CARBIT([0-9A-F]{12})$""")

    /**
     * The QR decoded cleanly but carries no credentials. Naming the actual content is what lets a
     * rider recover on their own: the dash prints several codes and only one of them pairs, so
     * "unreadable" sends them polishing the screen instead of changing screens.
     */
    private fun describeUnusableCode(rawValue: String): String {
        val vin = rawValue.contains("vin:", ignoreCase = true)
        return when {
            vin && (
                rawValue.contains("color:", ignoreCase = true) ||
                    rawValue.contains("engine:", ignoreCase = true) ||
                    rawValue.startsWith("code:", ignoreCase = true)
                ) ->
                "That is the vehicle information code (VIN, engine, colour), not the Wi-Fi " +
                    "pairing code. Open the phone-connection screen on the dash and scan the " +
                    "code shown there."

            rawValue.contains("motomorini", ignoreCase = true) ||
                rawValue.contains("motofun", ignoreCase = true) ->
                "This Moto Morini code carries no Wifi= field, so it is not the pairing code. " +
                    "Open the phone-link / MotoFun screen on the dash and scan the code there."

            // A provisioning-domain URL that carries no credentials is not a rider mistake: some
            // dashes pair the other way round. They join a hotspot the PHONE hosts, under an SSID
            // and password the dash itself prints, so their QR has nothing to hand over and the
            // generic "scan the pairing code instead" advice sends the rider hunting for a code
            // that does not exist. Confirmed on a tester's dash 2026-08-02, whose screen reads
            // "Please open Android hotspot and set the following parameters".
            hostOf(rawValue)?.lowercase()?.let(::isKnownProvisioningHost) == true &&
                (rawValue.contains("action=128", ignoreCase = true) ||
                    rawValue.contains("bm=", ignoreCase = true)) ->
                "This dash connects the other way round: it joins a hotspot your phone creates, " +
                    "so its code carries no network to join. On the dash, read the Ssid and " +
                    "Password it shows, set your Android hotspot to exactly those values, turn it " +
                    "on, and the dash will connect by itself."

            CARBIT_TOKEN.containsMatchIn(rawValue.trim()) ->
                "Carbit ID QR — enable Android hotspot with the SSID/password on the dash, then Connect."

            hostOf(rawValue)?.lowercase()?.let(::isKnownProvisioningHost) == true ->
                "This dash connects the other way round: it joins a hotspot your phone creates, " +
                    "so its code carries no network to join. On the dash, read the Ssid and " +
                    "Password it shows, set your Android hotspot to exactly those values, turn it " +
                    "on, and the dash will connect by itself."

            rawValue.startsWith("http", ignoreCase = true) ->
                "That is a web address with no network credentials in it. Scan the dash pairing " +
                    "code instead (MotoPlay / EasyConnect / MotoFun)."

            else -> "The QR code does not carry a T-Box network name."
        }
    }
}

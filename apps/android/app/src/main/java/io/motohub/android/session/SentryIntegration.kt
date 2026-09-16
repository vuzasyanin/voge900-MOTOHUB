package io.motohub.android.session

import android.content.Context
import android.util.Log
import io.motohub.android.BuildConfig
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import java.util.concurrent.atomic.AtomicInteger

/**
 * Centralized Sentry setup for CORE.
 *
 * The DSN is supplied by the build configuration, so public source builds can omit telemetry
 * while release/CI builds provide it through the ignored private properties file or environment.
 * Diagnostic errors are sent as already-redacted messages rather than raw Throwable objects: the
 * in-app log can contain connection details, and the application must never upload those values.
 */
object SentryIntegration {
    private const val LOG_TAG = "MotoHubSentry"
    private const val MAX_DIAGNOSTIC_EVENTS_PER_PROCESS = 50
    private val diagnosticEventsSent = AtomicInteger(0)
    @Volatile private var enabled = false

    fun initialize(context: Context) {
        val dsn = BuildConfig.SENTRY_DSN.trim()
        if (dsn.isEmpty()) return

        runCatching {
            SentryAndroid.init(context.applicationContext) { options ->
                options.dsn = dsn
                options.environment = "core"
                options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}"
                options.dist = "${BuildConfig.VERSION_CODE}-${BuildConfig.CHANNEL}"
                options.isSendDefaultPii = false
                options.isEnableAutoSessionTracking = true
                options.isDebug = BuildConfig.SENTRY_DEBUG
            }
            enabled = true
            Sentry.configureScope { scope ->
                scope.setTag("motohub.channel", BuildConfig.CHANNEL)
            }
        }.onFailure { failure ->
            Log.e(LOG_TAG, "Sentry initialization failed; continuing without telemetry", failure)
        }
    }

    /** Sends a bounded, redacted diagnostic event without changing the local log behavior. */
    fun captureDiagnosticError(source: String, message: String) {
        if (!enabled) return
        if (diagnosticEventsSent.incrementAndGet() > MAX_DIAGNOSTIC_EVENTS_PER_PROCESS) return

        runCatching {
            Sentry.withScope { scope ->
                scope.setTag("motohub.source", source)
                scope.setTag("motohub.edition", "core")
                scope.setTag("motohub.channel", BuildConfig.CHANNEL)
                scope.fingerprint = fingerprintOf(source, message)
                Sentry.captureMessage(message, SentryLevel.ERROR)
            }
        }.onFailure { failure ->
            Log.w(LOG_TAG, "Unable to send diagnostic event", failure)
        }
    }

    /**
     * Attaches low-cardinality facts to every event this process sends from here on.
     *
     * Tags rather than a context, because only tags can be grouped and counted across the fleet -
     * and that is the whole reason these exist. The most useful diagnostic the app writes, what
     * the Wi-Fi scan could see in the moment before a join, is a warning, so it never left the
     * phone: 109 riders hit "no network granted" in four days and not one of those reports could
     * say whether the dash was in the air at all.
     *
     * Values must stay coarse. A tag carrying a rider's exact RSSI is a new tag value per rider,
     * which costs Sentry's indexing and answers nothing.
     */
    fun setDiagnosticTags(tags: Map<String, String>) {
        if (!enabled) return
        runCatching {
            Sentry.configureScope { scope ->
                tags.forEach { (key, value) -> scope.setTag(key, value) }
            }
        }.onFailure { failure ->
            Log.w(LOG_TAG, "Unable to attach diagnostic tags", failure)
        }
    }

    /**
     * Groups a report by what it SAYS rather than by the numbers and the frame names in it.
     *
     * Two things were splitting one report into dozens of issues. Elapsed times land in the
     * message - one rider's "unavailable 727144ms" and another's "48772ms" are the same event -
     * and the appended stack trace is obfuscated per build, so `zZ.c` and `qZ.c` are the same
     * frame from two releases. Split that way neither the user counts nor [setDiagnosticTags]
     * mean anything. Only the first two lines are kept: the message the call site chose, and the
     * exception line under it. The SSID in that second line still separates one bike from
     * another, which is deliberate - it is the difference between one dash that is off and a
     * model that cannot be joined.
     */
    private fun fingerprintOf(source: String, message: String): List<String> = listOf(
        source,
        message.lineSequence().take(2).joinToString("\n").replace(NUMBERS, "#")
    )

    private val NUMBERS = Regex("\\d+")
}

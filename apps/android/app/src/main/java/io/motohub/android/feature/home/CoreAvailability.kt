package io.motohub.android.feature.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.motohub.android.BuildConfig
import io.motohub.android.i18n.motoHubText

private const val CORE_PACKAGE_NAME = "io.motohub.android"
private const val CORE_RELEASES_URL = "https://github.com/vuzasyanin/voge900-MOTOHUB/releases/latest"

/** Advanced-only: Core doesn't depend on itself, so this is always false in the Core flavor. */
fun isCoreMissing(context: Context): Boolean {
    if (!BuildConfig.IS_PRO) return false
    return runCatching {
        context.packageManager.getPackageInfo(CORE_PACKAGE_NAME, 0)
    }.isFailure
}

/**
 * Shown at the top of Home in Advanced when Core isn't installed, so the rider learns this
 * immediately instead of only on the first connection or projection attempt (those
 * still show their own error too, since a rider could uninstall Core mid-session). Rechecks on
 * every resume so it clears itself if Core gets installed and the rider switches back to
 * Advanced without a restart.
 */
@Composable
fun CoreMissingBanner() {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(isCoreMissing(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                missing = isCoreMissing(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!missing) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                motoHubText("MOTO-HUB CORE NOT FOUND"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                motoHubText(
                    "Advanced needs MOTO-HUB (Core) installed and paired with your T-Box to " +
                        "connect, mirror, or run Android Auto."
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(CORE_RELEASES_URL))
                    runCatching { context.startActivity(intent) }.onFailure {
                        Toast.makeText(context, motoHubText("Couldn't open the browser."), Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
            ) {
                Text(motoHubText("Download MOTO-HUB Core"))
            }
        }
    }
}

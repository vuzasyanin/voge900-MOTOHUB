package io.motohub.android

import android.app.Application
import io.motohub.android.androidauto.AndroidAutoNightModeController
import io.motohub.android.i18n.MotoHubStrings
import io.motohub.android.session.CrashRecovery
import io.motohub.android.session.ProcessExitReport
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.session.SentryIntegration

class MotoHubApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MotoHubStrings.initialize(this)
        AndroidAutoNightModeController.install(this)
        SentryIntegration.initialize(this)
        ProjectionEventLog.initialize(this)
        CrashRecovery.install(this)
        CrashRecovery.restorePreviousCrash(this)
        // After the crash report, and covering what it cannot: a process that was killed rather
        // than crashed leaves no exception behind, so Android's own record is the only account
        // of it. Both run at startup because the two answer different halves of "what happened
        // last time".
        ProcessExitReport.reportPreviousExits(this)
    }
}

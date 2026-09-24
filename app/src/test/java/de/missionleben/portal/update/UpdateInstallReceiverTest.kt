package de.missionleben.portal.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateInstallReceiverTest {
    private lateinit var context: Context
    private val receiver = UpdateInstallReceiver()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        UpdateInstaller.consumeFailure(context)
    }

    @Test
    fun installationFailureIsReportedOnce() {
        receiver.onReceive(context, installResult(PackageInstaller.STATUS_FAILURE))

        assertTrue(UpdateInstaller.consumeFailure(context))
        assertFalse(UpdateInstaller.consumeFailure(context))
    }

    @Test
    fun missingConfirmationIntentIsReportedAsFailure() {
        receiver.onReceive(context, installResult(PackageInstaller.STATUS_PENDING_USER_ACTION))

        assertTrue(UpdateInstaller.consumeFailure(context))
    }

    @Test
    fun unrelatedBroadcastIsIgnored() {
        receiver.onReceive(context, Intent("unrelated.action"))

        assertFalse(UpdateInstaller.consumeFailure(context))
    }

    private fun installResult(status: Int) = Intent(UpdateInstaller.ACTION_INSTALL_COMMIT).apply {
        putExtra(PackageInstaller.EXTRA_STATUS, status)
    }
}

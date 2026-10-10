package de.jeanlucmakiola.agendula.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import de.jeanlucmakiola.agendula.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The account type and authority exist in two places that cannot see each other:
 * `SyncContract`, derived from `BuildConfig.APPLICATION_ID`, and the `resValue`
 * strings the XML descriptors read. Drift between them is invisible at build
 * time and shows up as an account the sync framework will not trigger — the
 * silent no-op, with nothing in the log.
 */
@RunWith(AndroidJUnit4::class)
class SyncContractTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun theAccountTypeMatchesTheAuthenticatorDescriptor() {
        assertThat(SyncContract.ACCOUNT_TYPE)
            .isEqualTo(context.getString(R.string.account_type))
    }

    @Test
    fun theAuthorityMatchesTheSyncAdapterDescriptor() {
        assertThat(SyncContract.AUTHORITY)
            .isEqualTo(context.getString(R.string.sync_authority))
    }

    @Test
    fun theAuthorityMatchesTheStubProviderInTheManifest() {
        // The provider is what makes the authority real; a mismatch here means
        // requestSync addresses nothing.
        val provider = context.packageManager
            .resolveContentProvider(SyncContract.AUTHORITY, 0)
        assertThat(provider).isNotNull()
        assertThat(provider!!.name).isEqualTo(SyncStubProvider::class.java.name)
    }
}

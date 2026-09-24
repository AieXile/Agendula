package de.jeanlucmakiola.agendula.data.sync.push

import android.content.Context
import android.content.pm.PackageManager
import de.jeanlucmakiola.agendula.data.prefs.SettingsPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.ResolvedDistributor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which UnifiedPush distributor delivers our pushes, and whether push is on. The
 * connector keeps the choice; the on/off switch lives in [SettingsPrefs].
 */
@Singleton
class PushDistributors @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsPrefs,
) {

    data class Distributor(val packageName: String, val label: String)

    /** The distributor apps installed right now. */
    fun installed(): List<Distributor> =
        UnifiedPush.getDistributors(context)
            .filter { it != context.packageName }
            .map { Distributor(it, labelOf(it)) }
            .sortedBy { it.label.lowercase() }

    /**
     * The distributor to register with, or null when push is off or none is
     * usable. With no choice saved, a default or sole distributor is adopted.
     */
    suspend fun toUse(): String? {
        if (!settings.settings.first().pushEnabled) return null
        UnifiedPush.getSavedDistributor(context)?.let { return it }
        return when (val resolved = UnifiedPush.resolveDefaultDistributor(context)) {
            is ResolvedDistributor.Found -> resolved.packageName
                .takeIf { it != context.packageName }
                ?.also { UnifiedPush.saveDistributor(context, it) }
            ResolvedDistributor.ToSelect, ResolvedDistributor.NoneAvailable -> null
        }
    }

    /** The saved choice, without resolving a default. For display. */
    fun saved(): String? = UnifiedPush.getSavedDistributor(context)

    suspend fun select(packageName: String) {
        UnifiedPush.saveDistributor(context, packageName)
        settings.setPushEnabled(true)
    }

    /** Turns push off. Every registration with the distributor goes with it. */
    suspend fun disable() {
        settings.setPushEnabled(false)
        UnifiedPush.removeDistributor(context)
    }

    /**
     * Unregisters one instance without losing the user's choice, which the
     * connector drops along with its last instance.
     */
    fun unregister(instance: String) {
        val chosen = UnifiedPush.getSavedDistributor(context)
        UnifiedPush.unregister(context, instance)
        if (chosen != null && UnifiedPush.getSavedDistributor(context) == null) {
            UnifiedPush.saveDistributor(context, chosen)
        }
    }

    fun labelOf(packageName: String): String = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }
}

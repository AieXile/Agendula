package com.aiexile.agendula.data.tasks

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two platform facts [ProviderResolver] needs, behind an interface.
 *
 * Same seam the data source uses, for the same reason: which store a returning
 * user lands on is decided by [ProviderResolver.autoMode], getting it wrong shows
 * them an empty app, and that decision is worth testing on the JVM rather than
 * only on a device. Everything Android-shaped lives here so the logic above stays
 * plain Kotlin.
 */
interface ProviderEnvironment {

    /** The package declaring [authority], or `null` when nothing on the device does. */
    fun packageDeclaring(authority: String): String?

    /** Whether this app currently holds [permission]. */
    fun isGranted(permission: String): Boolean

    /** [packageName]'s own app name, or null when it cannot be read. */
    fun appLabel(packageName: String): String?
}

@Singleton
class AndroidProviderEnvironment @Inject constructor(
    @ApplicationContext private val context: Context,
) : ProviderEnvironment {

    override fun packageDeclaring(authority: String): String? =
        context.packageManager.resolveContentProvider(authority, 0)?.packageName

    override fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun appLabel(packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()
}

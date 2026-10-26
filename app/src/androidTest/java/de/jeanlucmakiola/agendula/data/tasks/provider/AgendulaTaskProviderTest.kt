package de.jeanlucmakiola.agendula.data.tasks.provider

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The provider as another app meets it: manifest, permission, URIs. */
@RunWith(AndroidJUnit4::class)
class AgendulaTaskProviderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val authority = "${context.packageName}.tasks"
    private val readPermission = "${context.packageName}.permission.READ_TASKS"
    private val writePermission = "${context.packageName}.permission.WRITE_TASKS"
    private val resolver = context.contentResolver

    private fun uri(path: String) = "content://$authority/$path".toUri()

    @Test
    fun isExportedUnderItsPermissions() {
        val info = requireNotNull(context.packageManager.resolveContentProvider(authority, 0))

        assertThat(info.name).isEqualTo(AgendulaTaskProvider::class.java.name)
        assertThat(info.exported).isTrue()
        assertThat(info.readPermission).isEqualTo(readPermission)
        assertThat(info.writePermission).isEqualTo(writePermission)
    }

    @Test
    fun bothPermissionsAreDangerous() {
        listOf(readPermission, writePermission).forEach { permission ->
            val info = context.packageManager.getPermissionInfo(permission, 0)

            assertThat(info.protection).isEqualTo(PermissionInfo.PROTECTION_DANGEROUS)
            assertThat(info.loadLabel(context.packageManager).toString()).isNotEmpty()
            assertThat(info.loadDescription(context.packageManager)?.toString()).isNotEmpty()
        }
    }

    @Test
    fun anAppWithoutThePermissionIsDenied() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        // The shell is another uid that was never granted it.
        val output = shell("content query --uri content://$authority/tasks")

        assertThat(output).contains("Permission Denial")
    }

    @Test
    fun anAppWithoutTheWritePermissionCannotWrite() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

        val output = shell("content insert --uri content://$authority/tasks --bind title:s:x --bind list_id:i:1")

        assertThat(output).contains("Permission Denial")
        assertThat(output).contains(writePermission)
    }

    @Test
    fun reportsOpenTasksMimeTypes() {
        ProviderTable.entries.forEach { table ->
            assertThat(resolver.getType(uri(table.path))).isEqualTo(table.mimeType(single = false))
            assertThat(resolver.getType(uri("${table.path}/3"))).isEqualTo(table.mimeType(single = true))
        }
    }

    @Test
    fun everyTableAnswersWithItsColumns() {
        ProviderTable.entries.forEach { table ->
            resolver.query(uri(table.path), null, null, null, null)!!.use {
                assertThat(it.columnNames.toList()).containsExactlyElementsIn(table.columns.map { c -> c.first }).inOrder()
            }
        }
    }

    @Test
    fun aCursorWatchesTheUriItWasQueriedFor() {
        val tasks = uri("tasks")

        resolver.query(tasks, null, null, null, null)!!.use { assertThat(it.notificationUri).isEqualTo(tasks) }
    }

    @Test
    fun aRowUriReadsOnlyThatRow() {
        // Ids start at 1, so 0 names nothing whatever the store holds.
        resolver.query(uri("tasks/0"), null, null, null, null)!!.use { assertThat(it.count).isEqualTo(0) }
        resolver.query(uri("tasks/0"), null, "1 = 1 OR 1 = 1", null, null)!!.use { assertThat(it.count).isEqualTo(0) }
    }

    @Test
    fun anUnknownUriIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { resolver.query(uri("events"), null, null, null, null) }
        // ContentResolver.getType swallows the provider's exception and answers null.
        assertThat(runCatching { resolver.getType(uri("tasks/abc")) }.getOrNull()).isNull()
    }

    @Test
    fun listsAreReadOnly() {
        val values = ContentValues().apply { put("list_name", "x") }

        assertThrows(UnsupportedOperationException::class.java) { resolver.insert(uri("tasklists"), values) }
        assertThrows(UnsupportedOperationException::class.java) { resolver.update(uri("tasklists/1"), values, null, null) }
        assertThrows(UnsupportedOperationException::class.java) { resolver.delete(uri("tasklists/1"), null, null) }
    }

    @Test
    fun aRowUriTakesNoInsert() {
        assertThrows(IllegalArgumentException::class.java) {
            resolver.insert(uri("tasks/1"), ContentValues().apply { put("title", "x") })
        }
    }

    @Test
    fun aBadWriteReachesTheClientAsIllegalArgument() {
        // No list has this id, so nothing in the app's real store is touched.
        val values = ContentValues().apply {
            put("list_id", Long.MAX_VALUE)
            put("title", "x")
        }

        assertThrows(IllegalArgumentException::class.java) { resolver.insert(uri("tasks"), values) }
    }

    @Test
    fun aFailingBatchWritesNothing() {
        val missingList = Long.MAX_VALUE
        val operations = arrayListOf(
            ContentProviderOperation.newInsert(uri("tasks")).withValue("list_id", missingList).withValue("title", "a").build(),
        )

        assertThrows(IllegalArgumentException::class.java) { resolver.applyBatch(authority, operations) }
        resolver.query(uri("tasks"), arrayOf("_id"), "list_id = ?", arrayOf(missingList.toString()), null)!!
            .use { assertThat(it.count).isEqualTo(0) }
    }

    /** stdout and stderr of [command]; the denial lands on stderr. */
    private fun shell(command: String): String {
        val (stdout, stdin, stderr) = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommandRwe(command)
        stdin.close()
        return listOf(stdout, stderr).joinToString("\n") { pfd ->
            ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        }
    }
}

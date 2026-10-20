package app.momoda

import app.momoda.SyncPlan.Action.Conflict
import app.momoda.SyncPlan.Action.DeleteRemote
import app.momoda.SyncPlan.Action.Download
import app.momoda.SyncPlan.Action.TrashLocal
import app.momoda.SyncPlan.Action.Upload
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlanTest {

    private fun plan(local: Map<String, String>, remote: Map<String, String>, base: Map<String, String>) =
        SyncPlan.plan(local, remote, base, "1020-0815")

    @Test fun inSync() = assertEquals(emptyList<Any>(), plan(mapOf("a.md" to "1"), mapOf("a.md" to "1"), mapOf("a.md" to "1")))

    @Test fun firstSyncUploadsLocal() = assertEquals(listOf(Upload("a.md")), plan(mapOf("a.md" to "1"), emptyMap(), emptyMap()))

    @Test fun firstSyncDownloadsRemote() = assertEquals(listOf(Download("_agent/r.md")), plan(emptyMap(), mapOf("_agent/r.md" to "1"), emptyMap()))

    @Test fun firstSyncSameContentNoop() = assertEquals(emptyList<Any>(), plan(mapOf("a.md" to "1"), mapOf("a.md" to "1"), emptyMap()))

    @Test fun firstSyncDifferentContentIsConflict() =
        assertEquals(listOf(Conflict("a.md", "a.remote-1020-0815.md")), plan(mapOf("a.md" to "1"), mapOf("a.md" to "2"), emptyMap()))

    @Test fun localEdit() = assertEquals(listOf(Upload("a.md")), plan(mapOf("a.md" to "2"), mapOf("a.md" to "1"), mapOf("a.md" to "1")))

    @Test fun remoteEdit() = assertEquals(listOf(Download("a.md")), plan(mapOf("a.md" to "1"), mapOf("a.md" to "2"), mapOf("a.md" to "1")))

    @Test fun bothEditedSameWayIsNoop() = assertEquals(emptyList<Any>(), plan(mapOf("a.md" to "3"), mapOf("a.md" to "3"), mapOf("a.md" to "1")))

    @Test fun bothEditedDifferentlyIsConflict() =
        assertEquals(listOf(Conflict("a.md", "a.remote-1020-0815.md")), plan(mapOf("a.md" to "2"), mapOf("a.md" to "3"), mapOf("a.md" to "1")))

    @Test fun remoteDeletedLocalUntouchedGoesToTrash() =
        assertEquals(listOf(TrashLocal("a.md")), plan(mapOf("a.md" to "1"), emptyMap(), mapOf("a.md" to "1")))

    @Test fun remoteDeletedButLocalEditedIsPushedBack() =
        assertEquals(listOf(Upload("a.md")), plan(mapOf("a.md" to "2"), emptyMap(), mapOf("a.md" to "1")))

    @Test fun localDeletedRemoteUntouchedDeletesRemote() =
        assertEquals(listOf(DeleteRemote("a.md")), plan(emptyMap(), mapOf("a.md" to "1"), mapOf("a.md" to "1")))

    @Test fun localDeletedButRemoteEditedIsPulledBack() =
        assertEquals(listOf(Download("a.md")), plan(emptyMap(), mapOf("a.md" to "2"), mapOf("a.md" to "1")))

    @Test fun bothDeletedIsNoop() = assertEquals(emptyList<Any>(), plan(emptyMap(), emptyMap(), mapOf("a.md" to "1")))

    @Test fun nextBaseKeepsOldBaseWhenSidesDiffer() = assertEquals(
        mapOf("same.md" to "2", "skipped.md" to "1"),
        SyncPlan.nextBase(
            agreed = mapOf("same.md" to "2", "skipped.md" to "9", "copy.md" to "5"),
            remote = mapOf("same.md" to "2", "skipped.md" to "3"),
            base = mapOf("same.md" to "1", "skipped.md" to "1", "gone.md" to "1"),
        ))

    @Test fun conflictCopyNeverOverwritesExistingFile() = assertEquals(
        listOf(Conflict("a.md", "a.remote-1020-0815-3.md"), Download("a.remote-1020-0815-2.md")),
        plan(
            mapOf("a.md" to "2", "a.remote-1020-0815.md" to "x"),
            mapOf("a.md" to "3", "a.remote-1020-0815.md" to "x", "a.remote-1020-0815-2.md" to "y"),
            mapOf("a.md" to "1", "a.remote-1020-0815.md" to "x"),
        ))

    @Test fun conflictCopyReusedWhenItAlreadyHoldsRemoteVersion() = assertEquals(
        listOf(Conflict("a.md", "a.remote-1020-0815.md"), Upload("a.remote-1020-0815.md")),
        plan(mapOf("a.md" to "2", "a.remote-1020-0815.md" to "3"), mapOf("a.md" to "3"), mapOf("a.md" to "1")))

    @Test fun conflictNameInSubfolder() = assertEquals("_agent/x.remote-1020-0815.md", SyncPlan.conflictName("_agent/x.md", "1020-0815"))

    @Test fun blobShaMatchesGit() {
        // `printf 'hello\n' | git hash-object --stdin`
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", SyncPlan.blobSha("hello\n".toByteArray()))
        // 空文件
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", SyncPlan.blobSha(ByteArray(0)))
    }
}

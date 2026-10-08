package io.github.hematite

import io.github.hematite.core.Dav
import io.github.hematite.core.SyncEngine
import io.github.hematite.core.SyncState
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop's live round trip, against a scratch folder on a real server:
 * `./gradlew testDebugUnitTest -Plive -PdavUrl=http://127.0.0.1:PORT/` (user `test`, password `secret`).
 */
class LiveSyncTest {
    private val url = System.getProperty("davUrl") ?: "http://127.0.0.1:18083/"

    @Test fun liveWebdavRoundTrip() {
        val folder = "hematite-sync-test-${UUID.randomUUID()}"
        val base = Dav(url, "test", "secret")
        base.checked("MKCOL", folder)
        val root = Files.createTempDirectory(folder).toFile()
        try {
            val dav = base.copy(url = "$url$folder/")
            dav.probe()
            assertTrue("rclone is expected to need lock protection", dav.lockWrites)
            var state = SyncState()
            fun update(): List<String> {
                val outcome = SyncEngine.cycle(dav, SyncEngine.snapshot(root), state, false, false)
                for (change in outcome.changes) {
                    if (change.bytes == null) {
                        val file = File(root, change.path)
                        assertEquals(change.expected, Dav.hash(file.readBytes()))
                        file.delete()
                    } else assertTrue(SyncEngine.apply(root, change) { it.delete() })
                    if (change.record != null) outcome.files[change.path] = change.record!! else outcome.files.remove(change.path)
                }
                state = Json.decodeFromString(SyncState.serializer(), Json.encodeToString(SyncState.serializer(), outcome.state))
                return outcome.conflicts
            }
            dav.put("remote.md", "remote initial".toByteArray(), null)
            update()
            assertEquals("remote initial", File(root, "remote.md").readText())
            File(root, "remote.md").writeText("local save")
            update()
            assertEquals("local save", String(dav.get("remote.md").first))
            val stale = state.files["remote.md"]!!.etag
            dav.put("remote.md", "another device".toByteArray(), dav.get("remote.md").second)
            assertThrows(Exception::class.java) { dav.put("remote.md", "stale overwrite".toByteArray(), stale) }
            assertEquals("another device", String(dav.get("remote.md").first))
            update()
            assertEquals("another device", File(root, "remote.md").readText())
            File(root, "remote.md").writeText("local conflict")
            dav.put("remote.md", "remote conflict".toByteArray(), dav.get("remote.md").second)
            val conflicts = update()
            assertEquals(1, conflicts.size)
            assertEquals("local conflict", String(dav.get("remote.md").first))
            assertEquals("remote conflict", String(dav.get(conflicts[0]).first))
            assertEquals("remote conflict", File(root, conflicts[0]).readText())
            File(root, "nested").mkdir()
            File(root, "nested/café & [name].md").writeText("unicode path")
            update()
            assertEquals("unicode path", String(dav.get("nested/café & [name].md").first))
            update() // Verify the next XML listing decodes these names.
            File(root, "remote.md").renameTo(File(root, "renamed.md"))
            // A rename made inside the app records the old path as deleted.
            state = state.copy(deletions = state.deletions + "remote.md")
            update()
            assertEquals(404, dav.request("GET", "remote.md").status)
            assertEquals("local conflict", String(dav.get("renamed.md").first))
            dav.delete("renamed.md", dav.get("renamed.md").second)
            update()
            assertFalse(File(root, "renamed.md").exists())
            File(root, "offline.md").writeText("pending local save")
            assertThrows(Exception::class.java) { SyncEngine.cycle(dav.copy(url = "http://127.0.0.1:1/"), SyncEngine.snapshot(root), state, false, false) }
            assertEquals("pending local save", File(root, "offline.md").readText())
            update()
            assertEquals("pending local save", String(dav.get("offline.md").first))
            println("Verified bootstrap, upload, download, stale-write protection, conflict copies, Unicode paths, rename, deletion, and offline recovery.")
        } finally {
            val cleanup = base.request("DELETE", folder)
            root.deleteRecursively()
            assertTrue("Remote scratch cleanup failed", cleanup.status in 200..299)
        }
    }
}

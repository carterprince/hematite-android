package io.github.hematite

import io.github.hematite.core.Alignment
import io.github.hematite.core.Change
import io.github.hematite.core.Dav
import io.github.hematite.core.IndexedNote
import io.github.hematite.core.Lists
import io.github.hematite.core.Markdown
import io.github.hematite.core.MarkdownDocument
import io.github.hematite.core.Moves
import io.github.hematite.core.Record
import io.github.hematite.core.Run
import io.github.hematite.core.Search
import io.github.hematite.core.Style
import io.github.hematite.core.SyncEngine
import io.github.hematite.core.SyncState
import io.github.hematite.core.Vault
import io.github.hematite.core.VaultException
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports of the desktop app's unit tests. */
class CoreTest {
    private fun temporary(name: String) = Files.createTempDirectory("hematite-$name").toFile()

    // markdown_syntax.rs

    private fun rendered(text: String, document: MarkdownDocument) =
        text.filterIndexed { index, _ -> document.markers.none { index in it } }

    @Test fun parsesTableCellsAlignmentAndSourceRows() {
        val text = "| Claim | Prediction | Observation |\n| :--- | :---: | ---: |\n| Café **bold** | Longer text | A \\| B |\n"
        val document = Markdown.parse(text)
        assertEquals(1, document.tables.size)
        val table = document.tables[0]
        assertEquals(2, table.rows.size)
        assertTrue(table.rows[0].header)
        assertEquals(listOf(listOf(Run("Café "), Run("bold", bold = true)), listOf(Run("Longer text")), listOf(Run("A | B"))),
            table.rows[1].cells.map { cell -> cell.fold(mutableListOf<Run>()) { runs, run ->
                // Adjacent plain text may arrive in pieces; join it for comparison.
                val last = runs.lastOrNull()
                if (last != null && last.copy(text = "") == run.copy(text = "")) runs[runs.lastIndex] = last.copy(text = last.text + run.text) else runs += run
                runs
            } })
        assertEquals(listOf(Alignment.Left, Alignment.Center, Alignment.Right), table.alignments)
        val range = table.rows[0].range
        assertEquals("| Claim | Prediction | Observation |", text.substring(range.start, range.end))
    }

    @Test fun recognizesCheckedAndUncheckedTasksWithoutExtraBullets() {
        val text = "- [ ] item\n- [X] done\n* [x] lowercase\n- normal\n\n```\n- [ ] code\n```\n"
        val document = Markdown.parse(text)
        assertEquals(listOf(false, true, true), document.tasks.map { it.second })
        assertEquals(1, document.bullets.size)
        assertTrue(rendered(text, document).startsWith(" item\n done\n lowercase\n normal"))
        assertTrue(rendered(text, document).contains("- [ ] code"))
    }

    @Test fun headingsAndStarBulletsPreserveSourceOffsets() {
        val text = "# Café\n## Second\n### Third\n#### Fourth\n\n* Star item\n- Dash item\n\n```\n# Code\n* Code\n```\n\n#nospace\n"
        val document = Markdown.parse(text)
        assertEquals(listOf(Style.Heading1, Style.Heading2, Style.Heading3, Style.Heading4), document.styles.map { it.style }.filter { it.name.startsWith("Heading") })
        assertEquals(2, document.bullets.size)
        val visible = rendered(text, document)
        assertTrue(visible, visible.startsWith("Café\nSecond\nThird\nFourth\n"))
        assertTrue(visible.contains("```\n# Code\n* Code\n```"))
        assertTrue(visible.endsWith("#nospace\n"))
    }

    @Test fun parsesStylesBulletsAndLinksWithUnicodeOffsets() {
        val text = "é **bold** and *italic*\n- item\n  - nested\n[GTK](https://gtk.org) and https://example.org.\n"
        val document = Markdown.parse(text)
        assertEquals(2, document.styles.size)
        assertEquals(Style.Bold, document.styles[0].style)
        assertEquals(2, document.bullets.size)
        assertEquals(2, document.links.size)
        assertEquals("https://gtk.org", document.links[0].uri)
        assertEquals("https://example.org", document.links[1].uri)
        assertEquals("é bold and italic\n item\n   nested\nGTK and https://example.org.\n", rendered(text, document))
    }

    @Test fun leavesCodeEscapesAndUnfinishedMarkupAlone() {
        val text = "`**code** https://code.example`\n\n```md\n- **code**\nhttps://code.example\n```\n\n\\*literal\\* and **unfinished"
        val document = Markdown.parse(text)
        assertTrue(document.styles.isEmpty())
        assertTrue(document.bullets.isEmpty())
        assertTrue(document.links.isEmpty())
        assertEquals(text, rendered(text, document))
    }

    @Test fun supportsNestedEmphasisAndKeepsUnsafeLinksLiteral() {
        val text = "***both*** and **bold *nested*** [bad](javascript:alert) [mail](mailto:test@example.org)"
        val document = Markdown.parse(text)
        assertEquals("both and bold nested [bad](javascript:alert) mail", rendered(text, document))
        assertEquals(4, document.styles.size)
        assertEquals(1, document.links.size)
    }

    @Test fun preservesUrlParenthesesAndIpv6Addresses() {
        val uris = Markdown.parse("See (https://example.org/a_(b)). http://[::1]/test <https://example.org>").links.map { it.uri }
        assertTrue(uris.toString(), "https://example.org/a_(b)" in uris)
        assertTrue("http://[::1]/test" in uris)
        assertTrue("https://example.org" in uris)
        assertEquals(3, uris.size)
    }

    @Test fun findsImagesWithTheirDestinations() {
        val text = "Before\n![](../paste-1.png)\n![alt](a%20b.png \"title\")\n"
        val document = Markdown.parse(text)
        assertEquals(listOf("../paste-1.png", "a%20b.png"), document.images.map { it.second })
        assertEquals("![](../paste-1.png)", document.images[0].first.let { text.substring(it.start, it.end) })
    }

    // lists.rs

    @Test fun continuesNumbersAndPreservesBulletAndIndent() {
        assertEquals(3 to "4. ", Lists.continuation("3. text"))
        assertEquals(4 to "100. ", Lists.continuation("99. text"))
        assertEquals(4 to "  * ", Lists.continuation("  * text"))
        assertEquals(3 to "\t- ", Lists.continuation("\t- text"))
        assertNull(Lists.continuation("ordinary text"))
        assertNull(Lists.continuation("3.text"))
    }

    @Test fun tabNestsUnderNumberAndShiftTabRestoresNextNumber() {
        assertEquals(3 to "   - ", Lists.tabPrefix("4. ", "3. parent\n", false))
        assertEquals(5 to "4. ", Lists.tabPrefix("   - child", "3. parent\n   - sibling\n", true))
        assertEquals(2 to "  - ", Lists.tabPrefix("- child", "- parent\n", false))
        assertEquals(4 to "- ", Lists.tabPrefix("  - child", "- parent\n", true))
    }

    @Test fun checkboxContinuationIsUncheckedAndPreservesIndent() {
        assertEquals(9 to "   - [ ] ", Lists.continuation("   - [ ] awef"))
        assertEquals(8 to "  * [ ] ", Lists.continuation("  * [X] done"))
        assertEquals(6 to "- [ ] ", Lists.continuation("- [x] done"))
        assertEquals(6 to "- [ ] ", Lists.continuation("- [ ] "))
    }

    @Test fun enterContinuesAndEndsLists() {
        assertEquals(Lists.enter("- one", 5), io.github.hematite.core.Edit("- one\n- ", 8))
        assertEquals(io.github.hematite.core.Edit("a\n", 2), Lists.enter("a\n- ", 4))
        assertNull(Lists.enter("plain", 5))
        assertEquals(io.github.hematite.core.Edit("- a\n  - b", 9), Lists.tab("- a\n- b", 7, false))
    }

    // moves.rs

    @Test fun rebasesRelativeLinksAndImagesWithoutTouchingCodeOrExternalUrls() {
        val text = "![image](paste.png) [note](other.md#heading)\n![ref][pic]\n\n[pic]: <images/a%20b.png> \"caption\"\n\n`![literal](paste.png)` [web](https://example.org)"
        val result = Moves.rebase(text, File("/vault"), File("/vault/folder"))
        assertTrue(result, result.contains("![image](../paste.png)"))
        assertTrue(result.contains("[note](../other.md#heading)"))
        assertTrue(result, result.contains("[pic]: <../images/a%20b.png> \"caption\""))
        assertTrue(result.contains("`![literal](paste.png)` [web](https://example.org)"))
        assertEquals(text, Moves.rebase(result, File("/vault/folder"), File("/vault")))
        val nested = "![a [nested] label](image.png \"caption ]( untouched\")"
        assertEquals("![a [nested] label](../image.png \"caption ]( untouched\")", Moves.rebase(nested, File("/vault"), File("/vault/folder")))
    }

    // search.rs

    @Test fun usesModificationDateToBreakRelevanceTies() {
        val root = File("/scratch")
        val notes = listOf(IndexedNote(File(root, "a.md"), root, "same content", 10), IndexedNote(File(root, "b.md"), root, "same content", 20))
        assertEquals(listOf("b", "a"), Search.find(notes, "content").map { it.title })
    }

    @Test fun balancesTitlePathAndBodyAndIncludesUnsavedText() {
        val root = File("/scratch")
        val notes = listOf(IndexedNote(File(root, "other.md"), root, "Planning a launch tomorrow", 0),
            IndexedNote(File(root, "launch.md"), root, "Schedule", 0), IndexedNote(File(root, "launch/tasks.md"), root, "Schedule", 0))
        val hits = Search.find(notes, "LAUNCH")
        assertEquals(listOf("launch", "tasks", "other"), hits.map { it.title })
        assertEquals("Planning a launch tomorrow", hits[2].preview)
        assertEquals(1, Search.find(notes, "launch tomorrow").size)
        assertTrue(Search.find(notes, "launch missing").isEmpty())
        assertTrue(Search.find(notes, "   ").isEmpty())
        assertEquals("other", Search.find(notes, "UNSAVED café", notes[0].file to "Unsaved CAFÉ notes")[0].title)
    }

    @Test fun contentPreviewsIncludeDistantUnicodeMatches() {
        val text = "${"é".repeat(180)} café target ${"字".repeat(180)}"
        val excerpt = Search.preview(text, listOf("café", "target"))!!
        assertTrue(excerpt.contains("café target"))
        assertTrue(excerpt.startsWith('…') && excerpt.endsWith('…'))
        assertTrue(excerpt.codePointCount(0, excerpt.length) <= 142)
    }

    // sync_dav.rs

    @Test fun readsRemoteModificationDates() {
        assertEquals(784111777000L, Dav.parseModified("Sun, 06 Nov 1994 08:49:37 GMT"))
        assertNull(Dav.parseModified("invalid"))
        assertNull(Dav.parseModified("Sun, 99 Nov 1994 08:49:37 GMT"))
    }

    @Test fun pathsAndNames() {
        assertEquals("https://example.org:8080/vault/", Dav.normalizeUrl("example.org:8080/vault"))
        assertThrows(Exception::class.java) { Dav.normalizeUrl("https://u:p@example.org/") }
        assertEquals("caf%C3%A9/one%20%26%20two.md", Dav.encode("café/one & two.md"))
        for (bad in listOf("../secret", "/etc/passwd", "folder/../note", "a\\b")) assertFalse(Dav.safePath(bad))
        assertEquals("café/one & two.md", Dav.decode("caf%C3%A9/one%20%26%20two.md"))
        assertNull(Dav.decode("bad%2"))
    }

    @Test fun refusesPartialListings() {
        val xml = "<d:multistatus xmlns:d='DAV:'><d:response><d:href>/one</d:href><d:status>HTTP/1.1 403 Forbidden</d:status></d:response></d:multistatus>"
        assertThrows(Exception::class.java) { Dav.parseListing(xml.toByteArray()) }
    }

    /** A tiny HTTP server answering each connection with the next scripted response, and checking each request. */
    private fun serve(responses: List<Triple<String, Int, String>>): Pair<String, Thread> {
        val socket = ServerSocket(0)
        val server = thread {
            socket.use {
                for ((expected, status, body) in responses) {
                    it.accept().use { client ->
                        client.soTimeout = 3000
                        val input = client.getInputStream()
                        val request = StringBuilder()
                        while (!request.endsWith("\r\n\r\n")) request.append(input.read().toChar())
                        val length = Regex("(?i)content-length: *(\\d+)").find(request)?.groupValues?.get(1)?.toInt() ?: 0
                        repeat(length) { input.read() }
                        check(request.toString().lowercase().contains(expected.lowercase())) { "Expected $expected in $request" }
                        val bytes = body.toByteArray()
                        client.getOutputStream().write("HTTP/1.1 $status Response\r\nETag: \"v1\"\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
                    }
                }
            }
        }
        return "http://127.0.0.1:${socket.localPort}/" to server
    }

    @Test fun recursiveListingAndDepthOneFallback() {
        val root = "<d:response><d:href>/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
        val folder = root.replace("<d:href>/</d:href>", "<d:href>/folder/</d:href>")
        val file = "<d:response><d:href>/folder/note.md</d:href><d:propstat><d:prop><d:resourcetype/><d:getetag>&quot;v1&quot;</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
        fun listing(body: String) = "<d:multistatus xmlns:d='DAV:'>$body</d:multistatus>"
        for (fallback in listOf(false, true)) {
            val responses = if (fallback) listOf(Triple("depth: infinity", 403, ""), Triple("depth: 1", 207, listing(root + folder)),
                Triple("depth: 1", 207, listing(folder + file)))
            else listOf(Triple("depth: infinity", 207, listing(root + folder + file)))
            val (url, server) = serve(responses)
            val files = Dav(url, "test", "test").list()
            assertEquals(1, files.size)
            assertEquals("\"v1\"", files["folder/note.md"]!!.etag)
            server.join()
        }
    }

    // sync_engine.rs

    @Test fun deletionPolicyRespectsExternalSettingExplicitTrashAndMissingVault() {
        val listing = "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response><d:response><d:href>/note.md</d:href><d:propstat><d:prop><d:resourcetype/><d:getetag>&quot;v1&quot;</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"
        for ((external, explicit, missing, changed) in listOf(
            listOf(false, false, false, false), listOf(true, false, false, false), listOf(false, true, false, false),
            listOf(true, false, true, false), listOf(false, true, true, false), listOf(false, false, false, true),
            listOf(true, false, false, true), listOf(false, true, false, true))) {
            val delete = (external || explicit) && !missing && !changed
            val (url, server) = serve(listOf(Triple("PROPFIND", 207, listing),
                if (delete) Triple("DELETE", 204, "") else Triple("GET", 200, "remote note")))
            var state = SyncState(
                deletions = if (explicit) setOf("note.md") else emptySet(),
                files = mapOf("note.md" to Record(Dav.hash("remote note".toByteArray()), if (changed) "\"old\"" else "\"v1\"")))
            // Restart with persisted intent.
            state = Json.decodeFromString(SyncState.serializer(), Json.encodeToString(SyncState.serializer(), state))
            val outcome = SyncEngine.cycle(Dav(url, "", ""), emptyMap(), state, external, missing)
            assertEquals(changed && (external || explicit) && !missing, outcome.conflicts.isNotEmpty())
            assertTrue(outcome.deletions.isEmpty())
            if (delete) {
                assertTrue(outcome.changes.isEmpty())
                assertFalse(outcome.files.containsKey("note.md"))
            } else {
                assertEquals(1, outcome.changes.size)
                assertEquals("remote note", String(outcome.changes[0].bytes!!))
            }
            server.join()
        }
    }

    @Test fun downloadPreservesRemoteModificationOrder() {
        val root = temporary("times")
        val old = 1_000_000_000L
        val new = old + 100_000
        for ((name, modified) in listOf("new.md" to new, "old.md" to old)) {
            assertTrue(SyncEngine.apply(root, Change(modified, name, null, "note".toByteArray(), null)) {})
            assertEquals(modified, File(root, name).lastModified())
        }
        assertEquals("new.md", Vault.entries(root)[0].file.name)
        root.deleteRecursively()
    }

    @Test fun applyingDownloadDoesNotReplaceANewerLocalSave() {
        val root = temporary("sync")
        File(root, "note.md").writeText("new local edit")
        assertFalse(SyncEngine.apply(root, Change(null, "note.md", Dav.hash("old".toByteArray()), "remote".toByteArray(), null)) {})
        assertEquals("new local edit", File(root, "note.md").readText())
        assertThrows(Exception::class.java) { SyncEngine.apply(root, Change(null, "../outside", null, ByteArray(0), null)) {} }
        val trashed = mutableListOf<File>()
        assertTrue(SyncEngine.apply(root, Change(null, "note.md", Dav.hash("new local edit".toByteArray()), null, null)) { trashed += it })
        assertEquals(listOf(File(root, "note.md")), trashed)
        root.deleteRecursively()
    }

    @Test fun conflictNamesKeepExtension() {
        assertTrue(SyncEngine.conflictPath("nested/note.md", "remote".toByteArray()).startsWith("nested/note.conflict-remote-"))
        assertTrue(SyncEngine.conflictPath("nested/note.md", "remote".toByteArray()).endsWith(".md"))
        assertTrue(SyncEngine.conflictPath("README", "x".toByteArray()).matches(Regex("README\\.conflict-remote-[0-9a-f]{12}")))
    }

    @Test fun snapshotSkipsHiddenFiles() {
        val root = temporary("snapshot")
        File(root, ".obsidian").mkdir()
        File(root, ".obsidian/workspace.json").writeText("{}")
        File(root, "folder").mkdir()
        File(root, "folder/note.md").writeText("note")
        File(root, "image.png").writeBytes(byteArrayOf(1, 2))
        assertEquals(listOf("folder/note.md", "image.png"), SyncEngine.snapshot(root).keys.toList())
        root.deleteRecursively()
    }

    // vault.rs, notes.rs, images.rs

    @Test fun sortsNewestFirstWithinEachFolder() {
        val root = temporary("sort")
        File(root, "folder").mkdir()
        for ((name, seconds) in listOf("old.md" to 10, "new.md" to 30, "b.md" to 20, "a.md" to 20, "folder/old.md" to 10, "folder/new.md" to 30)) {
            File(root, name).writeText(name)
            File(root, name).setLastModified(seconds * 1000L)
        }
        File(root, "folder").setLastModified(25_000)
        assertEquals(listOf("new.md", "folder", "folder/new.md", "folder/old.md", "a.md", "b.md", "old.md"),
            Vault.entries(root).map { it.file.relativeTo(root).invariantSeparatorsPath })
        root.deleteRecursively()
    }

    @Test fun discoversNotesAndPreventsExternalOverwrites() {
        val root = temporary("vault")
        File(root, "folder").mkdir()
        File(root, ".hidden").mkdir()
        File(root, "folder/note.md").writeText("original")
        File(root, "image.png").writeText("ignore")
        File(root, ".hidden/note.md").writeText("ignore")
        val files = Vault.entries(root)
        assertEquals(2, files.size)
        assertTrue(files[0].directory)
        assertEquals(File(root, "folder"), files[0].file)
        assertEquals(File(root, "folder/note.md"), files[1].file)
        assertEquals(1, files[1].depth)
        Vault.validateEntry(root, files[1].file)
        Vault.validateEntry(root, File(root, "folder"))
        assertThrows(VaultException::class.java) { Vault.validateEntry(root, root) }
        assertThrows(VaultException::class.java) { Vault.validateEntry(File(root, "folder"), File(root, "image.png")) }
        Files.createSymbolicLink(File(root, "link.md").toPath(), files[1].file.toPath())
        assertThrows(VaultException::class.java) { Vault.validateEntry(root, File(root, "link.md")) }
        Vault.save(files[1].file, "original", "edited")
        assertThrows(VaultException::class.java) { Vault.save(files[1].file, "original", "stale edit") }
        assertEquals("edited", files[1].file.readText())
        root.deleteRecursively()
    }

    @Test fun createsMarkdownWithoutOverwritingExistingNotes() {
        val root = temporary("new-note")
        val file = Vault.createNote(root, "My ideas.md")
        file.writeText("existing note")
        assertThrows(VaultException::class.java) { Vault.createNote(root, "My ideas.md") }
        assertEquals("existing note", file.readText())
        assertEquals("Other.md", Vault.createNote(root, "Other").name)
        for (invalid in listOf(".md", "", "../outside.md", "nested/note", ".hidden", "a\nb")) {
            assertThrows(invalid, VaultException::class.java) { Vault.createNote(root, invalid) }
        }
        root.deleteRecursively()
    }

    @Test fun createsUniqueRootAttachmentsAndRelativeLinks() {
        val root = temporary("images")
        File(root, "one/two").mkdirs()
        val note = File(root, "one/two/note.md")
        val (first, link) = Vault.saveImage(root, note, "first".toByteArray(), "png")
        val (second, _) = Vault.saveImage(root, note, "second".toByteArray(), "png")
        assertNotEquals(first, second)
        assertEquals(root, first.parentFile)
        assertEquals("![](../../${first.name})", link)
        assertEquals("first", first.readText())
        assertEquals("second", second.readText())
        assertTrue(Vault.saveImage(root, File(root, "root.md"), "third".toByteArray(), "png").second.startsWith("![](paste-"))
        assertThrows(Exception::class.java) { Vault.saveImage(root, File(root.parentFile, "outside.md"), "no".toByteArray(), "png") }
        root.deleteRecursively()
    }

    @Test fun movesNotesWithRebasedLinksAndRefusesCollisions() {
        val root = temporary("move")
        File(root, "folder").mkdir()
        File(root, "Asset Folder").mkdir()
        val source = File(root, "Move.md")
        source.writeText("![image](Asset%20Folder/paste.png)\n[other](Other.md)\n")
        val moved = Vault.moveNote(root, source, File(root, "folder"), "![image](Asset%20Folder/paste.png)\nUnsaved edits\n", source.readText())!!
        assertEquals(File(root, "folder/Move.md"), moved.destination)
        assertFalse(source.exists())
        assertEquals("![image](../Asset%20Folder/paste.png)\n[other](../Other.md)\n", moved.destination.readText())
        assertEquals("![image](../Asset%20Folder/paste.png)\nUnsaved edits\n", moved.editor)
        source.writeText("Existing destination")
        assertThrows(VaultException::class.java) { Vault.moveNote(root, moved.destination, root, null, null) }
        assertEquals("Existing destination", source.readText())
        root.deleteRecursively()
    }

    @Test fun trashRestoresToTheOriginalPath() {
        val root = temporary("trash")
        File(root, "folder").mkdir()
        File(root, "folder/note.md").writeText("note")
        Vault.trash(root, File(root, "folder"))
        assertFalse(File(root, "folder").exists())
        assertTrue(Vault.entries(root).isEmpty())
        assertTrue(SyncEngine.snapshot(root).isEmpty())
        val item = Vault.trashed(root).single()
        assertEquals("folder", item.original)
        Vault.restore(root, item)
        assertEquals("note", File(root, "folder/note.md").readText())
        assertTrue(Vault.trashed(root).isEmpty())
        root.deleteRecursively()
    }

    // Formatting toolbar and Ctrl+B/Ctrl+I

    @Test fun togglesMarkersAroundTheSelection() {
        assertEquals(io.github.hematite.core.Formatted("a **word** b", 4, 8), io.github.hematite.core.Formatting.toggle("a word b", 2, 6, "**"))
        assertEquals(io.github.hematite.core.Formatted("a word b", 2, 6), io.github.hematite.core.Formatting.toggle("a **word** b", 4, 8, "**"))
        assertEquals(io.github.hematite.core.Formatted("a word b", 2, 6), io.github.hematite.core.Formatting.toggle("a **word** b", 2, 10, "**"))
        assertEquals(io.github.hematite.core.Formatted("**", 1, 1).copy(text = "**"), io.github.hematite.core.Formatting.toggle("", 0, 0, "*"))
    }

    @Test fun togglesTaskAndBulletPrefixes() {
        val formatting = io.github.hematite.core.Formatting
        assertEquals("- [ ] buy milk", formatting.toggleLinePrefix("buy milk", 3, 3, task = true).text)
        assertEquals("buy milk", formatting.toggleLinePrefix("- [x] buy milk", 3, 3, task = true).text)
        assertEquals("  - [ ] nested", formatting.toggleLinePrefix("  - nested", 5, 5, task = true).text)
        assertEquals("- one\n- two", formatting.toggleLinePrefix("one\ntwo", 0, 7, task = false).text)
        assertEquals("- done", formatting.toggleLinePrefix("- [X] done", 0, 0, task = false).text)
    }
}

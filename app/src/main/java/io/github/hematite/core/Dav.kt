package io.github.hematite.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element

/** A sync problem with a message suitable for the user. */
class SyncException(message: String) : Exception(message)

class DavResponse(val status: Int, val body: ByteArray, val etag: String?, val lockToken: String?)

/** A file on the server: its strong ETag and, when the server reports it, its modification time in epoch milliseconds. */
data class Remote(val modified: Long?, val etag: String)

/** Small WebDAV transport, compatible with the desktop app's. */
class Dav(var url: String, val username: String, val password: String, var lockWrites: Boolean = false) {
    fun copy(url: String = this.url) = Dav(url, username, password, lockWrites)

    fun request(method: String, path: String, headers: List<Pair<String, String>> = emptyList(), body: ByteArray? = null): DavResponse {
        val builder = try { Request.Builder().url(url + encode(path)) } catch (error: IllegalArgumentException) {
            throw SyncException("Could not reach WebDAV server: ${error.message}")
        }
        builder.header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
        for ((name, value) in headers) builder.header(name, value)
        val type = headers.firstOrNull { it.first.equals("Content-Type", true) }?.second?.toMediaTypeOrNull()
        builder.method(method, body?.toRequestBody(type))
        try {
            client.newCall(builder.build()).execute().use { response ->
                val length = response.body.contentLength()
                if (length > MAX_FILE) throw SyncException("Could not reach WebDAV server: file is larger than 256 MB")
                val bytes = response.body.source().use { source ->
                    if (!source.request(MAX_FILE + 1) || source.buffer.size <= MAX_FILE) source.readByteArray()
                    else throw SyncException("Could not reach WebDAV server: file is larger than 256 MB")
                }
                return DavResponse(response.code, bytes, response.headers("ETag").lastOrNull()?.trim(),
                    response.headers("Lock-Token").lastOrNull()?.trim())
            }
        } catch (error: IOException) {
            throw SyncException("Could not reach WebDAV server: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun checked(method: String, path: String, headers: List<Pair<String, String>> = emptyList(), body: ByteArray? = null): DavResponse {
        val response = request(method, path, headers, body)
        if (response.status !in 200..299) throw SyncException(when (response.status) {
            401, 403 -> "WebDAV login or access was denied. Check your credentials and server URL."
            412 -> "Remote file changed during sync: $path. It will be checked again."
            else -> "WebDAV $method failed with HTTP ${response.status} for $path"
        })
        return response
    }

    /** The file's contents and strong ETag. */
    fun get(path: String): Pair<ByteArray, String> {
        val response = checked("GET", path)
        val etag = response.etag?.takeIf { it.startsWith('"') && it.endsWith('"') }
            ?: throw SyncException("The server must provide strong ETags for safe syncing")
        return response.body to etag
    }

    /** Writes `bytes` if the remote file still has `etag` (or doesn't exist when null), verifies it, and returns its new ETag. */
    fun put(path: String, bytes: ByteArray, etag: String?): String {
        val pieces = path.split('/')
        val parent = StringBuilder()
        for (piece in pieces.dropLast(1)) {
            parent.append(piece).append('/')
            val response = request("MKCOL", parent.toString())
            if (response.status !in 200..299 && response.status != 405) {
                throw SyncException("Could not create remote folder: HTTP ${response.status}")
            }
        }
        if (lockWrites) lockedWrite("PUT", path, bytes, etag)
        else checked("PUT", path, listOf(if (etag != null) "If-Match" to etag else "If-None-Match" to "*"), bytes)
        val (actual, tag) = get(path)
        if (!actual.contentEquals(bytes)) throw SyncException("Remote verification failed for $path")
        return tag
    }

    private fun lockedWrite(method: String, path: String, bytes: ByteArray?, expected: String?) {
        val lock = checked("LOCK", path, listOf("Content-Type" to "application/xml", "Timeout" to "Second-300", "Depth" to "0"), LOCK_BODY)
        val token = lock.lockToken ?: throw SyncException("Server did not provide a lock token")
        val condition = "($token)"
        val operation = runCatching {
            val (_, actual) = get(path)
            if ((expected != null && (lock.status != 200 || expected != actual)) || (expected == null && lock.status != 201)) {
                throw SyncException("Remote file changed during sync: $path. Retrying safely.")
            }
            checked(method, path, listOf("If" to condition), bytes)
        }
        if (operation.isFailure && lock.status == 201) runCatching { request("DELETE", path, listOf("If" to condition)) }
        val unlock = runCatching { checked("UNLOCK", path, listOf("Lock-Token" to token)) }
        operation.getOrThrow()
        unlock.getOrThrow()
    }

    fun delete(path: String, etag: String) {
        if (lockWrites) lockedWrite("DELETE", path, null, etag)
        else checked("DELETE", path, listOf("If-Match" to etag))
    }

    /** Checks that the server rejects stale writes, through conditional requests or else enforced locks. */
    fun probe() {
        val path = ".hematite-probe-${UUID.randomUUID()}.txt"
        val result = runCatching {
            checked("PUT", path, body = "first".toByteArray())
            val stale = request("PUT", path, listOf("If-Match" to "\"hematite-stale-version\""), "rejected".toByteArray()).status
            val duplicate = request("PUT", path, listOf("If-None-Match" to "*"), "rejected".toByteArray()).status
            val deletion = request("DELETE", path, listOf("If-Match" to "\"hematite-stale-version\"")).status
            if (stale == 412 && duplicate == 412 && deletion == 412) {
                lockWrites = false
                return@runCatching
            }
            checked("PUT", path, body = "first".toByteArray())
            val lock = checked("LOCK", path, listOf("Content-Type" to "application/xml", "Timeout" to "Second-300"), LOCK_BODY)
            val token = lock.lockToken ?: throw SyncException("Server cannot safely lock files")
            val verify = runCatching {
                if (request("PUT", path, body = "rejected".toByteArray()).status != 423 || request("DELETE", path).status != 423) {
                    throw SyncException("Server does not enforce write locks; safe sync is unavailable")
                }
                checked("PUT", path, listOf("If" to "($token)"), "second".toByteArray())
                if (!get(path).first.contentEquals("second".toByteArray())) throw SyncException("Server write verification failed")
            }
            val unlock = runCatching { checked("UNLOCK", path, listOf("Lock-Token" to token)) }
            verify.getOrThrow()
            unlock.getOrThrow()
            lockWrites = true
        }
        val cleanup = runCatching { request("DELETE", path) }
        result.getOrThrow()
        if (cleanup.isFailure) throw SyncException("Could not clean up the server capability test")
    }

    /** Every file in the vault. Never returns a partial listing. */
    fun list(): TreeMap<String, Remote> = try { listWithDepth(true) } catch (_: Exception) { listWithDepth(false) }

    internal fun listWithDepth(recursive: Boolean): TreeMap<String, Remote> {
        val pending = ArrayList<String>().apply { add("") }
        val seen = HashSet<String>()
        val files = TreeMap<String, Remote>()
        val base = splitUrl(url) ?: throw SyncException("Enter a valid WebDAV URL")
        val requestedRoot = (decode(base.path) ?: base.path).trimEnd('/') + "/"
        var wireBase: String? = null
        while (pending.isNotEmpty()) {
            val folder = pending.removeAt(pending.lastIndex)
            if (!seen.add(folder)) continue
            val response = checked("PROPFIND", folder,
                listOf("Depth" to if (recursive) "infinity" else "1", "Content-Type" to "application/xml"), PROPFIND_BODY)
            val items = parseListing(response.body)
            if (folder.isEmpty()) {
                // A reverse proxy may strip /vault/ without rewriting DAV hrefs.
                // Learn the root collection's wire path from its own response.
                val root = items.filter { it.collection }.minByOrNull { item -> item.href.count { it == '/' } }
                    ?: throw SyncException("Server listing did not include the vault collection")
                val path = if ("://" in root.href) splitUrl(root.href)?.path ?: throw SyncException("Invalid vault collection URL") else root.href
                val decoded = decode(path) ?: throw SyncException("Invalid vault collection URL")
                val rootPath = decoded.trimEnd('/') + "/"
                if (!requestedRoot.endsWith(rootPath)) throw SyncException("Incomplete WebDAV listing: root collection is missing")
                wireBase = rootPath
            }
            val wire = wireBase!!
            var foundSelf = false
            for (item in items) {
                val href = if ("://" in item.href) {
                    val parts = splitUrl(item.href) ?: throw SyncException("Invalid WebDAV file path")
                    if (parts.host != base.host || parts.port != base.port || parts.scheme != base.scheme) {
                        throw SyncException("Server returned an external file URL")
                    }
                    parts.path
                } else item.href
                val decoded = decode(href) ?: throw SyncException("Invalid WebDAV file path")
                if (decoded.trimEnd('/') == wire.trimEnd('/')) {
                    foundSelf = foundSelf || folder.isEmpty()
                    continue
                }
                // Some proxies strip a URL prefix without rewriting response hrefs.
                if (!decoded.startsWith(wire)) throw SyncException("Server returned a file outside the vault")
                val path = decoded.removePrefix(wire).trimEnd('/')
                if (path == folder.trimEnd('/')) {
                    foundSelf = true
                    continue
                }
                if (!safePath(path)) throw SyncException("Server returned an unsafe file path")
                if (path.split('/').any { it.startsWith('.') }) continue
                if (item.collection) {
                    if (!recursive) pending.add("$path/")
                } else {
                    if (!item.etag.startsWith('"') || !item.etag.endsWith('"') || item.etag.length < 2 || item.etag.any { it == '\n' || it == '\r' }) {
                        throw SyncException("The server must provide strong ETags for safe syncing")
                    }
                    files[path] = Remote(parseModified(item.modified), item.etag)
                }
            }
            if (!foundSelf) throw SyncException("Incomplete WebDAV listing: collection response is missing")
            if (files.size + seen.size + pending.size > 100_000) throw SyncException("Vault exceeds the sync file limit")
        }
        return files
    }

    internal class Item(val href: String, val etag: String, val modified: String, val collection: Boolean)
    private class UrlParts(val scheme: String, val host: String, val port: Int, val path: String)

    companion object {
        private const val MAX_FILE = 268_435_456L
        private val LOCK_BODY = "<d:lockinfo xmlns:d=\"DAV:\"><d:lockscope><d:exclusive/></d:lockscope><d:locktype><d:write/></d:locktype></d:lockinfo>".toByteArray()
        private val PROPFIND_BODY = "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getetag/><d:getlastmodified/></d:prop></d:propfind>".toByteArray()

        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            // Writes are conditional; never replay one on a new connection behind the engine's back.
            .retryOnConnectionFailure(false)
            .build()

        fun normalizeUrl(value: String): String {
            val trimmed = value.trim()
            val full = if ("://" in trimmed) trimmed else "https://$trimmed"
            val uri = try { java.net.URI(full) } catch (_: Exception) { throw SyncException("Enter a valid WebDAV URL") }
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host == null || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null) {
                throw SyncException("Use an HTTP or HTTPS URL, without embedded credentials, query, or fragment")
            }
            return full.trimEnd('/') + "/"
        }

        fun hash(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun encode(path: String): String = buildString {
            for (byte in path.toByteArray()) {
                val value = byte.toInt() and 0xff
                val char = value.toChar()
                if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-._~/") append(char) else append("%%%02X".format(value))
            }
        }

        /** Percent-decodes a URL path as UTF-8, or null when it is malformed. */
        fun decode(value: String): String? {
            val bytes = java.io.ByteArrayOutputStream()
            var index = 0
            while (index < value.length) {
                val char = value[index]
                if (char == '%') {
                    if (index + 3 > value.length) return null
                    val digits = value.substring(index + 1, index + 3)
                    if (!digits.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
                    val byte = digits.toInt(16)
                    bytes.write(byte)
                    index += 3
                } else {
                    bytes.write(char.toString().toByteArray())
                    index++
                }
            }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            return try { decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString().takeIf { '\u0000' !in it } } catch (_: CharacterCodingException) { null }
        }

        fun safePath(path: String): Boolean = path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && '\u0000' !in path &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

        private fun splitUrl(value: String): UrlParts? {
            val schemeEnd = value.indexOf("://").takeIf { it > 0 } ?: return null
            val rest = value.substring(schemeEnd + 3)
            val slash = rest.indexOf('/').let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, slash).substringAfterLast('@')
            val path = rest.substring(slash).substringBefore('?').substringBefore('#')
            val (host, port) = if (authority.startsWith('[')) {
                val close = authority.indexOf(']').takeIf { it > 0 } ?: return null
                authority.substring(0, close + 1) to authority.substring(close + 1).removePrefix(":")
            } else authority.substringBefore(':') to (if (':' in authority) authority.substringAfter(':') else "")
            return UrlParts(value.substring(0, schemeEnd).lowercase(), host.lowercase(), if (port.isEmpty()) -1 else port.toIntOrNull() ?: return null, path)
        }

        /** WebDAV getlastmodified uses the HTTP IMF-fixdate format, in GMT. */
        fun parseModified(value: String): Long? {
            val parts = value.trim().split(Regex("\\s+"))
            if (parts.size != 6 || parts[5] != "GMT") return null
            val month = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec").indexOf(parts[2]) + 1
            if (month == 0) return null
            val time = parts[4].split(':')
            if (time.size != 3) return null
            return try {
                LocalDateTime.of(parts[3].toInt(), month, parts[1].toInt(), time[0].toInt(), time[1].toInt(), time[2].toInt())
                    .toEpochSecond(ZoneOffset.UTC) * 1000
            } catch (_: Exception) { null }
        }

        internal fun parseListing(xml: ByteArray): List<Item> {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }
            val document = try { factory.newDocumentBuilder().parse(xml.inputStream()) } catch (error: Exception) {
                throw SyncException("This address is not a WebDAV collection")
            }
            if (document.getElementsByTagNameNS("*", "multistatus").length == 0) throw SyncException("This address is not a WebDAV collection")
            val responses = document.getElementsByTagNameNS("*", "response")
            return (0 until responses.length).map { index ->
                val response = responses.item(index) as Element
                fun text(name: String) = response.getElementsByTagNameNS("*", name).let { nodes ->
                    (0 until nodes.length).joinToString("") { nodes.item(it).textContent.trim() }
                }
                val statuses = response.getElementsByTagNameNS("*", "status")
                if ((0 until statuses.length).none { " 200 " in statuses.item(it).textContent.trim() }) {
                    throw SyncException("Incomplete WebDAV listing; no local files were removed")
                }
                Item(text("href"), text("getetag"), text("getlastmodified"), response.getElementsByTagNameNS("*", "collection").length > 0)
            }
        }
    }
}

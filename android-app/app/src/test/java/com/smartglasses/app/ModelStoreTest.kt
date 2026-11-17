package com.smartglasses.app

import com.smartglasses.app.ai.ImportSource
import com.smartglasses.app.ai.ModelSpec
import com.smartglasses.app.ai.ModelStatus
import com.smartglasses.app.ai.ModelStore
import com.smartglasses.app.ai.VerifiedCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/** ModelStore state machine against throwaway directories standing in for app storage. */
class ModelStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val content = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    private val spec = ModelSpec("m", "Test model", "1", "model.litertlm", content.size.toLong(), sha, null, null)

    private class MapCache : VerifiedCache {
        val map = HashMap<String, String>()
        var gets = 0
        override fun get(key: String) = map[key].also { gets++ }
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.keys.removeIf { it.startsWith(key.substringBeforeLast(':').substringBeforeLast(':')) } }
    }

    private lateinit var internal: File
    private lateinit var dropIn: File
    private val cache = MapCache()
    private var free = Long.MAX_VALUE

    private fun store() = ModelStore(spec, internal, dropIn, cache, freeSpace = { free }, io = Dispatchers.Unconfined)

    private fun setup() { internal = tmp.newFolder("internal"); dropIn = tmp.newFolder("dropin") }

    @Test
    fun missingWhenNothingOnDisk() = runBlocking {
        setup()
        assertEquals(ModelStatus.Missing(), store().refresh())
    }

    @Test
    fun importVerifiesAndBecomesReady() = runBlocking {
        setup()
        val s = store()
        val r = s.import(ImportSource("model.litertlm", content.size.toLong()) { ByteArrayInputStream(content) })
        assertTrue(r is ModelStatus.Ready)
        r as ModelStatus.Ready
        assertEquals(sha, r.sha256)
        assertFalse(r.inPlace)
        assertTrue(File(internal, "model.litertlm").exists())
        assertFalse(File(internal, "model.litertlm.importing").exists())
    }

    @Test
    fun importOfWrongFileIsRejectedAndLeavesNothing() = runBlocking {
        setup()
        val wrong = content.copyOf().also { it[0] = 42 }
        val r = store().import(ImportSource("photo.jpg", wrong.size.toLong()) { ByteArrayInputStream(wrong) })
        assertTrue(r is ModelStatus.Missing && r.message!!.contains("SHA-256"))
        assertEquals(0, internal.listFiles()!!.size)
    }

    @Test
    fun importRejectsWrongSizeBeforeCopying() = runBlocking {
        setup()
        var opened = false
        val r = store().import(ImportSource("small.bin", 10) { opened = true; ByteArrayInputStream(ByteArray(10)) })
        assertTrue(r is ModelStatus.Missing && r.message!!.contains("right file"))
        assertFalse(opened)
    }

    @Test
    fun importNeedsFreeSpace() = runBlocking {
        setup()
        free = 1000
        val r = store().import(ImportSource("model.litertlm", null) { ByteArrayInputStream(content) })
        assertTrue(r is ModelStatus.Missing && r.message!!.startsWith("Not enough free storage"))
    }

    @Test
    fun truncatedStreamIsReportedNotAccepted() = runBlocking {
        setup()
        val r = store().import(ImportSource("model.litertlm", null) { ByteArrayInputStream(content, 0, 1000) })
        assertTrue(r is ModelStatus.Missing && r.message!!.contains("stopped early"))
    }

    @Test
    fun partialInternalFileIsCorrupt() = runBlocking {
        setup()
        File(internal, "model.litertlm").writeBytes(content.copyOf(1000))
        val r = store().refresh()
        assertTrue(r is ModelStatus.Corrupt && r.reason.contains("incomplete"))
    }

    @Test
    fun sameSizeButWrongBytesIsCorrupt() = runBlocking {
        setup()
        File(internal, "model.litertlm").writeBytes(content.copyOf().also { it[5] = 0 })
        assertTrue(store().refresh() is ModelStatus.Corrupt)
    }

    @Test
    fun leftoverImportTempIsCleanedUp() = runBlocking {
        setup()
        File(internal, "model.litertlm.importing").writeBytes(ByteArray(10))
        assertEquals(ModelStatus.Missing(), store().refresh())
        assertFalse(File(internal, "model.litertlm.importing").exists())
    }

    @Test
    fun dropInIsUsedInPlaceAndHashedOnlyOnce() = runBlocking {
        setup()
        File(dropIn, "model.litertlm").writeBytes(content)
        val first = store().refresh() as ModelStatus.Ready
        assertTrue(first.inPlace)
        assertEquals(File(dropIn, "model.litertlm").path, first.path)
        assertEquals(1, cache.map.size)
        // A second launch finds the cached verification and doesn't need to hash again.
        val again = store().refresh()
        assertEquals(first, again)
        assertEquals(0, internal.listFiles()!!.size)  // nothing copied
    }

    @Test
    fun changedFileIsReverified() = runBlocking {
        setup()
        val f = File(dropIn, "model.litertlm")
        f.writeBytes(content)
        assertTrue(store().refresh() is ModelStatus.Ready)
        f.writeBytes(content.copyOf().also { it[7] = 1 })
        f.setLastModified(f.lastModified() + 5000)  // a new file shows up with a new mtime
        assertTrue(store().refresh() is ModelStatus.Corrupt)
    }

    @Test
    fun deleteRemovesEverything() = runBlocking {
        setup()
        val s = store()
        s.import(ImportSource("model.litertlm", null) { ByteArrayInputStream(content) })
        File(dropIn, "model.litertlm").writeBytes(content)
        s.downloadTemp.writeBytes(ByteArray(5))
        assertEquals(ModelStatus.Missing(), s.delete())
        assertEquals(0L, s.storageUsedBytes())
        assertTrue(cache.map.isEmpty())
    }

    @Test
    fun adoptDownloadVerifies() = runBlocking {
        setup()
        val s = store()
        s.downloadTemp.writeBytes(content.copyOf(100))
        assertTrue(s.adoptDownload() is ModelStatus.Missing)  // incomplete: kept for resume
        assertTrue(s.downloadTemp.exists())
        s.downloadTemp.writeBytes(content)
        assertTrue(s.adoptDownload() is ModelStatus.Ready)
        assertFalse(s.downloadTemp.exists())
    }

    @Test
    fun manifestParses() {
        val json = File("src/main/assets/model-manifest.json").readText()
        val m = ModelSpec.parseManifest(json).single()
        assertEquals("gemma-4-E2B-it.litertlm", m.fileName)
        assertEquals(2_588_147_712L, m.sizeBytes)
        assertEquals("181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", m.sha256)
    }
}

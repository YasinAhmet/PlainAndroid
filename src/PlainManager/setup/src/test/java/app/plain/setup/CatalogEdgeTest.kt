package app.plain.setup

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Locale

/** Malformed, older or odd apps.json files, install queue order, download names and network error texts. */
class CatalogEdgeTest {

    private fun app(pkg: String, code: Long, url: String = "https://x/$pkg.apk", file: String = "$pkg.apk") =
        Entry(pkg, pkg, pkg, code, "v$code", file, url, 100, "", 26, "")

    @Test fun jsonNullsAreMissingNotTheWordNull() {
        val e = Catalog.parse("""
            {"apps":[{"key":"maps","name":"Plain Maps","package":"app.plain.maps","versionCode":14,"versionName":null,
              "file":"PlainMaps.apk","url":"https://x/m.apk","sha256":null,"summary":null,"summary_tr":null,"merged_into":null}]}
        """.trimIndent()).single()
        assertEquals("", e.mergedInto) // "null" made the app a hidden "former app"
        assertEquals("14", e.versionName)
        assertEquals("", e.sha256)
        assertEquals("", e.summaryTr)
    }

    @Test fun entriesWithoutPackageOrVersionAreSkipped() {
        val list = Catalog.parse("""
            {"apps":[{"package":null,"versionCode":3},{"package":"  ","versionCode":3},{"package":"a.b"},
                     {"package":"a.c","versionCode":-1},{"package":"a.d","versionCode":"7"},null,5,"x",
                     {"package":"a.e","versionCode":9007199254740993,"newField":{"x":[1,2]},"history":"none"}]}
        """.trimIndent())
        assertEquals(listOf("a.d", "a.e"), list.map { it.pkg })
        assertEquals(7L, list[0].versionCode)          // a number written as a string still counts
        assertEquals(9007199254740993L, list[1].versionCode) // above Int and above a double's exact range
        assertTrue(list[1].history.isEmpty())           // "history" of the wrong type is ignored
    }

    @Test fun oddTopLevelShapes() {
        assertTrue(Catalog.parse("{}").isEmpty())
        assertTrue(Catalog.parse("{\"apps\":\"x\"}").isEmpty())
        assertTrue(Catalog.parse("﻿{\"apps\":[]}").isEmpty()) // byte order mark
        try { Catalog.parse("<html>Sign in to the Wi-Fi</html>"); throw AssertionError("should throw") }
        catch (e: JSONException) { assertEquals("The online app list is damaged", Net.describe(e)) }
    }

    @Test fun historyOddItems() {
        val e = Catalog.parse("""
            {"apps":[{"package":"a.b","versionCode":5,"url":"https://x/5","history":[null,3,{},{"versionCode":4},
              {"versionCode":2,"url":"https://x/2"},{"versionCode":4,"url":"https://x/4"},{"versionCode":9,"url":"https://x/9"}]}]}
        """.trimIndent()).single()
        assertEquals(listOf(9L, 4L, 2L), e.history.map { it.versionCode })
        // a "history" release newer than the installed one is never offered as an older version
        assertEquals(listOf(4L, 2L), Catalog.olderVersions(Offer(e, Source.ONLINE), 5, 35).map { it.versionCode })
        assertTrue(Catalog.olderVersions(Offer(e.copy(history = emptyList()), Source.ONLINE), 5, 35).isEmpty())
    }

    @Test fun onlineReleaseWithoutLinkKeepsTheBundledCopy() {
        val bundled = listOf(app("a.b", 3))
        val m = Catalog.merge(bundled, listOf(app("a.b", 4, url = ""), app("a.c", 1, url = "")))
        assertEquals(listOf("a.b"), m.map { it.entry.pkg }) // a.c can't be downloaded: not offered
        assertEquals(Source.BUNDLED, m[0].source)
        assertEquals(3L, m[0].entry.versionCode)
    }

    @Test fun statesWithLongVersionCodes() {
        val o = Offer(app("a.b", 3_000_000_000L), Source.ONLINE)
        assertEquals(Catalog.State.UPDATE, Catalog.state(o, 2_999_999_999L, 30))
        assertEquals(Catalog.State.NEWER_INSTALLED, Catalog.state(o, Long.MAX_VALUE, 30))
        assertEquals(Catalog.State.UP_TO_DATE, Catalog.state(o, 3_000_000_000L, 30))
    }

    @Test fun queueHasNoDuplicatesAndSetupLast() {
        val a = Offer(app("a", 1), Source.ONLINE)
        val b = Offer(app("b", 1), Source.ONLINE)
        val self = Offer(app("app.plain.setup", 9), Source.ONLINE)
        val q = Catalog.queueOrder(listOf(self, a), busyPkg = "b", add = listOf(b, a, Offer(app("c", 1), Source.BUNDLED)), self = "app.plain.setup")
        assertEquals(listOf("a", "c", "app.plain.setup"), q.map { it.entry.pkg })
        assertTrue(Catalog.queueOrder(emptyList(), null, emptyList(), "x").isEmpty())
    }

    @Test fun downloadNameStaysInsideTheFolder() {
        assertEquals("PlainMaps.apk", Catalog.cacheName(app("a.b", 1, file = "PlainMaps.apk")))
        assertEquals("evil.apk", Catalog.cacheName(app("a.b", 1, file = "../../files/evil.apk")))
        assertEquals("x.apk", Catalog.cacheName(app("a.b", 1, file = "..\\x.apk")))
        assertEquals("a.b.apk", Catalog.cacheName(app("a.b", 1, file = "")))
        assertEquals("a.b.apk", Catalog.cacheName(app("a.b", 1, file = "..")))
        assertEquals("a.b.apk", Catalog.cacheName(app("a.b", 1, file = "dir/")))
    }

    @Test fun hexIsTheSameInEveryLocale() {
        val digest = MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())
        val want = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val saved = Locale.getDefault()
        try {
            for (l in listOf(Locale("tr", "TR"), Locale("ar", "EG"), Locale("hi", "IN"), Locale.US)) {
                Locale.setDefault(l)
                assertEquals(l.toString(), want, Net.hex(digest))
            }
        } finally { Locale.setDefault(saved) }
        assertEquals("00ff7f80", Net.hex(byteArrayOf(0, -1, 127, -128)))
    }

    @Test fun networkErrorsReadAsPlainWords() {
        assertEquals("No internet connection", Net.describe(UnknownHostException("Unable to resolve host \"raw.githubusercontent.com\"")))
        assertEquals("The connection timed out", Net.describe(SocketTimeoutException("timeout")))
        assertEquals("Not enough storage", Net.describe(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals("HTTP 404", Net.describe(IOException("HTTP 404")))
        assertEquals("The app's file is missing", Net.describe(java.io.FileNotFoundException("apks/PlainAnkara.apk")))
        assertEquals("IOException", Net.describe(IOException()))
        assertFalse(Net.describe(javax.net.ssl.SSLHandshakeException("Chain validation failed")).contains("Chain"))
    }

    @Test fun bigCatalogParsesQuickly() {
        val sb = StringBuilder("{\"apps\":[")
        for (i in 0 until 10_000) {
            if (i > 0) sb.append(',')
            sb.append("{\"package\":\"app.n$i\",\"versionCode\":$i,\"url\":\"https://x/$i\",\"history\":[")
            for (j in 0 until 5) { if (j > 0) sb.append(','); sb.append("{\"versionCode\":$j,\"url\":\"https://x/$i/$j\"}") }
            sb.append("]}")
        }
        sb.append("]}")
        val t0 = System.nanoTime()
        val list = Catalog.parse(sb.toString())
        val offers = Catalog.merge(emptyList(), list)
        val ups = Catalog.updates(offers, { 0L }, 35)
        assertEquals(10_000, list.size)
        assertEquals(9_999, ups.size) // app.n0 has versionCode 0 = installed
        assertTrue("too slow", System.nanoTime() - t0 < 5_000_000_000L)
    }
}

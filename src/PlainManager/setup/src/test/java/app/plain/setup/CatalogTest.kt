package app.plain.setup

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogTest {
    private val json = """
        {"apps":[
          {"key":"music","name":"Plain Music","package":"app.localmusic","versionCode":5,"versionName":"1.4",
           "file":"PlainMusic.apk","url":"https://x/PlainMusic.apk","size":265160,"sha256":"ABC","minSdk":30,"summary":"Offline music"},
          {"key":"home","name":"Plain Home","package":"app.plain.home","versionCode":1,"versionName":"1.0","file":"PlainHome.apk","minSdk":29},
          {"key":"setup","name":"Plain Setup","package":"app.plain.setup","versionCode":3,"versionName":"1.2","file":"PlainSetup.apk"},
          {"name":"broken"}
        ]}
    """.trimIndent()

    @Test fun parsesAndSkipsBrokenEntries() {
        val e = Catalog.parse(json)
        assertEquals(listOf("app.localmusic", "app.plain.home", "app.plain.setup"), e.map { it.pkg })
        assertEquals("abc", e[0].sha256)
        assertEquals(30, e[0].minSdk)
        assertEquals("", e[1].summary)
    }

    @Test fun mergePrefersBundledUnlessOnlineIsNewerAndOffersSetupOnlyOnline() {
        val bundled = Catalog.parse(json)
        val newer = bundled[0].copy(versionCode = 6, versionName = "1.5")
        val same = bundled[1]
        val extra = bundled[1].copy(pkg = "app.plain.new", name = "New")
        val m = Catalog.merge(bundled, listOf(newer, same, extra, bundled[2]))
        assertEquals(listOf("app.localmusic", "app.plain.home", "app.plain.new", "app.plain.setup"), m.map { it.entry.pkg })
        assertEquals(listOf(Source.ONLINE, Source.BUNDLED, Source.ONLINE, Source.ONLINE), m.map { it.source })
        assertEquals(2, Catalog.merge(bundled, null).size) // offline: no Setup row
    }

    @Test fun states() {
        val o = Offer(Catalog.parse(json)[0], Source.BUNDLED)
        assertEquals(Catalog.State.TOO_OLD_ANDROID, Catalog.state(o, null, 29))
        assertEquals(Catalog.State.NOT_INSTALLED, Catalog.state(o, null, 35))
        assertEquals(Catalog.State.UPDATE, Catalog.state(o, 4, 35))
        assertEquals(Catalog.State.UP_TO_DATE, Catalog.state(o, 5, 35))
        assertEquals(Catalog.State.NEWER_INSTALLED, Catalog.state(o, 9, 35))
        assertEquals("Android 11", Catalog.androidName(30))
        assertEquals("Android 16", Catalog.androidName(36))
        assertEquals("259 KB", Catalog.sizeText(265160))
    }

    @Test fun updatesOnlyIncludesInstalledAppsWithANewerVersion() {
        val offers = Catalog.parse(json).map { Offer(it, Source.ONLINE) }
        val installed = mapOf("app.localmusic" to 4L, "app.plain.home" to 1L) // setup not installed
        assertEquals(listOf("app.localmusic"), Catalog.updates(offers, { installed[it] }, 35).map { it.entry.pkg })
        assertEquals(emptyList<Offer>(), Catalog.updates(offers, { installed[it] }, 29)) // music needs Android 11
        assertEquals(emptyList<Offer>(), Catalog.updates(offers, { installed[it] }, 35, held = setOf("app.localmusic")))
    }

    private val withHistory = """
        {"apps":[{"key":"music","name":"Plain Music","package":"app.localmusic","versionCode":7,"versionName":"1.6",
          "file":"PlainMusic.apk","url":"https://x/v1.6/PlainMusic.apk","minSdk":30,"summary":"S",
          "history":[{"versionCode":5,"versionName":"1.4","url":"https://x/v1.4/PlainMusic.apk","sha256":"AA","minSdk":29},
                     {"versionCode":6,"versionName":"1.5","url":"https://x/v1.5/PlainMusic.apk","minSdk":36},
                     {"versionCode":4,"versionName":"no url"}]}]}
    """.trimIndent()

    @Test fun historyParsesNewestFirstAndInheritsFromTheApp() {
        val h = Catalog.parse(withHistory)[0].history
        assertEquals(listOf("1.5", "1.4"), h.map { it.versionName }) // sorted; the entry without a url is dropped
        assertEquals("app.localmusic", h[1].pkg)
        assertEquals("PlainMusic.apk", h[1].file)
        assertEquals("aa", h[1].sha256)
        assertEquals(29, h[1].minSdk)
    }

    @Test fun olderVersionsAreBelowTheInstalledOneAndRunnable() {
        val o = Offer(Catalog.parse(withHistory)[0], Source.ONLINE)
        assertEquals(listOf("1.4"), Catalog.olderVersions(o, 7, 35).map { it.versionName }) // 1.5 needs a newer Android
        assertEquals(listOf("1.5", "1.4"), Catalog.olderVersions(o, 7, 36).map { it.versionName })
        assertEquals(listOf("1.4"), Catalog.olderVersions(o, 6, 36).map { it.versionName })
        assertEquals(emptyList<Entry>(), Catalog.olderVersions(o, null, 36)) // not installed
    }

    @Test fun mergeKeepsOnlineHistoryWhenTheBundledCopyIsAsNew() {
        val online = Catalog.parse(withHistory)
        val bundled = listOf(online[0].copy(history = emptyList()))
        val m = Catalog.merge(bundled, online)
        assertEquals(Source.BUNDLED, m[0].source)
        assertEquals(2, m[0].entry.history.size)
    }
}

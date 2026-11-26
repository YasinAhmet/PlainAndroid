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
}

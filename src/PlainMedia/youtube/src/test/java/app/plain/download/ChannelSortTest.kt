package app.plain.download

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sort chips of a channel's Videos tab (Latest / Popular / Oldest), as YouTube sent them on 2026-11-28. */
class ChannelSortTest {
    private fun vm(label: String, tok: String) =
        """{"chipViewModel":{"text":"$label","selected":false,"tapCommand":{"innertubeCommand":{"continuationCommand":{"token":"$tok","request":"CONTINUATION_REQUEST_TYPE_BROWSE"}}}}}"""
    private fun old(label: String, tok: String) =
        """{"chipCloudChipRenderer":{"text":{"simpleText":"$label"},"navigationEndpoint":{"continuationCommand":{"token":"$tok"}}}}"""
    private fun page(vararg chips: String) =
        JSONObject("""{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"title":"Videos","selected":true,"content":{"richGridRenderer":{"header":{"chipBarViewModel":{"chips":[${chips.joinToString(",")}]}},"contents":[]}}}}]}}}""")

    @Test fun newLayoutEnglish() {
        val m = Browse.sortChips(page(vm("Latest", "L"), vm("Popular", "P"), vm("Oldest", "O")))
        assertEquals(mapOf(Browse.Sort.LATEST to "L", Browse.Sort.POPULAR to "P", Browse.Sort.OLDEST to "O"), m)
    }

    @Test fun turkishLabelsAndOldLayout() {
        val m = Browse.sortChips(page(old("En son", "L"), old("Popüler", "P"), old("En eski", "O")))
        assertEquals("P", m[Browse.Sort.POPULAR]); assertEquals("O", m[Browse.Sort.OLDEST]); assertEquals("L", m[Browse.Sort.LATEST])
    }

    @Test fun unknownLanguageFallsBackToPosition() {
        val m = Browse.sortChips(page(vm("Neueste", "L"), vm("Beliebt", "P"), vm("Älteste", "O")))
        assertEquals("P", m[Browse.Sort.POPULAR])
    }

    @Test fun noChipsOrNoTokens() {
        assertTrue(Browse.sortChips(JSONObject("{}")).isEmpty())
        assertTrue(Browse.sortChips(JSONObject("""{"chipViewModel":{"text":"Latest"}}""")).isEmpty())
    }
}

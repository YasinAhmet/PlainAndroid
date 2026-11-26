package app.plain.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupsTest {
    @Test fun fileNameRoundTrips() {
        assertEquals("6-1.5.apk", Backups.fileName(6, "1.5"))
        assertEquals(6L to "1.5", Backups.parseName("6-1.5.apk"))
        assertEquals(18L to "2.6-beta", Backups.parseName(Backups.fileName(18, "2.6-beta")))
        assertEquals("3-1.0_x.apk", Backups.fileName(3, "1.0 x")) // unsafe characters replaced
        assertEquals("4-4.apk", Backups.fileName(4, null))
    }

    @Test fun rejectsOtherFiles() {
        assertNull(Backups.parseName("6-1.5.apk.part"))
        assertNull(Backups.parseName("x-1.5.apk"))
        assertNull(Backups.parseName("-1.5.apk"))
    }
}

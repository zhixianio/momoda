package app.momoda

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test fun newerMinor() = assertTrue(Versions.isNewer("v0.2.0", "0.1.9"))
    @Test fun olderIsNotNewer() = assertFalse(Versions.isNewer("v0.1.0", "0.2.0"))
    @Test fun sameIsNotNewer() = assertFalse(Versions.isNewer("v0.2.0", "0.2.0"))
    @Test fun releaseIsNewerThanPreview() = assertTrue(Versions.isNewer("v0.0.1", "0.0.1-m0"))
    @Test fun previewIsNotNewerThanRelease() = assertFalse(Versions.isNewer("v0.0.1-beta", "0.0.1"))
    @Test fun differentLengths() = assertTrue(Versions.isNewer("1.0.1", "1.0"))
    @Test fun numericNotLexical() = assertTrue(Versions.isNewer("0.10.0", "0.9.0"))
}

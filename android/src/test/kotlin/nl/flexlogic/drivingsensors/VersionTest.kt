package nl.flexlogic.drivingsensors

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test
    fun pluginVersionMatchesPackageJson() {
        // Gradle runs unit tests with the module dir (android/) as working dir.
        val json = File("../package.json").readText()
        val expected = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(json)!!.groupValues[1]
        assertEquals(expected, PLUGIN_VERSION)
    }

    @Test
    fun pluginVersionIsSemver() {
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(PLUGIN_VERSION))
    }
}

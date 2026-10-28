package skiff.maven

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionTest {
    private fun assertAscending(vararg versions: String) {
        for (i in 0 until versions.size - 1) {
            val lower = Version(versions[i])
            val higher = Version(versions[i + 1])
            assertTrue(lower < higher, "expected ${versions[i]} < ${versions[i + 1]}")
            assertTrue(higher > lower, "expected ${versions[i + 1]} > ${versions[i]}")
        }
    }

    @Test
    fun ordersNumericComponentsNumerically() {
        assertAscending("1.2", "1.10", "1.10.1", "2")
    }

    @Test
    fun ordersQualifiersLikeMaven() {
        assertAscending("1.0-alpha", "1.0-beta", "1.0-M1", "1.0-rc1", "1.0-SNAPSHOT", "1.0", "1.0-sp1", "1.0.1")
    }

    @Test
    fun missingComponentsCountAsZero() {
        assertEquals(0, Version("1.0").compareTo(Version("1.0.0")))
        assertEquals(0, Version("1").compareTo(Version("1.0.0.0")))
    }

    @Test
    fun releaseAliasesAreEqualToThePlainVersion() {
        for (alias in listOf("1.0-ga", "1.0-final", "1.0-release")) {
            assertEquals(0, Version(alias).compareTo(Version("1.0")), alias)
        }
    }

    @Test
    fun shortQualifiersAreNormalised() {
        assertEquals(0, Version("1.0-a1").compareTo(Version("1.0-alpha1")))
        assertEquals(0, Version("1.0-b2").compareTo(Version("1.0-beta2")))
        assertEquals(0, Version("1.0-cr1").compareTo(Version("1.0-rc1")))
    }

    @Test
    fun qualifierComparisonIsCaseInsensitive() {
        assertEquals(0, Version("1.0-RC1").compareTo(Version("1.0-rc1")))
    }

    @Test
    fun separatorsAreInterchangeable() {
        assertEquals(0, Version("1.0.1").compareTo(Version("1-0-1")))
        assertEquals(0, Version("1.0_1").compareTo(Version("1.0.1")))
    }

    @Test
    fun detectsPreReleases() {
        for (text in listOf("1.0-alpha1", "1.0-beta", "2.0.0-M3", "1.0-rc1", "1.0-SNAPSHOT", "1.9.20-eap-1", "1.0-dev", "1.0-preview")) {
            assertFalse(Version(text).isStable, text)
        }
    }

    @Test
    fun detectsStableVersions() {
        for (text in listOf("1.0", "4.12.0", "1.0.0.Final", "1.0-ga", "1.0-sp1", "2.4.21")) {
            assertTrue(Version(text).isStable, text)
        }
    }

    @Test
    fun equalityIsByText() {
        assertEquals(Version("1.0"), Version("1.0"))
        assertEquals(Version("1.0").hashCode(), Version("1.0").hashCode())
        assertFalse(Version("1.0") == Version("1.0.0"))
        assertEquals("1.0-rc1", Version("1.0-rc1").toString())
    }

    @Test
    fun picksTheMaximum() {
        val versions = listOf("1.9.0", "1.10.0", "1.10.0-rc1", "1.2.0").map(::Version)
        assertEquals("1.10.0", versions.max().text)
    }
}

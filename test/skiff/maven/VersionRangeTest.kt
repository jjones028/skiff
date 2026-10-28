package skiff.maven

import skiff.SkiffException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionRangeTest {
    private fun contains(range: String, version: String) = Version(version) in VersionRange.parse(range)

    @Test
    fun recognisesRanges() {
        assertTrue(VersionRange.isRange("[1.0,2.0)"))
        assertTrue(VersionRange.isRange("(1.0,)"))
        assertFalse(VersionRange.isRange("1.0"))
    }

    @Test
    fun inclusiveLowerAndExclusiveUpperBound() {
        assertTrue(contains("[1.0,2.0)", "1.0"))
        assertTrue(contains("[1.0,2.0)", "1.9.9"))
        assertFalse(contains("[1.0,2.0)", "2.0"))
        assertFalse(contains("[1.0,2.0)", "0.9"))
    }

    @Test
    fun exclusiveLowerAndInclusiveUpperBound() {
        assertFalse(contains("(1.0,2.0]", "1.0"))
        assertTrue(contains("(1.0,2.0]", "1.0.1"))
        assertTrue(contains("(1.0,2.0]", "2.0"))
        assertFalse(contains("(1.0,2.0]", "2.0.1"))
    }

    @Test
    fun openEndedRanges() {
        assertTrue(contains("[1.0,)", "99.0"))
        assertFalse(contains("[1.0,)", "0.1"))
        assertTrue(contains("(,2.0]", "0.1"))
        assertFalse(contains("(,2.0]", "2.1"))
    }

    @Test
    fun exactVersionRange() {
        assertTrue(contains("[1.5]", "1.5"))
        assertFalse(contains("[1.5]", "1.5.1"))
        assertFalse(contains("[1.5]", "1.4"))
    }

    @Test
    fun whitespaceIsIgnored() {
        assertTrue(contains("[ 1.0 , 2.0 )", "1.5"))
    }

    @Test
    fun onlyTheFirstIntervalOfAMultiRangeIsUsed() {
        assertTrue(contains("(,1.0],[1.2,)", "0.5"))
        assertFalse(contains("(,1.0],[1.2,)", "1.5"))
    }

    @Test
    fun rejectsUnterminatedRanges() {
        assertFailsWith<SkiffException> { VersionRange.parse("[1.0,2.0") }
    }
}

package com.atatuzun.mustafaalarm.domain

import com.atatuzun.mustafaalarm.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogTest {
    @Test
    fun newestEntry_isTheBuiltVersion() =
        assertEquals("add a Changelog entry for this version", BuildConfig.VERSION_NAME, Changelog.releases.first().version)

    @Test
    fun versionsAreUnique_andEveryReleaseListsChanges() {
        val versions = Changelog.releases.map { it.version }
        assertEquals(versions.size, versions.toSet().size)
        assertTrue(Changelog.releases.all { it.changes.isNotEmpty() && it.date.isNotBlank() })
    }
}

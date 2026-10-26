package com.nullclass.feature.settings.jw

import androidx.webkit.Profile
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class JwRefreshStoreTest {

    @Test
    fun `each timetable keeps its own profile when switching back`() = runBlocking {
        val store = JwRefreshStore(RuntimeEnvironment.getApplication())

        val mine = store.profileName("mine")
        val sibling = store.profileName("sibling")

        assertEquals(Profile.DEFAULT_PROFILE_NAME, mine)
        assertEquals("jw_timetable_sibling", sibling)
        assertNotEquals(mine, sibling)

        val reopened = JwRefreshStore(RuntimeEnvironment.getApplication())
        assertEquals(sibling, reopened.profileName("sibling"))
        assertEquals(mine, reopened.profileName("mine"))
    }
}

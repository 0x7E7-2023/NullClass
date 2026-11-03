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

    private suspend fun JwRefreshStore.multi(id: String) = profileName(id, multiProfile = true) {}

    @Test
    fun `each timetable keeps its own profile when switching back`() = runBlocking {
        val store = JwRefreshStore(RuntimeEnvironment.getApplication())

        val mine = store.multi("mine")
        val sibling = store.multi("sibling")

        assertEquals(Profile.DEFAULT_PROFILE_NAME, mine)
        assertEquals("jw_timetable_sibling", sibling)
        assertNotEquals(mine, sibling)

        val reopened = JwRefreshStore(RuntimeEnvironment.getApplication())
        assertEquals(sibling, reopened.multi("sibling"))
        assertEquals(mine, reopened.multi("mine"))
    }

    // DataStore 是进程级单例，两个用例共用一份数据：本用例收尾在 mine 名下，先跑后跑都不影响上一个用例
    @Test
    fun `old webview clears shared login only when timetable changes`() = runBlocking {
        val store = JwRefreshStore(RuntimeEnvironment.getApplication())
        var clears = 0
        suspend fun legacy(id: String) = store.profileName(id, multiProfile = false) { clears++ }

        // 头一回不知道共用登录态是谁的，也要清
        assertEquals(Profile.DEFAULT_PROFILE_NAME, legacy("sibling"))
        assertEquals(1, clears)
        legacy("sibling")
        assertEquals(1, clears)

        // WebView 升级后，默认 Profile 归最后用它登录的那张课表
        assertEquals(Profile.DEFAULT_PROFILE_NAME, store.multi("sibling"))
        assertEquals("jw_timetable_mine", store.multi("mine"))

        assertEquals(Profile.DEFAULT_PROFILE_NAME, legacy("mine"))
        assertEquals(2, clears)
    }
}

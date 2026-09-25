package com.nullclass.core.data.locale

import android.content.Context
import android.content.ContextWrapper
import com.nullclass.core.model.AppLanguage
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AppLocaleTest {

    private val app: Context get() = RuntimeEnvironment.getApplication()

    /**
     * Application.attachBaseContext 时拿到的 Context：applicationContext 还是 null
     * （Application 对象要等这一步返回后才挂上去）。12 及以下曾因此启动即崩。
     */
    private fun attachTimeContext(): Context = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context? = null
    }

    @Test
    @Config(sdk = [28])
    fun `12 及以下在 attachBaseContext 阶段不崩溃`() {
        val base = attachTimeContext()
        assertSame(base, AppLocale.wrap(base), "跟随系统时原样返回")

        AppLocale.cache(base, AppLanguage.ENGLISH)
        assertEquals(AppLanguage.ENGLISH, AppLocale.current(base))
        val wrapped = AppLocale.wrap(base)
        assertEquals("en", wrapped.resources.configuration.locales[0].language)
    }

    @Test
    @Config(sdk = [28])
    fun `12 及以下运行期切换会套到 Application 资源上`() {
        AppLocale.applyToApplication(app, AppLanguage.ENGLISH)
        assertEquals("en", app.resources.configuration.locales[0].language)
        AppLocale.applyToApplication(app, AppLanguage.SIMPLIFIED_CHINESE)
        assertEquals("zh", app.resources.configuration.locales[0].language)
    }

    @Test
    @Config(sdk = [33])
    fun `13 起不包装 Context，旧选择只迁移一次`() {
        assertSame(app, AppLocale.wrap(app, AppLanguage.ENGLISH))
        assertTrue(AppLocale.takeSystemMigration(app))
        assertFalse(AppLocale.takeSystemMigration(app))
    }
}

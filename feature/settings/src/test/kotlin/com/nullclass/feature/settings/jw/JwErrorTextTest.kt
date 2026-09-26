package com.nullclass.feature.settings.jw

import android.content.Context
import com.nullclass.feature.settings.R
import com.nullclass.importer.jw.JwErrorCode
import com.nullclass.importer.jw.JwLibraryUrl
import com.nullclass.importer.jw.JwPackageException
import com.nullclass.importer.jw.JwRemoteException
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "zh-rCN")
class JwErrorTextTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun Throwable.text(): String = toJwUiText(R.string.settings_jw_unknown_error).resolve(context)

    @Test
    fun `带原因码的异常取词条，下一层原因递归展开`() {
        val network = JwRemoteException("网络请求失败：timeout", code = JwErrorCode.NETWORK_FAILED, codeArgs = listOf("timeout"))
        val index = JwRemoteException("无法读取适配器库索引", network, JwErrorCode.INDEX_UNREADABLE, listOf(network))
        assertEquals("无法读取适配器库：网络连接失败：timeout", index.text())
    }

    @Test
    fun `用户填错库地址时按码取词条`() {
        val error = assertFailsWith<JwPackageException> { JwLibraryUrl.normalize("http://example.com") }
        assertEquals(JwErrorCode.LIBRARY_URL_INSECURE, error.code)
        assertEquals(context.getString(R.string.settings_jw_err_library_url_insecure), error.text())
    }

    @Test
    fun `没有原因码的规范诊断照录原文`() {
        assertEquals("适配器缺少学校名称 name", JwPackageException("适配器缺少学校名称 name").text())
    }

    @Test
    fun `宿主限额取词条`() {
        val limit = JwScriptLimitException(JwScriptLimitException.Kind.SCRIPT_TIMEOUT, 60, "脚本执行超时（60 秒）")
        assertEquals("适配器运行超时（60 秒）", limit.toUiText().resolve(context))
    }
}

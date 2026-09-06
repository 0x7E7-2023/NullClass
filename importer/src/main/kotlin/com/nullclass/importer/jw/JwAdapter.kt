package com.nullclass.importer.jw

import com.nullclass.importer.ScheduleDocument

/**
 * 教务系统（jiaowu）适配器：一校一个实现。
 *
 * 设计红线：
 * - **凭证安全**：登录全程用户在 WebView 手工完成（验证码/扫码/短信都是用户自己操作），
 *   应用不解析、不存储任何账号密码——接口里根本没有凭证的位置
 * - 提取是纯前端 JS 注入（各校登录流程差异被完全屏蔽）
 * - [parseExtracted] 是纯函数，HTML/JSON fixture 单测，CI 可测
 *
 * 贡献新学校：实现本接口 + 注册进 [JwAdapterRegistry] 即接入
 * （详见 README「求你的学校适配」与 issue 模板）。
 */
interface JwAdapter {

    /** 展示名，例：「某某大学」。 */
    val schoolName: String

    /** 稳定标识（小写字母数字），issue 模板与日志用。 */
    val schoolKey: String

    /** 登录页或课表页 URL（用户登录后自行导航到课表页）。 */
    val loginUrl: String

    /**
     * 注入课表页的 JS（保守写法，ES5）：从 DOM 提取数据并返回 JSON 字符串。
     * 注意 evaluateJavascript 回调对超长字符串可能截断——大数据量时应分段提取
     * （示例规模没有该问题，真实适配器注意）。
     */
    val extractScript: String

    /** 解析 [extractScript] 的返回 → ScheduleDocument（复用 M4 导入管线）。 */
    fun parseExtracted(extractedJson: String): ScheduleDocument
}

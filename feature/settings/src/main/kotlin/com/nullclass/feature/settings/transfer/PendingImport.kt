package com.nullclass.feature.settings.transfer

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 待导入文件的跨组件传递（MainActivity 收到 VIEW intent → TransferScreen 消费）。
 * 简单 singleton 即可：单进程应用，URI 用完即清。
 */
object PendingImport {
    val uri = MutableStateFlow<Uri?>(null)
}

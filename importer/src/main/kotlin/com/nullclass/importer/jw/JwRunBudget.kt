package com.nullclass.importer.jw

/** 一拍轮询之后的判定。 */
enum class JwRunVerdict {
    RUNNING,

    /** 脚本自己跑的时间用完了。 */
    SCRIPT_TIMEOUT,

    /** 等用户回答的累计时间用完了。 */
    WAIT_TIMEOUT,
}

/**
 * 脚本执行的**时间预算**。
 *
 * 记的是「脚本自己跑了多久」：等用户读弹窗、做决定的时间**不计数** ——
 * 超时是为了兜住跑飞的脚本，不是为了催用户做决定。
 * 但等待也不是无限的（界面异常时不能永远挂着），所以另外封顶 [maxWaitMs]。
 *
 * 抽成纯逻辑是为了能测：这段账以前长在 Android 侧的轮询循环里，一笔算错
 * 就会把「等用户」和「脚本卡死」混成同一件事。
 */
class JwRunBudget(
    private val timeoutMs: Long,
    val maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
) {

    private var startMs = -1L
    private var waitingSinceMs = -1L
    private var waitedMs = 0L

    /**
     * 记一拍。[nowMs] 必须单调（宿主传 `SystemClock.elapsedRealtime()`），[waiting] 由宿主回答。
     */
    fun tick(nowMs: Long, waiting: Boolean): JwRunVerdict {
        if (startMs < 0L) startMs = nowMs
        if (waiting) {
            if (waitingSinceMs < 0L) waitingSinceMs = nowMs
            if (waitedMs + (nowMs - waitingSinceMs) > maxWaitMs) return JwRunVerdict.WAIT_TIMEOUT
        } else {
            if (waitingSinceMs >= 0L) {
                waitedMs += nowMs - waitingSinceMs
                waitingSinceMs = -1L
            }
            // 等待的那几段已经从账上扣掉：接着从上次停下的地方算脚本时间。
            if (nowMs - startMs - waitedMs > timeoutMs) return JwRunVerdict.SCRIPT_TIMEOUT
        }
        return JwRunVerdict.RUNNING
    }

    companion object {
        /** 单次提取里「等用户回答」的累计上限。 */
        const val DEFAULT_MAX_WAIT_MS = 5 * 60 * 1000L
    }
}

package dev.praxis.feature.chat

/**
 * 待发送队列。
 *
 * ## 为什么需要（Operit 同款能力）
 *
 * 模型生成一轮可能几十秒。这段时间里用户想到了两件事，他只能：
 *  - 等生成完再打第二件事（打断思路）；
 *  - 或者先打下来但**发不出去**（旧版 Praxis 的行为：`canSend` 直接 false）。
 *
 * Operit 的做法是「processing 期间允许入队，前一轮结束后自动发下一条」。
 * 这解决了「我先把想到的写下来」这个真实需求。
 *
 * ## 与 Operit 的差异：必须能调顺序
 *
 * user 明确要求「但是可以调整发送顺序」。Operit 的队列是纯 FIFO，
 * 一旦入了队就只能在末尾追加（要改就得删掉重打）。
 *
 * 这里给出三组操作：[moveUp] / [moveDown] / [moveToTop]。
 * 之所以不做成「拖拽排序」：拖拽在 Compose 里要 `detectDragGesturesAfterLongPress`
 * + 手动 index 交换 + 位移补偿动画，一套下来上百行且容易和列表滚动打架；
 * 而上/下/置顶三个按钮在**队列通常很短**（1-3 条）时完全够用，
 * 是明显更划算的工程选择。
 *
 * ## 纯数据 + 纯函数
 *
 * 这个类不碰协程、不碰 Composable，全部操作返回新实例。
 * 于是「上移到底会不会越界」「删光后下标是否正确」这类问题可以纯 JVM 单测，
 * 不需要跑 Compose 测试。
 */
data class PendingQueue(
    val items: List<QueuedMessage> = emptyList(),
) {
    val size: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()
    val isNotEmpty: Boolean get() = items.isNotEmpty()

    /** 队首（下一条要发的）。 */
    val head: QueuedMessage? get() = items.firstOrNull()

    companion object {
        /**
         * 单条队列上限。
         *
         * 20 条而不是无限：队列是「临时记想法」用的，不是草稿箱。
         * 用户真排到 20 条时，说明前一轮跑得太久，加更多条只会让
         * 后面连续触发十几次请求，费用与等待都不合理。
         */
        const val MAX_SIZE: Int = 20
    }
}

/** 队列中的一条。 */
data class QueuedMessage(
    /** 稳定 id（UI 的 LazyColumn key）。用自增 Long，理由同 `ChatBubble.id`。 */
    val id: Long,
    val text: String,
) {
    val preview: String
        get() {
            val oneLine = text.replace('\n', ' ').trim()
            return if (oneLine.length <= 40) oneLine else oneLine.take(39) + "…"
        }
}

/**
 * 队列操作（全部返回新实例）。
 *
 * 之所以写成 object 的扩展函数而不是 `PendingQueue` 的方法：
 * `PendingQueue` 是状态、这是变换，分开之后单测文件可以只测变换，
 * 读代码时也能一眼看出「哪些操作会改状态」。
 */
object PendingQueueOps {

    /** 追加到末尾。超上限时返回原实例（不静默丢弃用户输入）。 */
    fun enqueue(queue: PendingQueue, id: Long, text: String): PendingQueue {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return queue
        if (queue.size >= PendingQueue.MAX_SIZE) return queue
        return queue.copy(items = queue.items + QueuedMessage(id = id, text = trimmed))
    }

    /** 移除指定条。 */
    fun remove(queue: PendingQueue, id: Long): PendingQueue =
        queue.copy(items = queue.items.filterNot { it.id == id })

    /** 取出队首并移除（发送成功后调用）。 */
    fun poll(queue: PendingQueue): Pair<QueuedMessage?, PendingQueue> {
        val head = queue.head ?: return null to queue
        return head to queue.copy(items = queue.items.drop(1))
    }

    /**
     * 上移一位。
     *
     * 已在首位时**返回原实例**（同一个对象引用），调用方可用
     * `===` 判断「没变化」，从而不触发一次无谓的重组。
     */
    fun moveUp(queue: PendingQueue, id: Long): PendingQueue {
        val index = queue.items.indexOfFirst { it.id == id }
        if (index <= 0) return queue
        return queue.copy(items = queue.items.swapped(index, index - 1))
    }

    /** 下移一位。已在末位时返回原实例。 */
    fun moveDown(queue: PendingQueue, id: Long): PendingQueue {
        val index = queue.items.indexOfFirst { it.id == id }
        if (index < 0 || index >= queue.items.lastIndex) return queue
        return queue.copy(items = queue.items.swapped(index, index + 1))
    }

    /** 置顶（长队列里比连点「上移」十次实用）。 */
    fun moveToTop(queue: PendingQueue, id: Long): PendingQueue {
        val index = queue.items.indexOfFirst { it.id == id }
        if (index <= 0) return queue
        val item = queue.items[index]
        val rest = queue.items.filterNot { it.id == id }
        return queue.copy(items = listOf(item) + rest)
    }

    /** 清空。 */
    fun clear(queue: PendingQueue): PendingQueue =
        queue.copy(items = emptyList())

    /**
     * 回滚一条（发送失败时把它放回**队首**）。
     *
     * ## 为什么必须查重
     *
     * 两条路径可能对同一条调用回滚：
     *  - 自动出队把队首取走 → 发送失败 → 回滚；
     *  - 用户点「立即发送」把它取走 → 失败 → 回滚；
     * 再加上「回滚还没写回状态，用户又点了一次发送」这种时序。
     * 不查重就会出现同一条在队列里出现两次，然后被连发两遍。
     *
     * ## 为什么插到队首而不是末尾
     *
     * 用户原本的意图是「这条先发」。失败回滚时它没发出去，
     * 塞到末尾等于把它的优先级降到最后 —— 用户得再手动上移一次。
     *
     * 超上限时直接丢弃回滚（而不是挤掉一条已有的）：
     * 回滚是补救路径，不该反过来破坏用户已经排好的队列。
     */
    fun restore(queue: PendingQueue, message: QueuedMessage): PendingQueue {
        if (queue.items.any { it.id == message.id }) return queue
        if (queue.size >= PendingQueue.MAX_SIZE) return queue
        return queue.copy(items = listOf(message) + queue.items)
    }

    /** 交换两个下标，返回新列表（不可变，避免原地修改导致状态不更新）。 */
    private fun <T> List<T>.swapped(a: Int, b: Int): List<T> {
        val mutable = toMutableList()
        val tmp = mutable[a]
        mutable[a] = mutable[b]
        mutable[b] = tmp
        return mutable
    }
}
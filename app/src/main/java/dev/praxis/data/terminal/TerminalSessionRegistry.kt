package dev.praxis.data.terminal

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * 记住「AI 建过哪些终端会话名」。
 *
 * ## 它修的是什么（2026-11-05）
 *
 * 真机反馈：「终端页面还是看不到智能体创建的终端会话」。
 *
 * 根因比「UI 不枚举 port」更深一层：`ProotTerminalSessionPort.live` 是
 * **内存 map**，App 进程一死就空。AI 建的会话只活在那个进程里 ——
 * 用户下次打开终端页时，port 里根本没有那个会话，同步自然同步不出来。
 *
 * 所以必须把**名字**持久化。名字够用：`create(name)` / `open(name)`
 * 都是按名幂等的，拿着名字重新建一次就能接上（内容会重新从 shell 出）。
 *
 * ## 为什么只存名字，不存会话 id / 内容
 *
 * | 存什么 | 问题 |
 * |---|---|
 * | 会话 id（`t1`/`t2`） | 每次进程启动 counter 从 1 重数，旧 id 会撞上新会话 |
 * | 屏幕内容 | 已经在 `DataStoreTerminalSnapshot` 里存了（按 tab 存），重复 |
 * | **名字** | 幂等键，重建即可 |
 *
 * ## 为什么不放进 TerminalSnapshot
 *
 * 那个快照是**用户 tab 的存档**（谁在屏幕上、滚动到哪），语义是「恢复界面」。
 * 这里记的是「AI 在后台开过什么」，语义是「待重建的外部资源」。
 * 混在一起会让「用户删掉一个 tab」与「忘掉一个 AI 会话」变成同一个操作。
 */
class TerminalSessionRegistry(
    private val dataStore: DataStore<Preferences>,
) {

    /**
     * 登记一个外部（AI）建的会话名。
     *
     * 幂等：重复登记同名不会产生两条。
     *
     * 失败静默：这只是一个「下次记得重建」的提示，写不进去的后果是
     * 终端页少一个 tab —— 不该因此让工具调用报错。
     */
    suspend fun remember(name: String) {
        if (name.isBlank()) return
        runCatching {
            dataStore.edit { prefs ->
                val current = prefs[KEY].orEmpty()
                prefs[KEY] = current + name
            }
        }
    }

    /**
     * 读出所有登记过的会话名（可能为空）。
     *
     * 读失败返回空列表：退化成「没有外部会话」，
     * 也就是这个功能没做之前的状态。
     */
    suspend fun names(): List<String> = runCatching {
        dataStore.data.first()[KEY].orEmpty().filter { it.isNotBlank() }.sorted()
    }.getOrDefault(emptyList())

    /** 忘掉一个名字（会话被显式关闭时调）。 */
    suspend fun forget(name: String) {
        runCatching {
            dataStore.edit { prefs ->
                prefs[KEY] = prefs[KEY].orEmpty() - name
            }
        }
    }

    private companion object {
        val KEY = stringSetPreferencesKey("terminal_external_session_names")
    }
}

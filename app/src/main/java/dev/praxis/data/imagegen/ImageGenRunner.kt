package dev.praxis.data.imagegen

import dev.praxis.core.imagegen.ImageGenEvent
import dev.praxis.core.imagegen.ImageGenParams
import kotlinx.coroutines.flow.Flow

/**
 * 「跑一次生成」这件事的端口。
 *
 * ## 为什么要这一层
 *
 * [ImageGenClient] 是具体类（持 OkHttp），工具层若直接依赖它，工具的测试就必须
 * 起一个真实（或 mock）的 HTTP 服务。抽一个只含 `generate` 的接口之后，工具的全部
 * 分支（参数错 / 没模型 / 引擎不可用 / 尺寸不合法 / 中途失败 / 成功）都能用一个假实现跑完，
 * 而这正是本工作包唯一能在无设备环境验证的东西。
 */
interface ImageGenRunner {

    /**
     * 生成一张图。事件顺序：若干 Progress，最后一条 Complete。
     * 传输失败抛 [java.io.IOException]。
     */
    fun generate(params: ImageGenParams): Flow<ImageGenEvent>
}

package dev.praxis.core.gateway

import java.io.File
import java.io.IOException

/**
 * 一次导出实际写出去的东西。
 *
 * [rootPath] 与 [writtenPaths] 都会展示给用户（「导出到了哪里」），所以用绝对路径；
 * [writtenPaths] 保持导出计划里的相对形状，便于与计划逐条核对。
 */
data class GatewayExportResult(
    val rootPath: String,
    val writtenPaths: List<String>,
    /** 计划里带来的说明（未包含项），原样交回界面。 */
    val notes: List<String>,
)

/**
 * 把导出计划落到磁盘。
 *
 * ## 为什么放在 core:data 而不是 :app
 *
 * 它只依赖 [File]，不碰 Context —— 于是可以对着一个临时目录跑真实读写：
 * 「嵌套目录有没有建」「内容是不是原样」「越界路径会不会被挡」都能在纯 JVM 上验。
 * :app 只提供根目录（getExternalFilesDir(null)/exports）并调用这里。
 *
 * 放在 core:gateway 而不是 core:data / :app：本机 Windows 上 Android 模块的测试任务
 * 起不来（AGP 路径解析报「文件名、目录名或卷标语法不正确」），放在纯 JVM 模块里
 * 这几条断言才能真的跑起来 —— 而「落盘」这件事本来也不需要 Android。
 *
 * ## 为什么保留目录结构
 *
 * 计划里的 relativePath 形如 accounts/openai-工作号-20261108-001.json。
 * 网关面板导入的是「账号文件」，保持 accounts/ 这一层，用户拿到目录后
 * 能一眼看出哪些是账号、哪个是配置。
 */
object GatewayExportWriter {

    /**
     * @param root 导出根目录；不存在会创建
     * @return 写了哪些文件 + 计划里的说明
     * @throws IllegalArgumentException 计划里出现越界相对路径
     */
    fun write(root: File, plan: GatewayExportPlan): GatewayExportResult {
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("创建导出目录失败：" + root.absolutePath)
        }
        val rootPath = root.canonicalPath
        val prefix = rootPath + File.separator
        val written = mutableListOf<String>()

        for (file in plan.files) {
            val target = File(root, file.relativePath)
            // 相对路径是我们自己生成的，但这一层不假设它一定老实：
            // 越界写出去属于「看起来导出成功、其实覆盖了别处文件」的静默事故。
            require(target.canonicalPath.startsWith(prefix)) {
                "导出路径越界：" + file.relativePath
            }
            val parent = target.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw IOException("创建子目录失败：" + parent.absolutePath)
            }
            target.writeText(file.content)
            written += file.relativePath
        }

        return GatewayExportResult(
            rootPath = rootPath,
            writtenPaths = written,
            notes = plan.notes,
        )
    }
}

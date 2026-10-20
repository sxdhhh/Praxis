package dev.praxis.data

import android.content.Context
import dev.praxis.core.plugin.SkillFile
import dev.praxis.core.plugin.SkillSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 基于应用私有目录的 [SkillSource]。
 *
 * ## 目录约定
 *
 * `filesDir/skills/<目录名>/SKILL.md`（也接受目录下任意 `.md`）。
 * 用**应用私有目录**而不是外部存储：
 *  - 外部存储需要权限，而技能包只是纯文本，没必要为它要权限；
 *  - 私有目录下的文件其他应用改不了，降低「别人偷偷改你的技能提示词」的风险。
 *
 * 想导入技能的用户可以把文件拷到这里（分屏文件管理器 / adb push）。
 *
 * ## 为什么只扫一层
 *
 * Operit 的技能包是 `skills/<name>/SKILL.md` 这种两层结构。
 * 递归扫描会把 `node_modules`、示例目录里的 md 也扫进来，
 * 那些不是技能却会被当成提示词注入，很难排查。
 *
 * ## IO 放到 Dispatchers.IO
 *
 * 目录遍历 + 读文件是阻塞调用，放在主线程会在技能多时掉帧。
 */
class FileSkillSource(
    private val context: Context,
) : SkillSource {

    override suspend fun listSkillFiles(): List<SkillFile> = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, ROOT_DIR)
        if (!root.isDirectory) return@withContext emptyList()

        val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        dirs.flatMap { dir ->
            val mdFiles = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".md") }.orEmpty()
            mdFiles.map { file ->
                SkillFile(
                    folder = dir.name,
                    name = file.name,
                    content = runCatching { file.readText() }.getOrDefault(""),
                )
            }
        }
    }

    companion object {
        const val ROOT_DIR = "skills"
    }
}

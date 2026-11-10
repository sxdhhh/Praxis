package dev.praxis.core.imagegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QNN 运行时解压计划（文档 §1.4）。 */
class QnnRuntimePlanTest {

    @Test
    fun `全部需要 20 个文件`() {
        // 2 个公共库 + 6 个架构 × 3 件。
        assertEquals(20, QnnRuntimePlan.ALL_FILES.size)
        assertEquals(2, QnnRuntimePlan.CORE_FILES.size)
        assertEquals(18, QnnRuntimePlan.HEXAGON_VERSIONS.size * 3)
    }

    @Test
    fun `每套三个文件的名字对得上引擎的加载方式`() {
        assertEquals(
            listOf("libQnnHtpV75.so", "libQnnHtpV75Skel.so", "libQnnHtpV75Stub.so"),
            QnnRuntimePlan.filesFor(75),
        )
    }

    @Test
    fun `缺公共库就不算可用（引擎会直接报错）`() {
        assertFalse(QnnRuntimePlan.isUsable(listOf("libQnnHtp.so")))
        assertFalse(QnnRuntimePlan.isUsable(emptyList()))
        assertTrue(
            QnnRuntimePlan.isUsable(listOf("libQnnHtp.so", "libQnnSystem.so")),
        )
    }

    @Test
    fun `只缺某个架构时仍然可用（不该整条路判死）`() {
        val partial = QnnRuntimePlan.ALL_FILES - QnnRuntimePlan.filesFor(81)
        assertTrue(QnnRuntimePlan.isUsable(partial))
        assertEquals(
            listOf(68, 69, 73, 75, 79),
            QnnRuntimePlan.coveredVersions(partial),
        )
    }

    @Test
    fun `缺件清单能直接展示给用户`() {
        val missing = QnnRuntimePlan.missing(QnnRuntimePlan.CORE_FILES)
        assertEquals(18, missing.size)
        assertTrue(missing.contains("libQnnHtpV68Skel.so"))
        assertEquals(emptyList<String>(), QnnRuntimePlan.missing(QnnRuntimePlan.ALL_FILES))
    }

    @Test
    fun `内置清单里真有这些文件（防「清单与 assets 漂移」）`() {
        // 这是纯 JVM 模块，读不到 Android assets；但可以断言计划本身自洽：
        // 名字不重复、都以 lib 开头、都是 .so。
        assertEquals(QnnRuntimePlan.ALL_FILES.size, QnnRuntimePlan.ALL_FILES.toSet().size)
        assertTrue(QnnRuntimePlan.ALL_FILES.all { it.startsWith("lib") && it.endsWith(".so") })
    }
}

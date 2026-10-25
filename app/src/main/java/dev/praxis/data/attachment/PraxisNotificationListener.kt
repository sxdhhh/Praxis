package dev.praxis.data.attachment

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 读取当前活跃通知，拼成一段可插进输入框的文字。
 *
 * ## 为什么继承 NotificationListenerService
 *
 * Android 只允许**已授权为通知监听器**的应用读取通知。没有这个服务
 * 声明，`NotificationManager.activeNotifications` 会直接抛异常或返回空。
 * 所以本文件既是「读取器」又是「监听器声明」。
 *
 * ## 为什么不用单例缓存自己的实例
 *
 * 标准做法是把 `onListenerConnected` 里拿到的 instance 存进
 * `companion object`，读取时直接用。这里没这么做：静态持有 Service 引用
 * 在系统重建服务时会留下陈旧引用（点开就崩）。改成走
 * `NotificationManager.activeNotifications`（API 23+），由系统查询，
 * 不依赖我们持有什么。
 *
 * ## 隐私边界
 *
 * 只取标题、正文、包名。**不取**大文本样式里的完整内容、不取
 * `Notification.EXTRA_SUB_TEXT` 之外的扩展字段 —— 通知里可能有
 * 验证码、聊天预览，多取一分就多一分暴露面。
 */
class PraxisNotificationListener : NotificationListenerService() {

    companion object {
        /**
         * 拼接当前活跃通知（最多 [limit] 条，最多 [maxChars] 字符）。
         *
         * @return null 表示没有权限。调用方应提示用户去开权限，
         *   而不是笼统地说「没有通知」。
         */
        fun readActive(context: Context, limit: Int = 15, maxChars: Int = 2000): String? {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as? android.app.NotificationManager ?: return null

            val notifications = runCatching { manager.activeNotifications }.getOrNull()
                // 无权限时不同 ROM 表现不同：有的抛 SecurityException，
                // 有的返回空数组。统一按「无权限」处理。
                ?: return null

            if (notifications.isEmpty()) return "[通知] 当前没有活跃通知"

            val lines = notifications
                .sortedByDescending { it.postTime }
                .take(limit)
                .mapNotNull { sbn ->
                    val title = sbn.notification.extras
                        .getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
                    val text = sbn.notification.extras
                        .getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
                    if (title.isNullOrEmpty() && text.isNullOrEmpty()) return@mapNotNull null
                    buildString {
                        append("- ").append(sbn.packageName.substringAfterLast('.'))
                        if (!title.isNullOrEmpty()) append("：").append(title)
                        if (!text.isNullOrEmpty()) append(" — ").append(text)
                    }
                }

            if (lines.isEmpty()) return "[通知] 当前没有可读通知"

            // 截断：通知正文可能很长（聊天预览），超长会把上下文挤爆。
            var used = 0
            val kept = ArrayList<String>(lines.size)
            for (line in lines) {
                if (used + line.length > maxChars) break
                kept += line
                used += line.length
            }

            return buildString {
                append("[通知] 当前 ").append(kept.size).append(" 条：\n")
                kept.forEach { append(it).append('\n') }
            }.trimEnd()
        }
    }
}

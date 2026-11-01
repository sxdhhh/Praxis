# Praxis 记忆库

从 Operit 的 ObjectBox 库（`/data/data/com.ai.assistance.operit/files/objectbox/data.mdb`）
完整提取的原始记忆。**未经改写、未做摘要**，保留全部正文。

导出于 2026-11-01，共 **2161 条 / 1,374,829 字符**。

## 怎么用

先想清楚你要找什么，再决定读哪个文件——不要一上来就打开 `all-memories.md`（2.4MB）。

| 你要找 | 读这个 |
|---|---|
| Praxis / operit-next 的任何历史决策 | **`PRAXIS-only.md`**（641 条，47 万字）← 最常用 |
| 某个具体主题（FTP、终端、UI、CI…） | `by-topic/<主题>.md` |
| 确定记忆里一定有，但要全文检索 | `grep -rn "关键词" .` |
| 程序化处理（写脚本筛） | `all-memories.json` |
| 就是想通读一遍 | `all-memories.md`（不推荐） |

### 检索示例

```bash
cd docs/memories

# 找所有提到 vsftpd 的记忆
grep -rn "vsftpd" .

# 只在 Praxis 专题里找 chroot
grep -n "chroot" PRAXIS-only.md

# 用 python 按长度排序，读最长的 5 条（通常是最完整的决策记录）
python3 -c "
import json
d=json.load(open('all-memories.json'))
for e in d['entries'][:5]:
    print('='*60); print(e['title'], e['length']); print(e['body'][:800])
"
```

## 主题分组

| 文件 | 条数 | 内容 |
|---|---|---|
| `by-topic/01-小猿口算逆向.md` | 143 | 小猿口算 3.141.1 接口逆向、签名、协议 |
| `by-topic/02-pk-node.md` | 345 | pk-node 私服、sign、刷分规则、练习链路 |
| `by-topic/03-pk-h5私服.md` | 93 | PK H5 页面、WebView 转场、cookie 传导 |
| `by-topic/04-cn.apixiaoyuan.app.md` | 108 | 「逆向系老挂」App |
| `by-topic/05-suchat.md` | 102 | suchat、WeKit、液态玻璃、莫奈取色 |
| `by-topic/06-HyperOS内核.md` | 65 | HyperCeiler、LSPosed、引导链移植 |
| `by-topic/07-Operit与Praxis.md` | 648 | **Operit / Praxis / proot / 容器 / 终端** |
| `by-topic/08-工具与纪律.md` | 87 | 协作规范、记忆管理、提示词偏好 |
| `by-topic/09-LocalHub与运维.md` | 138 | LocalHub、AWS、阿里云、frp、CI |
| `by-topic/99-其它.md` | 432 | 未命中关键词的 |

分组是**按关键词首匹配**做的，一条记忆只进一个组。所以
某条 Praxis 相关的记忆如果先命中了「pk-node」，它就在 02 里。
找 Praxis 相关内容请用 `PRAXIS-only.md`，它是按 `praxis|operit-next` 正则全局匹配的。

## 提取方式与已知精度损失

ObjectBox 是闭源二进制格式，没有可用的 Python 反序列化工具。提取方式是：

1. 正则扫出连续 ≥20 字符的可打印 ASCII 或合法多字节 UTF-8 序列
2. 过滤掉 UUID、`reply_finalized_auto` 之类元数据碎片、过短的纯 ASCII 串
3. SHA-256 去重（同一正文在库里可能存多份）

**代价**：会混入少量元数据碎片；极短的记忆（<20 字符）会丢失。
**收益**：拿到全部正文，包括长文档，没有任何截断。

旧导出 `/root/memories-all`（2026-10-08，1099 条 / 63.8 万字）已过时，
它甚至**完全没有** Praxis 相关内容（那时项目还没定名）。不要用它。

## 与交接文档的关系

- `docs/HANDOVER.md` 是**提炼过的结论**——该怎么做事、哪里有坑
- 本目录是**原始证据**——某个决定是什么时候、因为什么、谁说的

两者冲突时以本目录的原始记录为准，然后去更新 HANDOVER。

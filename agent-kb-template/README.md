# Agent 知识库骨架（项目无关可复用版）

从 LinguaReader（`C:\work\reader`）的知识库提炼的**方法论层**：只搬结构与治理规则，不含任何本项目事实。可落地到任何由 AI agent 驱动开发的代码库。

## 它解决什么问题

Agent 每个会话都是失忆重启。这套骨架把跨会话需要传递的知识分成三层，各层职责单一：

| 层 | 落地物 | 回答的问题 | 更新频率 |
| --- | --- | --- | --- |
| 入口 | 仓库根 `AGENTS.md` | 开工前必须知道什么（前提/命令/构建事实/约定/验证纪律） | 随事实低频更新 |
| 记忆 | `.agents/memory/` | 这个仓库的隐性知识与历史教训 | 每次踩坑/改边界 |
| 规则 | `.agents/rules/` | 记忆怎么写、代码怎么验 | 几乎不变 |

核心信条（出自 `memory-maintenance.md`）：**知识库的价值 = 准确度 × 可检索性，不是篇幅。**

## 文件清单与安装位置

| 模板文件 | 复制到 | 改名为 |
| --- | --- | --- |
| `AGENTS.template.md` | 目标仓库根 | `AGENTS.md` |
| `agents/rules/memory-maintenance.md` | `.agents/rules/memory-maintenance.md` | 不改 |
| `agents/rules/code-and-verification.md` | `.agents/rules/code-and-verification.md` | 不改 |
| `agents/memory/MEMORY.template.md` | `.agents/memory/MEMORY.md` | `MEMORY.md` |
| `agents/memory/known-pitfalls.template.md` | `.agents/memory/known-pitfalls.md` | `known-pitfalls.md` |

所有【】为占位符，填完删除。若你的 agent 工具用别的入口文件名（如 `CLAUDE.md`），按工具要求改名，内容结构不变。

## 落地步骤（新仓库从零到可用，约两个会话）

1. **复制骨架**：按上表落位。
2. **填入口（一个会话）**：前提、命令、构建事实必须**实际执行命令、打开配置文件核对**后填写，禁止凭记忆或猜测。能从目录结构一眼看出的不写进「前提」——前提的收录标准是「没有上下文的聪明 agent 第一次接触必然搞错」。
3. **写首批记忆（一个会话）**：通读代码，产出
   - `architecture-map.md`：状态体系、依赖方向、端到端数据流、跨模块契约、改动影响面速查表；
   - 2~3 份改动最频繁模块的主题文件；
   - 空的 `known-pitfalls.md` 起步。

   每份带「最后核对 @ commit」锚点。
4. **之后靠规则自养**：踩坑追加 pitfalls；改边界/契约同步主题文件；版本号变更后抽查过期条目（见 `memory-maintenance.md` 的过期治理）。

## 移植时最容易做错的三件事

1. **把猜测当事实写进记忆**——宁可留空或标「待查」，也别编。错误记忆比没有记忆更糟（agent 会自信地照着错）。
2. **入口写成产品文档**——`AGENTS.md` 的读者是 agent 不是用户，只写影响动手的信息。
3. **记忆越写越长**——单文件 200 行上限，超了拆主题并更新索引。

## 相对源知识库的三处补强

源库（LinguaReader）实测中发现的衰减模式，对策已写进本骨架的规则：

1. 增量追加条目出现**编号撞车、计数失修**（两个 #19、头部计数停在旧值）→ `memory-maintenance.md` 增补「增量追加要回收计数」。
2. 同一数值散落多份文档各自漂移（资源条数三处三个值）→ 增补「同一数值只维护一个权威出处」+ 金丝雀抽查法。
3. 索引级「最后核对」落后于文件级 → 增补索引头部时间戳的同步规则。

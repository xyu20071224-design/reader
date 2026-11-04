# 规则：共享 worktree 下的 git 操作（并行 agent 专用）

> 适用场景：**多个 agent 同时在一个 git worktree 里工作**（本项目重构期间的实际形态）。
> 单人开发时不需要这些规则。

## 为什么需要

多个 agent 共用**同一个 `.git/index`**。这带来两个不直观的失败模式：

### 失败模式 1：`git commit` 会把别人暂存的东西一起带走

`git add` 与 `git commit` **不是原子的**。在「add 完成 → 检查 cached → 执行 commit」
这个窗口里，别人如果也跑了 `git add`，他的文件进了同一个暂存区，被你的 commit 一起带走。

**实际发生过**：一次提交里混进了 6 个别的 agent 的文件（书架拆解 + 阅读页拆解被合成一条提交），
提交信息只描述了其中一件事。内容没丢，但**归属错了、回滚粒度毁了**。

### 失败模式 2：`index.lock` 竞争

两个 git 进程同时写索引时，后到的会撞上 `index.lock`。等锁释放后重试，
可能正好落在对方 `git add` 之后，于是重试的 commit 带走了对方的暂存内容。

## 规则

### 1. 提交时总是带 pathspec

```powershell
git commit -F msg.txt -- <显式路径1> <显式路径2> ...
```

`git commit -- <pathspec>` **只提交指定路径**，忽略 index 里别人暂存的东西。
这一条就能根治上面两个失败模式。

### 2. 永远不要用 `git add -A` / `git add .` / `git add src/`

显式路径是唯一可接受的形式。

### 3. 动历史前先确认两件事

```powershell
# a) 目标提交尚未推送
$h = (git rev-parse HEAD).Trim(); $o = (git rev-parse origin/<branch>).Trim();
# b) 工作区干净
git status --porcelain
```

并且 **先通知其他 agent 暂停 git 操作**，然后**一个人单线程做完**——
不要边拆边让别人提交。

### 4. 中途态不要留在工作区

「先建新文件、再改调用方」这类改动会让编译在一段时间内是红的，**挡住所有人验证**。
做法：每抽出一块就让它回到可编译状态并**立刻提交**，不要攒到最后。

### 5. 编译红了先看 `git status`，再判断是不是自己的错

并行期间最常见的情况是：**报错来自别人的中间态**。
本轮实际遇到两次（`AppViewModel` 缺转发层、`ReaderScreen` 缺 import），
都是别人改到一半。**不要因为报错在自己文件里就以为是自己**，也不要急着去改别人的文件。

## 纯代码移动的验收判据（配套）

拆解巨型文件时，`git diff` 会把「移动」显示成一堆 `-`/`+`，人眼很难在几十上百行里
发现「手抖改了一个字」。用 `scripts/verify-pure-move.ps1` 做**机械比对**：

```powershell
.\scripts\verify-pure-move.ps1 -OldFile <老文件> -OldRef <提交> -OldRange "1075..1153" `
    -NewFile <新文件> -RenameFrom "private fun ContentsSheet" -RenameTo "internal fun ReaderContentsSheet"
```

判据是**二元**的：`[OK] 纯搬移已确认` 或 `[FAIL] 第 N 行不同`。
不相等就必须人工解释每一处差异。

## 附：长文件改写

`read` 默认 `limit = 2000` 行。用「读到的内容」覆盖整个文件会**静默截断**剩余部分。
本轮两个人各踩一次（一次丢 1438 行、一次丢 1768 行），都靠 `git diff --stat` 的
删除行数远超预期发现。

**判据不是记阈值，记等式**：`read` 返回的 `totalLines` == 实际拿到的 `lines.length`。
优先用 `edit`（局部替换）而不是 `write`（整文件覆盖）。
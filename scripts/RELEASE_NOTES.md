# 绑定真实发布边界的本地说明工具

`build_release_notes.py` 仅读取本地 Git 对象和已经公开的 GitHub release 元数据，
不请求网络、不生成 AI 摘要、不创建或修改发布。先导出仓库全部页的 release 元数据为
JSON 数组，同时保存当前和前一个正式发布的原始元数据对象。草稿和预发布不能充当边界。

```sh
python3 scripts/build_release_notes.py \
  --repo . \
  --release-json current-release.json \
  --previous-release-json previous-release.json \
  --published-history-json all-release-pages.json \
  --out src/app/build/generated/releaseNotesAssets/release-notes/published-vX.Y.Z.json
```

工具从完整元数据中核对当前发布及按 `published_at` 紧邻的前一个正式发布，拒绝跳过中间发布。
输入应来自受信任的公开元数据导出；工具无法证明手工删减的输入包含全部页。时间相同而无法
唯一确定前版时拒绝生成。两端真实 tag 必须存在，前版 commit 必须是当前版祖先，当前 tag
必须匹配该 tag 源码中的 versionName/versionCode。提交清单只取两端 tag 之间，不含新 HEAD。
原发布正文保持原文，空说明仍为空，不把空说明宣称为没有改动。

应用只在 versionName、versionCode、40 位实际源码 commit、clean 状态全匹配时把资料
作为“本版改动”。由构建装配把 `build/generated/releaseNotesAssets` 加入 main assets；生成
目录被 Git 忽略，在干净 tag 上生成资料不会改变源码身份。若写入已跟踪源目录，则会产生
dirty 或改变 commit，因此该输出只能作为历史归档，不能假定仍匹配当前构建。
其他已核实资料只作为发布档案。未知 SHA、dirty、测试或未发布构建显示
本版资料缺失；不能把其新修复写入旧正式版档案。打包资料属于 APK assets，需新 APK 生效。

当前 `published-v2.3.1.json` 来自真实公开 v2.3.1（ad20253）和 v2.3.0（69c6123），
不描述本次 issue 修复。生成是发布后的资料归档；若下一 APK 源码已离开正式 tag，则该资料
只属于历史，直到有与新 APK 精确对应的真实发布证明。

测试：`python3 -m unittest discover -s scripts -p 'test_release_notes.py' -v`。

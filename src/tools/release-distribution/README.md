# 发行和资源分发源

共享层 `update.ReleaseSource` 将发布历史、资产身份和实际传输分开。构造源不会联网；调用
`fetchPage` 或 `latestRelease` 才读取网络。自动检查开关、源选择和备用源策略由应用装配层控制。

## 接口

- `ReleaseSource.id`：缓存和请求所属的源；历史缓存由历史说明模块保存，不由网络适配器保存。
- `fetchPage(cursor)`：返回 `ReleasePage(sourceId, entries, nextCursor)`。空游标请求第一页；
  非空游标只交回同一个源使用。请求或格式失败抛出异常，不能用空页假装历史已加载完。
- `latestRelease()`：获取该源明确指定的正式发布。GitHub 使用 `releases/latest`；静态源按
  `latestTag` 找到对应记录，缺记录、循环分页或把预发布当正式最新版均拒绝。
- `ReleaseRecord`：即使没有 APK 也保留发布说明，供版本历史使用。`canonicalBody()` 提供旧
  更新/补丁解析器所需的 JSON，其中所有资产 URL 都是原 GitHub 身份。
- `ReleaseAsset`：`canonicalUrl` 是原发布身份；`transportUrl` 是当前所选源的实际 URL。
  两者不同必须给出精确 `bytes` 和 SHA-256，下载完成后核对。普通 GitHub 资产未提供 digest
  时可以缺省哈希，但仍受下载上限和 HTTP Content-Length 完整性校验。

## 静态目录协议一版

用户必须显式选择一个 HTTPS 目录文件，例如 `https://mirror.example/reader/catalog.json`。
每个页面和资产只允许访问该地址的同源、同目录范围；跨源、父目录、降级、凭据、片段、
越界重定向及含糊的编码路径均拒绝。远端 JSON 不能增加可信域名。

目录包含 `schemaVersion: 1`、`repository: "xyu20071224-design/reader"`、`latestTag`、
`releases` 和可选的 `nextPage`。`latestTag` 在没有正式版时为 null；不能在有正式版时谎报空。
每页最多 100 个发布，最大 4 MiB。`nextPage` 和资产 `url` 都相对用户选择的**原始目录文件**
解析，不相对当前分页位置解析。

每个发布包含 `id`、`tag`、`notes`、`publishedAt`、`targetCommitish`、`draft`、`prerelease`、
`assets`。资产包含 `name`、原 GitHub `canonicalUrl`、镜像 `url`、精确 `bytes`、64 位小写
`sha256`；原 URL 的 tag 和文件名必须与所在发布和资产一致。相同页面的 ID、tag 或资产名
不能重复。未来协议版本不能按一版猜测解析。

HTTPS 目录的授权配置与发布者身份由装配/发布流程明确指定，文件哈希本身不能证明发布者
身份。元数据只提供发现信息；APK 包名/签名/升级身份、资源包自身校验、补丁签名和基础包
约束均在原消费管线继续验证。不能为了让镜像工作而删除 `NativePatchCatalog.trustedAsset`。

## 本地生成镜像文件

准备下载好的原始 GitHub release 对象或列表 JSON，以及按 `<tag>/<asset name>` 放置的
实际发布资产。工具只读本地文件、复制原字节并生成目录，不联网、不部署，也不重建 APK。

```sh
python3 scripts/build_release_mirror.py \
  --release-json /absolute/releases.json \
  --assets-dir /absolute/original-assets \
  --out /absolute/mirror-output \
  --latest-tag v2.3.1
```

`--latest-tag` 应与实际 GitHub 正式最新版一致；未指定时默认选发布时间最新的正式版。
不会镜像 draft。原 metadata 给出的 size/digest 与本地字节不符时停止，旧目录保持不变。
资产和分页放入不可变的 `generations/<digest>/`；全部存在后才原子切换 `catalog.json`。
旧 generation 不自动删除，避免仍读取旧目录的客户端失效。

Python 3.10 及以上可执行工具；自检命令为：

```sh
python3 -m unittest discover -s scripts -p 'test_release_mirror.py' -v
```

## 应用接入与未完成验收

装配层按用户设置选 `GitHubReleaseSource` 或 `StaticReleaseSource`，将页数据交历史模块。
下载时保留所选发布/资产对象，用该源的 `assetPolicy` 调 `ReleaseHttpTransport.download`；
不能只保留文件名再从另一目录猜同名资产。下载切源要先取消原任务并清除待下载引用。

原生补丁必须先按原 GitHub canonical 身份走旧发现校验，再通过选定镜像取字节；最终仍由
`NativeHotfixRuntime` 校验证书、精确基础 APK 哈希和签名内修订号、防重放状态。本协议不会
改变上述认证，也不会把第三方插件代码作为资源载入。

真实国内源、信任配置、匹配签名资产和设备尚未提供时，只能报告客户端/本地协议验证，
不能报告“国内可用性已验收”。应在 GitHub 不可达的条件下另验发现、包安装、APK 更新和
匹配基础包补丁的应用/撤销，并记录实际网络路径和失败回退。

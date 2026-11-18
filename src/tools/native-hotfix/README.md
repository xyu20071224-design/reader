# Android 原生热修复

从 2.3.0（versionCode 25）起，「检查更新」支持腾讯 Tinker 1.9.15.2 的原生 DEX 补丁。
旧版本需要先通过原有 APK 更新流程安装一次基础包。后续兼容的小修复可下载 `.lrpatch`，
完成校验与合并后由用户点击「重启应用」生效，不再调用 APK 安装器。重启会暂停听书。

## 边界

- 可修复 Kotlin/Java 业务逻辑，包含由 Kotlin 字符串提供的阅读脚本。
- 本版本只启用 DEX，不支持改 Manifest、Android XML/字符串资源、原生库或内置 assets。
  这些变化应发新 APK；词典内容可继续使用资源包。
- `ReaderApplication` 和 Tinker loader 是不可变入口。构建工具跨全部 DEX 检查其语义未变。
- R8 保留代码裁剪、混淆和资源裁剪，但关闭代码优化。即使 `keep` 的加载器也可能被
  outlining 抽取到随业务变化的合成类；基础包和候选包必须使用相同配置。
  比较器仅在内存副本中排除调试行表，防止 R8 共享 PC 行表长度造成误判；
  方法签名、真实指令、常量、异常处理和注解仍须一致，输入 APK 不作改写。
- 每个补丁精确绑定基础 APK 的 SHA-256、包签名、TINKER_ID 和 versionCode。
  同版本的另一构建产物也不能混用。必须保存实际发布的 APK 和它的 R8 mapping。
- 修订号独立于 APK 版本递增。每次都对同一原始基础包生成累计补丁，不能以旧补丁为基础。
- 新 APK 优先于热补丁。对其他基础版本不匹配的补丁不会显示。
- 合并不重启主进程、不打断当前阅读。重启后框架在业务类加载前注入补丁。
  Android Q 及以后使用框架的异步 dexopt 模式，避免等待 ART 不生成的 ODEX 文件；
  首次启动的优化耗时可能增加。

## 基础包归档

正式构建使用仓库规定的签名流程，校验证书与上一正式包一致。归档（不要提交 Git）：

1. 最终签名 APK，发布后不可重新构建替换。
2. 同一构建的 `src/app/build/outputs/mapping/release/mapping.txt`。
3. 源码提交、构建参数、JDK/SDK/Gradle 版本及 APK SHA-256。

基础 Release 附 APK、校验文件和空目录：

```json
{"schemaVersion":1,"patches":[]}
```

目录资产名必须为 `native-patches.json`。网络检查仍由用户手动点击或显式开启自动检查；
出厂状态不新增后台联网。

## 生成补丁

先在基础源码上做兼容修复，保持包名、versionCode、versionName 和资源完全不变。
使用基础包 mapping 构建 release，然后使用同一证书签名候选 APK：

```sh
./toolchain/build.sh :app:assembleRelease -PhotfixMapping=/absolute/base/mapping.txt
```

准备官方 [Tinker 1.9.15.2 CLI](https://github.com/Tencent/tinker/releases/tag/v1.9.15.2)
及 JDK 17。脚本直接调用官方 CLI，不依赖旧版 AGP 的 Tinker Gradle 插件。
签名密码通过环境变量提供，密钥与密码都不进入源码或发布说明。

```sh
python3 scripts/build_native_patch.py \
  --base /absolute/base/LinguaReader-v2.3.0.apk \
  --new /absolute/candidate-signed.apk \
  --cli /absolute/tinker-patch-cli.jar \
  --keystore /absolute/release.keystore --alias RELEASE_ALIAS \
  --apksigner /absolute/android-sdk/build-tools/35.0.0/apksigner \
  --aapt /absolute/android-sdk/build-tools/35.0.0/aapt \
  --out /absolute/output --base-id reader-25 --base-code 25 --revision 1 \
  --notes '本次修复说明'
```

默认密码变量为 `READER_PATCH_STORE_PASSWORD`，可用 `--password-env` 指定。
生成 `.lrpatch`、`native-patches.json`、签名核验记录和 CLI 日志。
除安装时使用、含 DEX 校验值的 `assets/dexopt/baseline.prof/.profm` 外，
任何资源或原生库差异都会拒绝生成。安装时优化文件不随补丁加载。
补丁使用 JAR v1 签名；脚本逐项读取验证证书。它没有 Manifest，不能用
`apksigner verify` 当作普通 APK 验证，也不能改成 `.apk` 误导旧客户端安装。

## 验证与发布

先在独立设备/并存包验证：只安装基础 APK，通过补丁改变实际 Kotlin 方法结果；
重启前后读取已安装 base.apk 的 SHA-256，必须不变。测试撤销后再次重启恢复基础行为，
并验证错误证书、错误基础包、篡改包和已撤销修订被拒绝。release 必须另做 R8 验证，
debug 结果不能替代。验证记录写入 `VALIDATION.md`。
有状态设备协议的手动诊断源模板与运行顺序见 [device/README.md](device/README.md)。

验证通过后将补丁附到该基础版本仍作为 latest 的 GitHub Release；不要为每个原生修订创建
更高 APK 版本标签。先上传补丁，再更新目录，保留基础 APK 供旧客户端升级。
目录中的 `assetName`、`bytes`、`sha256` 必须与实际发布资产完全一致。
多个基础版本并存时合并目录条目；每个基础版本列出最高的累计修订即可。

下载只接受指定 GitHub 仓库的 HTTPS Release 资产，并检查大小、SHA-256、ZIP 路径和展开量。
随后 Tinker 对签名与 TINKER_ID 再验一次，应用检查签名内的精确基础 APK 哈希与修订号。
未签名的发现目录不能伪造签名内元数据。热修复状态使用 AtomicFile 跨进程落盘。

## 撤销与故障恢复

更新面板可撤销当前补丁；重启后回到基础 APK。撤销保留已用最高修订号，
同一补丁不会再次提示或被重放，修复撤销问题应发更高修订号的累计包。
框架启用启动安全模式；准备完成但下一进程未成功加载时清理补丁并显示失败状态。
本地状态损坏时拒绝旧修订而保持基础应用可启动。新 APK 使用新的基础 ID，独立管理修订。

框架源代码与平台参考：[Tencent/tinker](https://github.com/Tencent/tinker)、
[Android 动态代码加载](https://developer.android.com/privacy-and-security/risks/dynamic-code-loading)。
只对项目当前的 GitHub 自分发流程提供补丁，不把此流程直接用于应用商店渠道。

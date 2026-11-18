# 手动设备诊断夹具

此目录用于 2.3.0 / reader-25 的有状态协议验证，需要指定签名补丁及跨进程重启顺序，
不属于普通 `connectedDebugAndroidTest`。其他基础版本须同步调整探针里的基础 ID/code。
所有探针、密钥和诊断补丁均不得作为用户更新资产发布。

- `NativeHotfixDeviceTest.kt`：可复制到隔离源码副本的 androidTest 中逐项运行。
  需要 debug 基础包和测试 APK；不要用它直接测试混淆 release，测试运行器可能引用已改名的 Kotlin 类。
- `NativeProofInstrumentation.java` / `AndroidManifest.xml`：正式 R8 包使用的独立 Java Instrumentation，
  通过目标应用自己的 ClassLoader 调用保留的热修复 ABI，不携带目标应用的 Kotlin/AndroidX 实现。

独立探针以 JDK 17 的 `javac --release 8`、SDK 35 android.jar 编译，
使用 build-tools 的 D8 生成 classes.dex、aapt 打包 Manifest，然后把 DEX 加入 APK，
用与基础 APK 相同的证书通过 apksigner 签名。探针自身包名为 `com.linguareader.hotfixproof`，
默认目标为 `com.linguareader.app`；并存包需要调整 Manifest 的 targetPackage。

从基础包 mapping 找到 `kotlin.coroutines.EmptyCoroutineContext -> …:` 的实际类名，
通过参数 `emptyContext` 传入。探针可反射构造该单例并等待真正的 suspend 完成回调。
不需要为测试目的保留整个 Kotlin 标准库的类名。

把以下夹具放到目标应用的 externalFilesDir：

- `fixture.lrpatch`：同证书、精确基础 APK、签名修订号为 1 的有效补丁。
- `wrong-cert.lrpatch`：其他证书签署的有效归档。
- `wrong-base.lrpatch`：原证书签署，但 TINKER_ID 与基础包不一致。
- `wrong-revision.lrpatch`：有效修订 1 的副本，由探针尝试用未签名的修订 2 说明应用。

调用示例：

```sh
adb shell am instrument -w -r \
  -e action prepareSignedNativePatchWithoutInstallingAnApk \
  -e emptyContext 'MAPPED_EMPTY_CONTEXT_CLASS' \
  com.linguareader.hotfixproof/com.linguareader.hotfixproof.NativeProofInstrumentation
```

成功必须同时看到 `proofPassed=true` 和 `INSTRUMENTATION_CODE: -1`。
失败会输出原始异常及 `proofPassed=false`，不能仅凭 adb 退出码判定。

逐项调用顺序：

1. `prepareSignedNativePatchWithoutInstallingAnApk`，然后完全关闭并重开目标应用。
2. 检查真实 Kotlin 行为改变、安装的 base.apk SHA-256 不变；可使用隔离诊断候选中的查词分支。
3. `tamperedPatchCannotBecomeActive`。
4. `validArchiveWithWrongCertificateIsRejected`。
5. `validSignatureWithWrongBaseIsRejected`。
6. `unsignedDiscoveryCannotForgeSignedRevision`。
7. `interruptedResultCallbackRecoversFromLoadedSignedMetadata`。
8. `rollbackRetainsRevisionHighWaterAndRestoresBaseOnReopen`，然后完全关闭并重开目标应用。
9. 检查基础行为恢复、base.apk SHA-256 不变，再运行 `rolledBackRevisionCannotBeReapplied`。

中断恢复动作会把本地回调状态改回 applying、highest=0，
用已加载补丁的签名元数据重建 ready/最高修订；只能用于独立测试设备的数据。
先确认没有真实书库，保存日志、界面 XML 与最终摘要，按主文档记录验证范围。

# 审查附件-第四轮（2026-09-11）

第四轮审查的**可复现实验台**。**不是项目正式测试，不要提交、不要并入 `src/`**。

审查报告：`../审查报告-第四轮.md`

## 内容

| 文件 | 用途 | 对应审查点 |
| --- | --- | --- |
| `ReproImportAuditTest.kt` | 非 EPUB（TXT/FB2/PDF，EPUB 作对照）导入耗时 + 排版还原实测 | 审查点 4 |
| `ReproSplitterAuditTest.kt` | 分句器边界用例电池 + 魔戒全文不变量测量 | 审查点 2 |
| `contrast.py` | 主题调色板 WCAG 对比度计算（纯 python3，无依赖） | 审查点 5 |

## 复现步骤

```bash
cd /home/xinyan/work/reader

# 1) 导入实测（Robolectric；把两个 .kt 临时拷进源码树，跑完删掉）
cp 审查附件-第四轮/ReproImportAuditTest.kt   src/app/src/test/java/com/linguareader/app/data/
cp 审查附件-第四轮/ReproSplitterAuditTest.kt src/shared/src/test/java/com/linguareader/shared/tts/
./toolchain/build.sh :shared:test --tests "com.linguareader.shared.tts.ReproSplitterAuditTest" \
    :app:testDebugUnitTest --tests "com.linguareader.app.data.ReproImportAuditTest" --console=plain
rm src/app/src/test/java/com/linguareader/app/data/ReproImportAuditTest.kt \
   src/shared/src/test/java/com/linguareader/shared/tts/ReproSplitterAuditTest.kt

# 2) 看报告（Gradle 默认吞掉测试 stdout，指标在 XML 的 <system-out> 里）
python3 - <<'PY'
import xml.etree.ElementTree as ET
for p in ("src/app/build/test-results/testDebugUnitTest/TEST-com.linguareader.app.data.ReproImportAuditTest.xml",
          "src/shared/build/test-results/test/TEST-com.linguareader.shared.tts.ReproSplitterAuditTest.xml"):
    r = ET.parse(p).getroot()
    print("="*20, r.get('name'), r.get('tests'), "fail", r.get('failures'))
    so = r.find('system-out')
    if so is not None: print(so.text)
PY

# 3) 对比度
python3 审查附件-第四轮/contrast.py
```

## 前置条件

- `ReproSplitterAuditTest.corpusInvariants` 需要 `artifacts/lotr-book/`（魔戒英文，app 导入格式）
  与 `artifacts/lotr-zh/`（中译本，解包 EPUB）。两者 gitignore、仅存档机有；缺失时该条
  会打印「缺少 artifacts/lotr-book，跳过语料测量」并直接返回，不失败。
- `ReproImportAuditTest` 无额外数据依赖，全部样例合成（TXT/FB2/EPUB/PDF 生成的字节数与
  章节数写在 `<system-out>` 第一行）。
- 两个测试都会往 `/tmp/lingua-review/work/` 写样例与落盘结果；该目录不属于仓库。

## 已知说明

- `contrast.py` 原稿在最后一节的解包里把 `link` 误写成未定义的 `l`（`UnboundLocalError`），
  本目录里已修正为 `lk`；其余数值与审查报告一致。
- 耗时是**纳秒计时取 3 次最小值**，跑在 Robolectric 的 JVM + tmpfs 上，只代表"这台机器上的
  相对量级"，不是真机数字（真机未测，见报告「未覆盖与未验证清单」）。

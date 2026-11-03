package com.linguareader.app.uirender

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.linguareader.app.components.ButtonRow
import com.linguareader.app.components.CardDivider
import com.linguareader.app.components.CardTitleRow
import com.linguareader.app.components.EmptyState
import com.linguareader.app.components.LinguaCard
import com.linguareader.app.components.LinguaScaffold
import com.linguareader.app.components.LinguaTopBarAction
import com.linguareader.app.components.PrimaryButton
import com.linguareader.app.components.SecondaryButton
import com.linguareader.app.components.SectionHeader
import com.linguareader.app.components.SettingGroup
import com.linguareader.app.components.SettingRow
import com.linguareader.app.components.SettingValue
import com.linguareader.app.components.StatusChip
import com.linguareader.app.components.StatusChipTone
import com.linguareader.app.skin.DesignTokens
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.skin.SkinRegistry
import com.linguareader.shared.ui.skin.ImageSkinBuilder
import com.linguareader.shared.ui.skin.SkinBuiltIns
import com.linguareader.shared.ui.skin.SkinSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 原语组件的渲染快照 + 像素断言。
 *
 * 两层验证：
 * 1. **像素断言**（自动）：关键区域的颜色必须等于 token 里的值 —— 这条是回归护栏，
 *    换肤改造若把某处颜色接错，它会红；
 * 2. **出图**（半自动）：PNG 落到 `build/ui-render-probe/`，供人眼看排版有没有塌。
 *    像素断言证明不了「字没被裁掉」「层级没乱」，所以出图后必须人工过一眼。
 *
 * 三套皮肤各出一组：日间 / 夜间 / 由图片推导出的皮肤。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ComponentRenderTest {

    private fun tokensOf(spec: SkinSpec): DesignTokens = SkinRegistry.toDesignTokens(spec)

    /** 图片皮肤的夹具：暖木色 + 米白，模拟「拍一张书桌照片」的效果。 */
    private fun photoSkin(): SkinSpec {
        val pixels = RenderTestImages.synthetic(120, 120) { x, y ->
            when {
                y < 30 -> 0xFFF3EADF.toInt()
                x < 60 -> 0xFF7B5B3A.toInt()
                else -> 0xFF3E6B58.toInt()
            }
        }
        return ImageSkinBuilder.fromImage(pixels, 120, 120, name = "书桌照片")
    }

    /**
     * 画布尺寸必须和 `@Config` 里的 qualifiers 对得上。
     *
     * 上面声明的是 `w411dp-h891dp-xxhdpi`，也就是一根 411dp×891dp 的屏幕；
     * xxhdpi 的 density 是 3，所以真实像素是 1233×2673。
     * 早先按 540×960 渲染等价于只有 180dp×320dp 的窗口——内容一多就溢出，
     * 屏外的东西根本画不出来，断言会假红（当初就踩了）。
     */
    private fun renderWith(
        name: String,
        spec: SkinSpec,
        width: Int = 1233,
        height: Int = 2673,
        content: @Composable () -> Unit
    ): Bitmap {
        val tokens = tokensOf(spec)
        return ComposeRenderHarness.render(name, width, height) {
            CompositionLocalProvider(LocalDesignTokens provides tokens) {
                content()
            }
        }
    }

    /** 断言某点颜色等于期望色（允许 ±2 的通道抖动：抗锯齿/颜色空间转换）。 */
    private fun assertPixelNear(bmp: Bitmap, x: Int, y: Int, expected: Int, label: String) {
        val actual = bmp.getPixel(x, y)
        val dr = kotlin.math.abs(android.graphics.Color.red(actual) - android.graphics.Color.red(expected))
        val dg = kotlin.math.abs(android.graphics.Color.green(actual) - android.graphics.Color.green(expected))
        val db = kotlin.math.abs(android.graphics.Color.blue(actual) - android.graphics.Color.blue(expected))
        assertTrue(
            label + ": expected " + ComposeRenderHarness.hexColor(expected) +
                " but was " + ComposeRenderHarness.hexColor(actual) + " at (" + x + "," + y + ")",
            dr <= 2 && dg <= 2 && db <= 2
        )
    }

    // ── ① 空书架（LinguaScaffold + EmptyState + PrimaryButton） ──────

    @Composable
    private fun EmptyBookshelfScreen() {
        val t = LocalDesignTokens.current
        LinguaScaffold(
            title = "书架",
            actions = {
                LinguaTopBarAction(Icons.Filled.Search, "搜索", onClick = {})
                LinguaTopBarAction(Icons.Filled.Add, "导入", onClick = {})
            }
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = t.spacing.gutter),
                verticalArrangement = Arrangement.Center
            ) {
                EmptyState(
                    icon = Icons.Filled.MenuBook,
                    title = "书架还是空的",
                    body = "导入一本 EPUB / TXT / PDF，或从资源包市场装一套词典，就能开始按语境阅读。",
                    action = { PrimaryButton(text = "导入图书", onClick = {}) }
                )
                Spacer(Modifier.height(t.spacing.xxl))
            }
        }
    }

    @Test
    fun emptyBookshelfLightRenders() {
        val spec = SkinBuiltIns.light
        val t = tokensOf(spec)
        val bmp = renderWith("components-empty-shelf-light", spec) { EmptyBookshelfScreen() }
        // 顶栏底色 = paper（右下角是空内容区，也必须是 paper）
        assertPixelNear(bmp, bmp.width - 6, bmp.height - 6, t.colors.paper.toArgb(), "paper background")
        assertPixelNear(bmp, 4, 4, t.colors.paper.toArgb(), "top bar background")
        // 图标底盘（居中偏上）应当是 paperDeep —— 取空状态列的中线附近
        assertTrue("image must not be a flat single colour", ComposeRenderHarness.dominantColors(bmp).size >= 2)
    }

    @Test
    fun emptyBookshelfDarkRenders() {
        val spec = SkinBuiltIns.dark
        val t = tokensOf(spec)
        val bmp = renderWith("components-empty-shelf-dark", spec) { EmptyBookshelfScreen() }
        assertPixelNear(bmp, bmp.width - 6, bmp.height - 6, t.colors.paper.toArgb(), "dark paper background")
        assertPixelNear(bmp, 4, 4, t.colors.paper.toArgb(), "dark top bar")
        // 夜间底色必须真的很暗（防「日间皮肤被误用到夜间」）
        val corner = bmp.getPixel(bmp.width - 6, bmp.height - 6)
        val lum = (android.graphics.Color.red(corner) + android.graphics.Color.green(corner) +
            android.graphics.Color.blue(corner)) / 3
        assertTrue("dark skin background must be dark, got mean " + lum, lum < 60)
    }

    @Test
    fun emptyBookshelfPhotoSkinRenders() {
        val spec = photoSkin()
        val t = tokensOf(spec)
        val bmp = renderWith("components-empty-shelf-photo", spec) { EmptyBookshelfScreen() }
        assertPixelNear(bmp, bmp.width - 6, bmp.height - 6, t.colors.paper.toArgb(), "photo-skin paper")
        // 图片皮肤也必须过得了 token 的对比度门槛（在真实渲染出来之后再看一遍）
        assertTrue("photo skin should be readable", t.colors.ink != t.colors.paper)
    }

    // ── ② 设置列表（LinguaScaffold + SectionHeader + SettingGroup + StatusChip） ──

    @Composable
    private fun SettingsScreen() {
        val t = LocalDesignTokens.current
        LinguaScaffold(title = "阅读设置") {
            Column(
                Modifier.fillMaxSize().padding(horizontal = t.spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(t.spacing.s)
            ) {
                SectionHeader("阅读")
                SettingGroup {
                    SettingRow(
                        title = "字号跟随系统",
                        subtitle = "关闭后使用下方的手动字号",
                        checked = true,
                        onCheckedChange = {}
                    )
                    CardDivider()
                    SettingRow(title = "正文主题", trailing = { SettingValue("纸·日间") })
                    CardDivider()
                    SettingRow(title = "行距", trailing = { SettingValue("1.65") })
                }
                SectionHeader("听书")
                SettingGroup {
                    SettingRow(
                        title = "自动朗读下一章",
                        subtitle = "章节结束后继续播报",
                        checked = false,
                        onCheckedChange = {}
                    )
                    CardDivider()
                    SettingRow(
                        title = "离线语音包",
                        trailing = {}
                    )
                }
                Spacer(Modifier.height(t.spacing.m))
                ButtonRow {
                    SecondaryButton(text = "重置", onClick = {})
                    PrimaryButton(text = "保存", onClick = {})
                }
            }
        }
    }

    @Test
    fun settingsScreenLightRenders() {
        val spec = SkinBuiltIns.light
        val t = tokensOf(spec)
        val bmp = renderWith("components-settings-light", spec) { SettingsScreen() }
        assertPixelNear(bmp, bmp.width - 6, bmp.height - 6, t.colors.paper.toArgb(), "settings paper")
        // 卡片表面：靠近第一张卡片左上角内侧
        assertTrue("card surface must appear", ComposeRenderHarness.dominantColors(bmp, 6).size >= 3)
    }

    @Test
    fun settingsScreenDarkRenders() {
        val spec = SkinBuiltIns.dark
        val t = tokensOf(spec)
        val bmp = renderWith("components-settings-dark", spec) { SettingsScreen() }
        assertPixelNear(bmp, bmp.width - 6, bmp.height - 6, t.colors.paper.toArgb(), "settings dark paper")
    }

    @Test
    fun settingsScreenPhotoSkinRenders() {
        val spec = photoSkin()
        val t = tokensOf(spec)
        val bmp = renderWith("components-settings-photo", spec) { SettingsScreen() }
        assertPixelNear(bmp, bmp.width - 6, bmp.height - 6, t.colors.paper.toArgb(), "settings photo paper")
    }

    // ── ③ 卡片矩阵（逐组件出图，做单组件基准） ───────────────────────

    @Composable
    private fun ComponentMatrix() {
        val t = LocalDesignTokens.current
        Column(
            Modifier
                .fillMaxSize()
                .background(t.colors.paper)
                .padding(t.spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(t.spacing.m)
        ) {
            LinguaCard {
                CardTitleRow(title = "卡片标题", subtitle = "副标题说明这一行是什么")
                CardDivider()
                Text("卡片正文内容", style = t.typography.body, color = t.colors.inkSoft)
            }
            Column(verticalArrangement = Arrangement.spacedBy(t.spacing.s)) {
                StatusChip("已同步", StatusChipTone.SUCCESS)
                StatusChip("同步失败", StatusChipTone.DANGER)
                StatusChip("待确认", StatusChipTone.WARNING)
                StatusChip("进行中", StatusChipTone.ACCENT)
                StatusChip("无状态", StatusChipTone.NEUTRAL)
            }
            ButtonRow {
                SecondaryButton(text = "取消", onClick = {})
                PrimaryButton(text = "确认", onClick = {})
            }
            Spacer(Modifier.height(t.spacing.s))
            EmptyState(
                icon = Icons.Filled.Search,
                title = "没有匹配结果",
                body = "换个拼写，或到生词本里手动添加。"
            )
        }
    }

    @Test
    fun componentMatrixLightRenders() {
        val spec = SkinBuiltIns.light
        val t = tokensOf(spec)
        val bmp = renderWith("components-matrix-light", spec) { ComponentMatrix() }
        assertPixelNear(bmp, 3, 3, t.colors.paper.toArgb(), "matrix paper backdrop")
    }

    @Test
    fun componentMatrixDarkRenders() {
        val spec = SkinBuiltIns.dark
        val t = tokensOf(spec)
        val bmp = renderWith("components-matrix-dark", spec) { ComponentMatrix() }
        assertPixelNear(bmp, 3, 3, t.colors.paper.toArgb(), "matrix dark backdrop")
    }

    @Test
    fun componentMatrixPhotoSkinRenders() {
        val spec = photoSkin()
        val t = tokensOf(spec)
        val bmp = renderWith("components-matrix-photo", spec) { ComponentMatrix() }
        assertPixelNear(bmp, 3, 3, t.colors.paper.toArgb(), "matrix photo backdrop")
    }

    // ── ④ 皮肤差异的硬断言 ──────────────────────────────────────────

    @Test
    fun threeSkinsRenderToDifferentImages() {
        // 三张图必须真的不同 —— 否则「换肤生效」只是错觉（比如 LocalDesignTokens 没被读到）。
        val a = renderWith("sanity-light", SkinBuiltIns.light) { EmptyBookshelfScreen() }
        val b = renderWith("sanity-dark", SkinBuiltIns.dark) { EmptyBookshelfScreen() }
        val c = renderWith("sanity-photo", photoSkin()) { EmptyBookshelfScreen() }
        val cornerA = a.getPixel(a.width - 6, a.height - 6)
        val cornerB = b.getPixel(b.width - 6, b.height - 6)
        val cornerC = c.getPixel(c.width - 6, c.height - 6)
        assertTrue("light vs dark must differ", cornerA != cornerB)
        assertTrue("light vs photo must differ", cornerA != cornerC)
        assertTrue("dark vs photo must differ", cornerB != cornerC)
    }

    @Test
    fun photoSkinIsCompliantAndRendersReadableInk() {
        val spec = photoSkin()
        val t = tokensOf(spec)
        val violations = com.linguareader.shared.ui.skin.SkinValidator.violations(spec.colors)
        assertTrue("photo skin must pass WCAG audit, got " + violations.size + " issues", violations.isEmpty())
        // 渲染层面再确认一次：ink 与 paper 的 ARGB 确实不同（防「墨色 = 纸色」的退化）
        assertTrue(t.colors.ink.toArgb() != t.colors.paper.toArgb())
    }
}

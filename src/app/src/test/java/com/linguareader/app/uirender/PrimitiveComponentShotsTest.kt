package com.linguareader.app.uirender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
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
import com.linguareader.app.components.SettingRow
import com.linguareader.app.components.StatusChip
import com.linguareader.app.components.StatusChipTone
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.skin.SkinRegistry
import com.linguareader.shared.ui.skin.SkinBuiltIns
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **8 个原语组件各出一张小图**（Lead 的验收点）。
 *
 * 为什么单独出一张小图：整屏快照里一个组件画错很容易被"整体看着还行"掩盖，
 * 单独裁剪到组件尺寸后，任何塌陷/错位/文字被裁都是一眼可见。
 *
 * 每张图都断言「不是纯色」——即组件确实画出了内容，而不是留了个空盒子。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class PrimitiveComponentShotsTest {

    /**
     * 画布尺寸以 **dp** 为单位。
     *
     * 关键坑：`ComposeRenderHarness.render` 收的是**像素**，而本类用
     * `@Config(qualifiers = "w411dp-h891dp-xxhdpi")`，density = 3。
     * 早先按「像素」写 420×220，等于只有 140dp×73dp 的窗口——卡片标题一出来就被裁掉，
     * 图看着像布局塌了，其实是画布太小。所以这里统一按 dp 传，内部乘 density。
     */
    private fun shot(name: String, widthDp: Int = 320, heightDp: Int = 120, content: @Composable () -> Unit) {
        val density = 3 // xxhdpi，与 @Config 的 qualifiers 对应
        val tokens = SkinRegistry.toDesignTokens(SkinBuiltIns.light)
        val bmp = ComposeRenderHarness.render(name, widthDp * density, heightDp * density) {
            CompositionLocalProvider(LocalDesignTokens provides tokens) {
                Box(Modifier.fillMaxWidth()) { content() }
            }
        }
        // 组件必须真的画出东西：统计与底色不同的像素数。
        val background = android.graphics.Color.WHITE
        var painted = 0
        for (x in 0 until bmp.width step 2) {
            for (y in 0 until bmp.height step 2) {
                if (bmp.getPixel(x, y) != background) painted++
            }
        }
        assertTrue(name + " 看起来是空白的（只有 " + painted + " 个非白像素），组件没有画出来", painted > 20)
    }

    // ① LinguaScaffold（统一顶栏 / 返回箭头 / 标题）
    @Test
    fun linguaScaffold() = shot("prim-1-scaffold", 360, 140) {
        LinguaScaffold(
            title = "书架",
            onBack = {},
            actions = {
                LinguaTopBarAction(Icons.Filled.Search, "搜索", onClick = {})
            }
        ) {
            Text("内容区在此", modifier = Modifier.padding(16.dp))
        }
    }

    // ② LinguaCard（+ CardTitleRow / CardDivider）
    @Test
    fun linguaCard() = shot("prim-2-card", 360, 140) {
        Column(Modifier.padding(16.dp)) {
            LinguaCard {
                CardTitleRow(title = "卡片标题", subtitle = "副标题")
                CardDivider()
                Text("正文内容")
            }
        }
    }

    // ③ SettingRow（标题 + 副标题 + Switch）
    @Test
    fun settingRow() = shot("prim-3-setting-row", 360, 90) {
        Column(Modifier.padding(16.dp)) {
            SettingRow(
                title = "字号跟随系统",
                subtitle = "关闭后使用手动字号",
                checked = true,
                onCheckedChange = {}
            )
        }
    }

    // ④ SectionHeader
    @Test
    fun sectionHeader() = shot("prim-4-section-header", 360, 70) {
        SectionHeader("阅读设置")
    }

    // ⑤ StatusChip（五种语义色）
    @Test
    fun statusChip() = shot("prim-5-status-chip", 360, 110) {
        Column(Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("成功", StatusChipTone.SUCCESS)
                StatusChip("失败", StatusChipTone.DANGER)
            }
            Spacer(Modifier.padding(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("警示", StatusChipTone.WARNING)
                StatusChip("强调", StatusChipTone.ACCENT)
                StatusChip("中性", StatusChipTone.NEUTRAL)
            }
        }
    }

    // ⑥ PrimaryButton / SecondaryButton
    @Test
    fun buttons() = shot("prim-6-buttons", 360, 90) {
        Box(Modifier.padding(16.dp)) {
            ButtonRow {
                SecondaryButton(text = "取消", onClick = {})
                PrimaryButton(text = "确认", onClick = {})
            }
        }
    }

    // ⑦ EmptyState
    @Test
    fun emptyState() = shot("prim-7-empty-state", 360, 260) {
        EmptyState(
            icon = Icons.Filled.MenuBook,
            title = "书架还是空的",
            body = "导入一本书就能开始按语境阅读。",
            action = { PrimaryButton(text = "导入", onClick = {}) }
        )
    }

    // ⑧ LinguaSheet —— ModalBottomSheet 需要真实窗口宿主，单图里用它的内容容器等价呈现：
    //    验证「拖柄 + 标题 + 内容」这套内部布局本身是画得出来的。
    @Test
    fun sheetContents() = shot("prim-8-sheet-contents", 360, 180) {
        val t = LocalDesignTokens.current
        Column(
            Modifier
                .fillMaxWidth()
                .background(t.colors.paper)
                .padding(horizontal = t.spacing.l)
                .padding(bottom = t.spacing.xl)
        ) {
            com.linguareader.app.components.SheetDragHandle()
            Text(text = "存储占用", style = t.typography.title, color = t.colors.ink)
            com.linguareader.app.components.SheetDivider()
            Text(
                text = "这里是弹层内容区",
                style = t.typography.body,
                color = t.colors.inkSoft,
                modifier = Modifier.padding(top = t.spacing.m)
            )
        }
    }
}

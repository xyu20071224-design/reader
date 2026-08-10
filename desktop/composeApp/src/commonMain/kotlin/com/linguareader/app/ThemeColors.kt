package com.linguareader.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ── 纸质书配色系统 ──────────────────────────────────────────────
internal val Paper = Color(0xFFF7F3EA)        // 主背景：米白纸
internal val PaperDeep = Color(0xFFF0E8D9)    // 次级背景：旧纸
internal val CardSurface = Color(0xFFFFFBF4)  // 卡片表面：暖白
internal val Ink = Color(0xFF27231F)          // 主文字：墨色
internal val InkSoft = Color(0xFF6F665C)      // 次级文字
internal val InkFaint = Color(0xFF9C938A)     // 弱化文字/占位
internal val Accent = Color(0xFF8D5535)       // 主强调：棕褐
internal val AccentDeep = Color(0xFF6F4127)   // 深强调：按压态
internal val AccentSoft = Color(0xFFE7D3BC)   // 浅强调底：选中胶囊
internal val Gold = Color(0xFFC99B3F)         // 点缀：书签金
internal val Success = Color(0xFF4E7A57)      // 认识/掌握
internal val Danger = Color(0xFFB0493E)       // 删除/危险
internal val BookCoverFallback = Color(0xFFE1D5C2) // 无封面书的底色

// ── 形状与层级规范 ──────────────────────────────────────────────
internal val CardShape = RoundedCornerShape(14.dp)
internal val SmallShape = RoundedCornerShape(9.dp)
internal val PillShape = RoundedCornerShape(50)

internal val AppShapes = Shapes(
    small = SmallShape,
    medium = CardShape,
    large = RoundedCornerShape(20.dp)
)

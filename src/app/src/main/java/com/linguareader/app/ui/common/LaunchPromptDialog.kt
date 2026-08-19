package com.linguareader.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.linguareader.app.LaunchPromptUi
import com.linguareader.app.R
import com.linguareader.app.data.Greeting
import com.linguareader.app.data.GreetingPeriod
import com.linguareader.app.data.UpdateNote
import com.linguareader.app.ui.theme.Accent
import com.linguareader.app.ui.theme.CardShape
import com.linguareader.app.ui.theme.CardSurface
import com.linguareader.app.ui.theme.Ink
import com.linguareader.app.ui.theme.InkSoft
import com.linguareader.app.ui.theme.PillShape

/** Launch greeting scene card, or one-time update note after an app update (F-144). */
@Composable
internal fun LaunchPromptDialog(
    prompt: LaunchPromptUi,
    onDismiss: () -> Unit
) {
    when (prompt) {
        is LaunchPromptUi.GreetingPrompt -> GreetingSceneDialog(prompt.greeting, onDismiss)
        is LaunchPromptUi.UpdatePrompt -> UpdateNoteDialog(prompt.note, onDismiss)
    }
}

@Composable
private fun GreetingSceneDialog(greeting: Greeting, onDismiss: () -> Unit) {
    val accent = greeting.period.accent()
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = CardSurface,
            shadowElevation = 10.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(accent.copy(alpha = 0.16f), Color.Transparent)
                            )
                        )
                        .padding(vertical = 22.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(accent.copy(alpha = 0.18f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(greeting.period.iconRes()),
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = greeting.title,
                            fontFamily = FontFamily.Serif,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Medium,
                            color = Ink
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = greeting.period.hoursLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = InkSoft
                        )
                    }
                }
                Text(
                    text = greeting.message,
                    fontFamily = FontFamily.Serif,
                    fontSize = 15.sp,
                    lineHeight = 26.sp,
                    textAlign = TextAlign.Center,
                    color = Ink,
                    modifier = Modifier.padding(top = 6.dp, bottom = 22.dp)
                )
                Button(
                    onClick = onDismiss,
                    shape = PillShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("开始阅读", modifier = Modifier.padding(vertical = 2.dp))
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun UpdateNoteDialog(note: UpdateNote, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了", color = Accent) }
        },
        title = { Text(note.title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Text(
                note.items.joinToString("\n") { "· $it" },
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                modifier = Modifier.padding(top = 4.dp)
            )
        },
        containerColor = CardSurface,
        shape = CardShape
    )
}

/** Per-period accent used by the greeting scene card. */
private fun GreetingPeriod.accent(): Color = when (this) {
    GreetingPeriod.DAWN -> Color(0xFFC97A45)   // 朝阳
    GreetingPeriod.NOON -> Color(0xFFA87E22)   // 烈日
    GreetingPeriod.DUSK -> Color(0xFF9A5D42)   // 晚霞
    GreetingPeriod.NIGHT -> Color(0xFF43506C)  // 星河
}

/** Per-period scene icon. */
private fun GreetingPeriod.iconRes(): Int = when (this) {
    GreetingPeriod.DAWN -> R.drawable.ic_greeting_dawn
    GreetingPeriod.NOON -> R.drawable.ic_greeting_noon
    GreetingPeriod.DUSK -> R.drawable.ic_greeting_dusk
    GreetingPeriod.NIGHT -> R.drawable.ic_greeting_night
}

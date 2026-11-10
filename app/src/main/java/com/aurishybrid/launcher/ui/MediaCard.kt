package com.aurishybrid.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurishybrid.launcher.media.MediaState
import com.aurishybrid.launcher.ui.theme.AurisColors

class MediaActions(
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onOpen: () -> Unit,
    val onGrantAccess: () -> Unit,
)

/** Карточка «Сейчас играет»: обложка, трек, исполнитель, управление активной медиасессией. */
@Composable
fun MediaCard(state: MediaState, actions: MediaActions, modifier: Modifier) {
    val now = state.now
    Row(
        modifier.card(if (state.hasAccess) actions.onOpen else actions.onGrantAccess).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(78.dp).clip(RoundedCornerShape(10.dp)).background(AurisColors.Graphite),
            contentAlignment = Alignment.Center
        ) {
            val art = now?.art
            if (art != null) {
                Image(bitmap = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(78.dp))
            } else {
                GlyphIcon(Glyph.MUSIC, AurisColors.FgDim, Modifier.size(36.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            when {
                !state.hasAccess -> {
                    Line("Сейчас играет", AurisColors.Fg, 16, bold = true)
                    Line("Нужен доступ к уведомлениям", AurisColors.FgDim, 13)
                    Line("Нажмите, чтобы разрешить", AurisColors.Cyan, 13)
                }
                now == null -> {
                    Line("Ничего не играет", AurisColors.Fg, 16, bold = true)
                    Line("Нажмите, чтобы открыть аудио", AurisColors.FgDim, 13)
                }
                else -> {
                    Line(now.title ?: "Без названия", AurisColors.Fg, 17, bold = true)
                    Line(now.artist ?: "—", AurisColors.FgDim, 14)
                    Line(now.appLabel, AurisColors.Cyan, 12)
                }
            }
        }
        if (now != null) {
            Spacer(Modifier.width(6.dp))
            MediaButton(Glyph.PREV, false, actions.onPrevious)
            MediaButton(if (now.playing) Glyph.PAUSE else Glyph.PLAY, true, actions.onPlayPause)
            MediaButton(Glyph.NEXT, false, actions.onNext)
        }
    }
}

@Composable
private fun Line(text: String, color: Color, sizeSp: Int, bold: Boolean = false) {
    Text(
        text, color = color, fontSize = sizeSp.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium
    )
}

@Composable
private fun MediaButton(glyph: Glyph, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .size(if (primary) 50.dp else 44.dp)
            .clip(CircleShape)
            .background(if (primary) AurisColors.Blue else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        GlyphIcon(glyph, AurisColors.Fg, Modifier.size(if (primary) 24.dp else 22.dp))
    }
}

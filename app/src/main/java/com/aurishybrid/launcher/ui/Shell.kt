package com.aurishybrid.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurishybrid.launcher.data.ObdSnapshot
import com.aurishybrid.launcher.data.ObdState
import com.aurishybrid.launcher.data.VehicleData
import com.aurishybrid.launcher.ui.theme.AurisColors
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMMM", Locale.getDefault())

const val NA = "N/A"

fun fmt(v: Float?, decimals: Int): String =
    v?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: NA

fun ObdSnapshot.statusText(): String = when (state) {
    ObdState.DISABLED -> "OBD2 выкл."
    ObdState.IDLE -> "OBD2: не активен"
    ObdState.PAUSED -> "OBD2: проверка адаптера"
    ObdState.NO_PERMISSION -> "OBD2: нет разрешения"
    ObdState.NO_DEVICE -> "OBD2: адаптер не выбран"
    ObdState.CONNECTING -> "OBD2: подключение…"
    ObdState.RECONNECTING -> "OBD2: переподключение"
    ObdState.CONNECTED -> if (proxyClientActive) "OBD2 · общий с HA" else "OBD2 подключён"
    ObdState.ERROR -> "OBD2: ошибка"
}

/** Пункт боковой панели. */
data class NavItem(val key: String, val glyph: Glyph, val label: String, val enabled: Boolean = true)

/**
 * Каркас экрана: вертикальная панель разделов слева, строка состояния сверху, содержимое справа.
 */
@Composable
fun Shell(
    items: List<NavItem>,
    selected: String,
    onSelect: (NavItem) -> Unit,
    title: String,
    data: VehicleData,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxSize()
            .background(AurisColors.Night)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        SideBar(items, selected, onSelect)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            StatusBar(title, data)
            content()
        }
    }
}

@Composable
private fun SideBar(items: List<NavItem>, selected: String, onSelect: (NavItem) -> Unit) {
    Column(
        Modifier
            .width(92.dp)
            .fillMaxHeight()
            .background(AurisColors.Navy)
            .padding(vertical = 6.dp)
    ) {
        items.forEach { item ->
            val isSel = item.key == selected
            val tint = when {
                isSel -> AurisColors.Fg
                item.enabled -> AurisColors.FgDim
                else -> AurisColors.FgDim.copy(alpha = .45f)
            }
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable { onSelect(item) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight(.6f)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isSel) AurisColors.Blue else Color.Transparent)
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSel) AurisColors.Blue.copy(alpha = .18f) else Color.Transparent)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    GlyphIcon(item.glyph, if (isSel) AurisColors.Cyan else tint, Modifier.size(30.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.label, color = tint, fontSize = 11.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBar(title: String, data: VehicleData) {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = AurisColors.Fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.weight(1f))
        ObdIndicator(data.obd)
        Spacer(Modifier.width(18.dp))
        Text("12V", color = AurisColors.FgDim, fontSize = 13.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            data.voltage12?.let { fmt(it, 1) + " В" } ?: NA,
            color = voltageColor(data.voltage12), fontSize = 16.sp, fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(18.dp))
        Text(
            now.format(DATE_FMT).replaceFirstChar { it.titlecase(Locale.getDefault()) },
            color = AurisColors.FgDim, fontSize = 14.sp, maxLines = 1
        )
        Spacer(Modifier.width(14.dp))
        Text(now.format(TIME_FMT), color = AurisColors.Fg, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    }
}

private fun voltageColor(v: Float?): Color = when {
    v == null -> AurisColors.FgDim
    v < 11.8f || v > 15.0f -> AurisColors.Amber
    else -> AurisColors.Fg
}

@Composable
private fun ObdIndicator(obd: ObdSnapshot) {
    val c = when (obd.state) {
        ObdState.CONNECTED -> AurisColors.Green
        ObdState.CONNECTING, ObdState.RECONNECTING -> AurisColors.Amber
        ObdState.ERROR -> AurisColors.Red
        else -> AurisColors.FgDim
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(7.dp))
        Text(obd.statusText(), color = AurisColors.FgDim, fontSize = 13.sp, maxLines = 1)
    }
}

val CardShape = RoundedCornerShape(16.dp)

/** Базовая карточка интерфейса. */
fun Modifier.card(onClick: (() -> Unit)? = null): Modifier {
    val m = this.clip(CardShape).background(AurisColors.Panel).border(1.dp, AurisColors.Line, CardShape)
    return if (onClick != null) m.clickable(onClick = onClick) else m
}

@Composable
fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = AurisColors.FgDim, fontSize = 12.sp, letterSpacing = 1.2.sp,
        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier
    )
}

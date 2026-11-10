package com.aurishybrid.launcher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurishybrid.launcher.apps.AppEntry
import com.aurishybrid.launcher.apps.AppLauncher
import com.aurishybrid.launcher.ui.theme.AurisColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Все установленные приложения. Долгое нажатие — системная страница «О приложении». */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(apps: AppLauncher, refreshKey: Int, onLaunch: (AppEntry) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val list by produceState(initialValue = emptyList<AppEntry>(), refreshKey) {
        value = withContext(Dispatchers.Default) { apps.launchableApps() }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(list, key = { it.packageName }) { app ->
            val icon by produceState<ImageBitmap?>(initialValue = null, app.packageName) {
                value = withContext(Dispatchers.IO) { apps.icon(app.packageName) }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .combinedClickable(
                        onClick = { onLaunch(app) },
                        onLongClick = { openAppInfo(context, app.packageName) }
                    )
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    val bmp = icon
                    if (bmp != null) {
                        Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(64.dp))
                    } else {
                        GlyphIcon(Glyph.GRID, AurisColors.FgDim, Modifier.size(36.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    app.label, color = AurisColors.Fg, fontSize = 13.sp, maxLines = 2, minLines = 2,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = 16.sp
                )
            }
        }
    }
}

private fun openAppInfo(ctx: Context, pkg: String) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        // на прошивке нет такой страницы настроек — ничего не делаем
    }
}

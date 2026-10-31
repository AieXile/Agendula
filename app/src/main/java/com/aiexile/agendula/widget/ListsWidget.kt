package com.aiexile.agendula.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.unit.ColorProvider as GlanceColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.aiexile.agendula.MainActivity
import com.aiexile.agendula.R
import com.aiexile.agendula.ui.theme.AgendulaDarkFallback
import com.aiexile.agendula.ui.theme.AgendulaLightFallback
import de.jeanlucmakiola.floret.components.pastelize
import de.jeanlucmakiola.floret.glance.FloretGlanceTheme
import de.jeanlucmakiola.floret.glance.WidgetActionButton
import de.jeanlucmakiola.floret.glance.WidgetActionStyle
import de.jeanlucmakiola.floret.glance.WidgetBackground
import de.jeanlucmakiola.floret.glance.WidgetMessage
import de.jeanlucmakiola.floret.glance.WidgetSurface
import de.jeanlucmakiola.floret.glance.WidgetThemeMode
import de.jeanlucmakiola.floret.glance.WidgetTile
import de.jeanlucmakiola.floret.glance.R as GlanceR
import kotlinx.coroutines.flow.first
import kotlin.math.min

/**
 * "Lists" home-screen widget: one shortcut tile per list, in the list's own
 * colour, showing its open count — navigation to a list rather than a
 * preview of its tasks, which is what [TaskWidget] already does.
 */
class ListsWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    // Exact, not Responsive: the grid is sized to the real placement so every
    // tile fits without scrolling.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = runCatching { context.widgetStats().first() }.getOrDefault(WidgetStatsData.Unavailable)
        provideContent {
            val background = parseWidgetBackground(currentState(WIDGET_BACKGROUND_KEY))
            val themeOverride = parseWidgetThemeOverride(currentState(WIDGET_THEME_KEY))
            val listIds = parseWidgetListIds(currentState(WIDGET_LIST_IDS_KEY))
            val data by remember { context.widgetStats() }.collectAsState(initial = initial)
            val ready = data as? WidgetStatsData.Ready ?: initial as? WidgetStatsData.Ready
            FloretGlanceTheme(
                lightScheme = AgendulaLightFallback,
                darkScheme = AgendulaDarkFallback,
                dynamicColor = ready?.snapshot?.dynamicColor ?: true,
                mode = ready?.let { effectiveThemeMode(it.snapshot.themeMode, themeOverride) } ?: WidgetThemeMode.SYSTEM,
            ) {
                ListsWidgetBody(data, background, listIds)
            }
        }
    }
}

@Composable
private fun ListsWidgetBody(data: WidgetStatsData?, background: WidgetBackground, listIds: Set<Long>?) {
    val context = LocalContext.current
    val ready = data as? WidgetStatsData.Ready
    val tiles = ready?.snapshot?.lists?.let { lists -> listIds?.let { ids -> lists.filter { it.id in ids } } ?: lists }
    val size = LocalSize.current
    WidgetSurface(background = background) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(Padding - Gap / 2)) {
            when {
                data == null -> Unit
                ready == null -> WidgetMessage(context.getString(R.string.widget_unavailable))
                tiles.isNullOrEmpty() -> WidgetMessage(context.getString(R.string.widget_lists_empty))
                else -> {
                    val grid = listGrid(tiles.size, size.width - Padding * 2, size.height - Padding * 2)
                    val rows = tiles.take(grid.columns * grid.rows).chunked(grid.columns)
                    // Gaps are each tile's padding rather than Spacers, which would
                    // eat into Glance's 10-children-per-Row/Column cap.
                    rows.forEach { rowTiles ->
                        Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                            rowTiles.forEach { tile ->
                                // Glance has no margin — padding on the tile itself lands inside its fill.
                                Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight().padding(Gap / 2)) {
                                    ListTileCell(tile, grid, GlanceModifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal data class ListGrid(val columns: Int, val rows: Int, val tileWidth: Dp, val tileHeight: Dp)

/**
 * Picks the column count whose tiles come closest to [TileAspect] at the
 * largest size, among those that fit [count] tiles into [width] x [height].
 * Tiles too narrow for the one-line layout must be tall enough to stack, so a
 * narrow widget shows fewer, readable tiles. Lists that don't fit are dropped
 * rather than scrolled.
 */
internal fun listGrid(count: Int, width: Dp, height: Dp): ListGrid {
    fun span(total: Dp, cells: Int) = (total - Gap * (cells - 1)) / cells
    fun maxRows(columns: Int): Int {
        val minHeight = if (span(width, columns) < MinOneLineTileWidth) MinNarrowTileHeight else MinTileHeight
        return ((height + Gap) / (minHeight + Gap)).toInt().coerceIn(1, MaxCells)
    }
    val maxColumns = ((width + Gap) / (MinTileWidth + Gap)).toInt().coerceIn(1, MaxCells)
    val best = (1..min(count, maxColumns))
        .map { columns -> columns to (count + columns - 1) / columns }
        .filter { (columns, rows) -> rows <= maxRows(columns) }
        .maxByOrNull { (columns, rows) -> min(span(width, columns).value / TileAspect, span(height, rows).value) }
    val (columns, rows) = best ?: (maxColumns to maxRows(maxColumns))
    return ListGrid(columns, rows, span(width, columns), span(height, rows))
}

@Composable
private fun ListTileCell(tile: ListTile, grid: ListGrid, modifier: GlanceModifier) {
    val context = LocalContext.current
    val tint = ColorProvider(day = pastelize(tile.color, false), night = pastelize(tile.color, true))
    val onTint = GlanceColorProvider(Color.White)
    val showAdd = tile.acceptsWrites && grid.tileWidth >= MinTileWidthWithAdd
    WidgetTile(
        color = tint,
        modifier = modifier,
        onClick = actionStartActivity(MainActivity.listIntent(context, tile.id)),
    ) {
        if (grid.tileWidth < MinOneLineTileWidth && grid.tileHeight >= MinNarrowTileHeight) {
            // Narrow: the name gets the full width and wraps; count and + share the bottom row.
            Column(modifier = GlanceModifier.fillMaxSize()) {
                Text(
                    text = tile.name,
                    maxLines = if (grid.tileHeight >= MinTwoLineNameHeight) 2 else 1,
                    style = TextStyle(color = onTint, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                )
                Spacer(GlanceModifier.defaultWeight())
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${tile.openCount}",
                        modifier = GlanceModifier.defaultWeight(),
                        style = TextStyle(color = onTint, fontSize = 18.sp, fontWeight = FontWeight.Medium),
                    )
                    if (showAdd) ListAddButton(tile)
                }
            }
        } else if (grid.tileHeight >= MinStackedTileHeight) {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tile.name,
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight(),
                        style = TextStyle(color = onTint, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    )
                    if (showAdd) ListAddButton(tile)
                }
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    text = "${tile.openCount}",
                    style = TextStyle(color = onTint, fontSize = 18.sp, fontWeight = FontWeight.Medium),
                )
            }
        } else {
            Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tile.name,
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(color = onTint, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                )
                Text(
                    text = "${tile.openCount}",
                    modifier = GlanceModifier.padding(horizontal = 4.dp),
                    style = TextStyle(color = onTint, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                )
                if (showAdd) ListAddButton(tile)
            }
        }
    }
}

@Composable
private fun ListAddButton(tile: ListTile) {
    val context = LocalContext.current
    WidgetActionButton(
        icon = ImageProvider(GlanceR.drawable.floret_ic_widget_add),
        contentDescription = context.getString(R.string.new_task),
        action = actionStartActivity(MainActivity.newTaskIntent(context, tile.id)),
        style = WidgetActionStyle.TONAL,
        size = 20.dp,
    )
}

private val Padding = 8.dp
private val Gap = 8.dp
private val MinTileWidth = 72.dp
private val MinTileHeight = 36.dp
private val MinTileWidthWithAdd = 64.dp
private val MinStackedTileHeight = 60.dp
private val MinNarrowTileHeight = 52.dp
private val MinTwoLineNameHeight = 70.dp
private val MinOneLineTileWidth = 120.dp
private const val TileAspect = 1.5f

/** Glance's per-Row/Column child cap. */
private const val MaxCells = 10

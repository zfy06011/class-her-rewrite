package com.classher.timetable.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement as LayoutArrangement
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlin.math.floor

private class PixelShape(private val corner: Dp = 8.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { corner.toPx() }.coerceAtMost(minOf(size.width, size.height) / 4)
        val s = c / 2; val w = size.width; val h = size.height
        return Outline.Generic(Path().apply {
            moveTo(c, 0f); lineTo(w - c, 0f); lineTo(w - c, s); lineTo(w - s, s); lineTo(w - s, c); lineTo(w, c)
            lineTo(w, h - c); lineTo(w - s, h - c); lineTo(w - s, h - s); lineTo(w - c, h - s); lineTo(w - c, h)
            lineTo(c, h); lineTo(c, h - s); lineTo(s, h - s); lineTo(s, h - c); lineTo(0f, h - c)
            lineTo(0f, c); lineTo(s, c); lineTo(s, s); lineTo(c, s); close()
        })
    }
}

@Composable
internal fun PaperBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val grid = LocalPaperColors.current.grid
    Box(modifier.background(MaterialTheme.colorScheme.background).drawBehind {
        val unit = 10.dp.toPx()
        var x = 0f; while (x < size.width) { drawLine(grid.copy(alpha = .45f), Offset(x, 0f), Offset(x, size.height), .5.dp.toPx()); x += unit }
        var y = 0f; while (y < size.height) { drawLine(grid.copy(alpha = .45f), Offset(0f, y), Offset(size.width, y), .5.dp.toPx()); y += unit }
    }, content = content)
}

@Composable
internal fun PixelPanel(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surface,
    shadow: Boolean = true, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val palette = LocalPaperColors.current; val shape = PixelShape()
    val decorated = if (!shadow) modifier else modifier.drawBehind {
        val path = (shape.createOutline(size, layoutDirection, this) as Outline.Generic).path
        withTransform({ translate(4.dp.toPx(), 5.dp.toPx()) }) { drawPath(path, palette.shadow.copy(alpha = .4f)) }
    }
    if (onClick == null) Surface(modifier = decorated, color = color, contentColor = MaterialTheme.colorScheme.onSurface,
        shape = shape, border = BorderStroke(1.5.dp, palette.ink), content = content)
    else Surface(onClick = onClick, modifier = decorated, color = color, contentColor = MaterialTheme.colorScheme.onSurface,
        shape = shape, border = BorderStroke(1.5.dp, palette.ink), content = content)
}

@Composable
internal fun PixelBrand() {
    val pink = LocalPaperColors.current.accent
    val highlight = LocalPaperColors.current.highlight
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                Text("双轨", Modifier.offset(2.dp, 3.dp), style = MaterialTheme.typography.displayLarge, color = pink)
                Text("双轨", style = MaterialTheme.typography.displayLarge, color = LocalPaperColors.current.ink)
            }
            Canvas(Modifier.padding(start = 12.dp).size(28.dp, 44.dp)) {
                pixelCross(pink, Offset(size.width * .35f, size.height * .2f), 3.dp.toPx())
                pixelCross(pink, Offset(size.width * .7f, size.height * .8f), 2.dp.toPx())
                pixelCross(highlight, Offset(size.width * .85f, size.height * .5f), 2.dp.toPx())
            }
        }
        Text("今天也按自己的节奏", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Canvas(Modifier.padding(top = 6.dp).width(152.dp).height(4.dp)) {
            drawRect(pink, size = Size(size.width - 8.dp.toPx(), 2.dp.toPx()))
            drawRect(pink, Offset(size.width - 8.dp.toPx(), 0f), Size(3.dp.toPx(), 3.dp.toPx()))
            drawRect(pink, Offset(size.width - 2.dp.toPx(), 2.dp.toPx()), Size(2.dp.toPx(), 2.dp.toPx()))
        }
    }
}

@Composable
internal fun PixelNavigation(page: Int, onPage: (Int) -> Unit) {
    val colors = LocalCourseColors.current
    PixelPanel(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 8.dp, end = 12.dp, bottom = 12.dp), colors[0]) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            listOf("首页", "课表", "我的").forEachIndexed { index, label ->
                Column(Modifier.weight(1f).selectable(selected = page == index, onClick = { onPage(index) }, role = Role.Tab)
                    .heightIn(min = 60.dp).padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(color = if (page == index) LocalPaperColors.current.accent.copy(alpha = .28f) else Color.Transparent,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)) {
                        Box(Modifier.width(76.dp).padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
                            PixelIcon(listOf(PixelGlyph.HOME, PixelGlyph.CALENDAR, PixelGlyph.PERSON)[index], Modifier.size(28.dp),
                                ink = if (page == index) LocalPaperColors.current.ink else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(label, style = MaterialTheme.typography.titleMedium, color = if (page == index) LocalPaperColors.current.ink else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun PixelAction(label: String, glyph: PixelGlyph, color: Color, enabled: Boolean = true, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // M3 Surface supplies ripple and button semantics; disabled actions remain noninteractive.
    PixelPanel(modifier.alphaFor(enabled).semantics(mergeDescendants = true) { role = Role.Button; if (!enabled) disabled() }, color, onClick = if (enabled) onClick else null) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = LayoutArrangement.spacedBy(8.dp)) {
            PixelIcon(glyph, Modifier.size(24.dp)); Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
private fun Modifier.alphaFor(enabled: Boolean): Modifier = this.then(if (enabled) Modifier else Modifier.graphicsLayer { alpha = .45f })

@Composable
internal fun PixelRule(modifier: Modifier = Modifier, color: Color = LocalPaperColors.current.ink) {
    Canvas(modifier.height(2.dp)) { drawLine(color, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 2.dp.toPx()))) }
}

@Composable
internal fun PixelVerticalRule(modifier: Modifier = Modifier) {
    val ink = LocalPaperColors.current.ink
    Canvas(modifier.width(1.dp)) { drawLine(ink, Offset.Zero, Offset(0f, size.height), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 2.dp.toPx()))) }
}

internal enum class PixelGlyph { BOOK, CALENDAR, HOME, PERSON, LIST, CLOCK, HOURGLASS, SCHOOL, PLUS }
private val glyphs = mapOf(
    PixelGlyph.BOOK to listOf("................", "..XXXX...XXXX...", ".XBBBWX.XWBBBX..", ".XBBBWX.XWBBBX..", ".XBBBWX.XWBBBX..", ".XBBBWXXXWBBBX..", ".XBBBWWWWBBBBX..", ".XBBBWWWWBBBBX..", ".XBBBWWWWBBBBX..", ".XXXXXXXXXXXXX..", "..BBBB...BBBB..."),
    PixelGlyph.CALENDAR to listOf("....X.....X.....", "....X.....X.....", "..XXXXXXXXXXXX..", "..XPPPPPPPPPPX..", "..XPPPPPPPPPPX..", "..XWWWWWWWWWWX..", "..XWWXWWXWWXWX..", "..XWWWWWWWWWWX..", "..XWWXWWXWWXWX..", "..XWWWWWWWWWWX..", "..XWWXWWXWWXWX..", "..XWWWWWWWWWWX..", "..XXXXXXXXXXXX.."),
    PixelGlyph.HOME to listOf(".......XX.......", "......XPPX......", ".....XPPPPX.....", "....XPPPPPPX....", "...XWWWWWWWWX...", "..XWWWWWWWWWWX..", ".XWWWWWWWWWWWWX.", "...XWWWWWWWWX...", "...XWWWWWWWWX...", "...XWWXXXWWWX...", "...XWWXPPXWWX...", "...XWWXPPXWWX...", "...XXXXXXXXXX..."),
    PixelGlyph.PERSON to listOf("......XXXX......", ".....XWWWWX.....", ".....XWWWWX.....", ".....XWWWWX.....", "......XWWX......", ".......XX.......", "......XWWX......", "....XXWWWWXX....", "...XWWWWWWWWX...", "..XWWWWWWWWWWX..", "..XWWWWWWWWWWX..", "..XXXXXXXXXXXX.."),
    PixelGlyph.LIST to listOf("..XXXXXXXXXX....", "..XWWWWWWWWXX...", "..XWWWWWWWWXPX..", "..XWXXXXXXWXPX..", "..XWWWWWWWWXPX..", "..XWXXXXXXWXPX..", "..XWWWWWWWWXPX..", "..XWXXXXXXWXPX..", "..XWWWWWWWWXPX..", "..XXXXXXXXXXPX..", "...XPPPPPPPPPX..", "....XXXXXXXXXX.."),
    PixelGlyph.CLOCK to listOf(".....XXXXXX.....", "...XXWWWWWWXX...", "..XWWWWWWWWWWX..", "..XWWWWXWWWWWX..", ".XWWWWWXWWWWWWX.", ".XWWWWWXWWWWWWX.", ".XWWWWWXXXWWWWX.", ".XWWWWWWWWWWWWX.", "..XWWWWWWWWWWX..", "..XWWWWWWWWWWX..", "...XXWWWWWWXX...", ".....XXXXXX....."),
    PixelGlyph.HOURGLASS to listOf("...XXXXXXXXXX...", "....XBBBBBBX....", "....XWBBBBWX....", ".....XWBBWX.....", "......XBBX......", ".......XX.......", "......XYYX......", ".....XWYYWX.....", "....XWYYYYWX....", "....XYYYYYYX....", "...XXXXXXXXXX..."),
    PixelGlyph.SCHOOL to listOf(".......XX.......", "......XPPX......", ".....XWWWWX.....", ".....XWXXWX.....", ".....XWWWWX.....", "..XXXXWWWWXXXX..", "..XBBXWWWWXBBX..", "..XBBXWWWWXBBX..", "..XBBXWXXWXBBX..", "..XBBXWPPWXBBX..", "..XXXXXXXXXXXX.."),
    PixelGlyph.PLUS to listOf(".......XX.......", ".......XX.......", ".......XX.......", ".......XX.......", "...XXXXXXXXXX...", "...XXXXXXXXXX...", ".......XX.......", ".......XX.......", ".......XX.......", ".......XX......."),
)

@Composable
internal fun PixelIcon(glyph: PixelGlyph, modifier: Modifier = Modifier, ink: Color = LocalPaperColors.current.ink) {
    val courses = LocalCourseColors.current; val paper = LocalPaperColors.current
    val colors = mapOf('X' to ink, 'W' to MaterialTheme.colorScheme.surface, 'B' to courses[1], 'P' to courses[0], 'Y' to paper.sun)
    Canvas(modifier) { sprite(glyphs.getValue(glyph), colors, 16) }
}
private fun DrawScope.sprite(rows: List<String>, colors: Map<Char, Color>, columns: Int) {
    val scale = floor(minOf(size.width / columns, size.height / rows.size)).coerceAtLeast(1f)
    val left = (size.width - columns * scale) / 2; val top = (size.height - rows.size * scale) / 2
    rows.forEachIndexed { y, row -> row.forEachIndexed { x, symbol -> colors[symbol]?.let {
        drawRect(it, Offset(left + x * scale, top + y * scale), Size(scale, scale))
    } } }
}
private fun DrawScope.pixelCross(color: Color, at: Offset, unit: Float) {
    drawRect(color, at + Offset(unit, 0f), Size(unit, unit * 3)); drawRect(color, at + Offset(0f, unit), Size(unit * 3, unit))
}

@Composable
internal fun PixelBookStack(modifier: Modifier = Modifier) {
    val colors = LocalCourseColors.current; val paper = LocalPaperColors.current; val white = MaterialTheme.colorScheme.surface
    val rows = listOf("......XXXXXXXXXXXX......", ".....XBBBBBBBBBBBX......", ".....XBBBBBBBBBBBXPPX...", "....XBBWWWWBBBBBXPPPX...", "....XBBWWWBBBBBBXPPPX...", "...XBBBBBBBBBBBXPPPPX...", "...XBBBBBBBBBBBXPPPPX...", "..XBBBBBBBBBBBXPPPPPX...", "..XBBBBBBBBBBBXPPPPPX...", ".XBBBBBBBBBBBXPPPPPPX...", ".XXXXXXXXXXXXPPPPPPPX...", ".XWWWWWWWWWWXPPPPPPPX...", ".XWWWWWWWWWWXPPPPPPPX...", ".XXXXXXXXXXXXPPPPPPPX...", "..XPPPPPPPPPPPPPPPPX....", "..XXXXXXXXXXXXXXXXXX....", "...XBBBBBBBBBBBBBBX.....", "...XXXXXXXXXXXXXXXX.....", "....CCCCCCCCCCCCCC......")
    Canvas(modifier) { sprite(rows, mapOf('X' to paper.ink, 'B' to colors[1], 'P' to colors[0], 'W' to white, 'C' to paper.shadow), 24) }
}

@Composable
internal fun SleepingCat(modifier: Modifier = Modifier) {
    val p = LocalPaperColors.current; val white = MaterialTheme.colorScheme.surface
    val rows = listOf(".................ZZ....Z....", "..............ZZ...ZZ.......", "............................", ".........PP.................", "........XXXX................", "......XXWWWWXX..............", "....XXXWWWWWWWXXX...........", "...XCCWWWWWWWWWWWXX.....XX..", "..XCCCWWWWWWWWWWWWX....XWWX.", "..XWWWWWXWWWWXWWWWX....XWWX.", ".XWWWWWWWWPPWWWWWWWX...XWWX.", ".XWWWPWWWWXXWWW PWWX..XWWX..".replace(" ", ""), "..XWWWWWWWWWWWWWWWWXXXXXX...", "...XXXWWWWWWWWCCCCCXXX......", "......XXXXXXXXXXXXX.........", ".......CCCCCCCCCCC..........").map { it.padEnd(28, '.') }
    Canvas(modifier) { sprite(rows, mapOf('X' to p.ink, 'W' to white, 'P' to p.accent, 'C' to p.shadow, 'Z' to p.highlight), 28) }
}

@Composable
internal fun EmptyToday(hasTerm: Boolean) {
    val ink = LocalPaperColors.current.ink; val pink = LocalPaperColors.current.accent
    Box(Modifier.fillMaxWidth().drawBehind {
        drawRoundRect(ink, style = androidx.compose.ui.graphics.drawscope.Stroke(1.3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
    }.padding(20.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = LayoutArrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(28.dp)) { pixelCross(pink, Offset.Zero, 3.dp.toPx()) }
                SleepingCat(Modifier.size(128.dp, 80.dp))
                Canvas(Modifier.size(28.dp)) { pixelCross(pink, Offset(size.width / 2, size.height / 2), 2.dp.toPx()) }
            }
            Text("今天没有课程，好好安排自己的时间。", style = MaterialTheme.typography.titleSmall)
            PixelRule(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f))
            Text(if (hasTerm) "按自己的节奏，享受今天。" else "在“我的”登录学校、获取并确认导入。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

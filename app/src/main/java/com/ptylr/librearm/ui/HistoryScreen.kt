package com.ptylr.librearm.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ptylr.librearm.R
import com.ptylr.librearm.health.HealthConnectManager
import com.ptylr.librearm.model.HistoricalReading
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.launch

private val SystolicColor = Color(0xFFE53935)
private val DiastolicColor = Color(0xFF1E88E5)

// Fixed anchor so saved pager indices remain stable across launches and month rollovers.
private val ANCHOR_MONTH: YearMonth = YearMonth.of(2000, 1)

private data class DailyAverage(val sys: Double, val dia: Double)

private enum class HistoryTab { Calendar, Trends }

@Composable
fun HistoryScreen(
    healthManager: HealthConnectManager,
    hasReadPermission: Boolean,
    healthAvailable: HealthConnectManager.Availability,
    permissionPreviouslyDenied: Boolean,
    onRequestReadPermission: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onInstallHealthConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!hasReadPermission) {
        ReadPermissionRequired(
            modifier = modifier,
            healthAvailable = healthAvailable,
            previouslyDenied = permissionPreviouslyDenied,
            onGrantClick = onRequestReadPermission,
            onOpenHealthConnect = onOpenHealthConnect,
            onInstallHealthConnect = onInstallHealthConnect
        )
        return
    }

    val zone = remember { ZoneId.systemDefault() }
    val monthYearPattern = stringResource(R.string.history_month_year_format)
    val monthYearFormatter = remember(monthYearPattern) { DateTimeFormatter.ofPattern(monthYearPattern) }

    val today = remember { YearMonth.now() }
    val pageCount = remember(today) {
        (ChronoUnit.MONTHS.between(ANCHOR_MONTH, today) + 1).toInt().coerceAtLeast(1)
    }
    val currentMonthPage = pageCount - 1

    val pagerState = rememberPagerState(initialPage = currentMonthPage, pageCount = { pageCount })
    val scope = rememberCoroutineScope()

    val displayedMonth = remember(pagerState.currentPage) {
        ANCHOR_MONTH.plusMonths(pagerState.currentPage.toLong())
    }

    var selectedTab by rememberSaveable { mutableStateOf(HistoryTab.Calendar) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                    }
                },
                enabled = pagerState.currentPage > 0
            ) {
                Icon(
                    Icons.Default.ChevronLeft,
                    contentDescription = stringResource(R.string.history_prev_month)
                )
            }
            Text(
                text = displayedMonth.format(monthYearFormatter),
                style = MaterialTheme.typography.titleMedium
            )
            IconButton(
                onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(
                            (pagerState.currentPage + 1).coerceAtMost(currentMonthPage)
                        )
                    }
                },
                enabled = pagerState.currentPage < currentMonthPage
            ) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = stringResource(R.string.history_next_month)
                )
            }
        }

        PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
            Tab(
                selected = selectedTab == HistoryTab.Calendar,
                onClick = { selectedTab = HistoryTab.Calendar },
                text = { Text(stringResource(R.string.history_tab_calendar)) }
            )
            Tab(
                selected = selectedTab == HistoryTab.Trends,
                onClick = { selectedTab = HistoryTab.Trends },
                text = { Text(stringResource(R.string.history_tab_trends)) }
            )
        }

        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier
                .weight(1f)
                .padding(top = 16.dp)
        ) { pageIndex ->
            val pageMonth = remember(pageIndex) { ANCHOR_MONTH.plusMonths(pageIndex.toLong()) }
            var readings by remember(pageMonth) { mutableStateOf<List<HistoricalReading>>(emptyList()) }

            LaunchedEffect(pageMonth) {
                val start = pageMonth.atDay(1).atStartOfDay(zone).toInstant()
                val end = pageMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
                readings = healthManager.readRange(start, end)
            }

            when (selectedTab) {
                HistoryTab.Calendar -> CalendarView(pageMonth, readings, zone)
                HistoryTab.Trends -> TrendsView(pageMonth, readings, zone)
            }
        }
    }
}

@Composable
private fun CalendarView(
    month: YearMonth,
    readings: List<HistoricalReading>,
    zone: ZoneId
) {
    val daysWithReadings: Set<Int> = remember(readings, month, zone) {
        readings
            .map { it.time.atZone(zone).toLocalDate() }
            .filter { YearMonth.from(it) == month }
            .map { it.dayOfMonth }
            .toSet()
    }

    val locale = Locale.getDefault()
    val firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek
    val firstDay = month.atDay(1)
    val startOffset = ((firstDay.dayOfWeek.value - firstDayOfWeek.value) + 7) % 7
    val daysInMonth = month.lengthOfMonth()
    // Always render 6 rows of 7 so swiping between months doesn't change the grid height.
    val totalCells = 42
    val today = LocalDate.now()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            (0 until 7).forEach { i ->
                val day = firstDayOfWeek.plus(i.toLong())
                Text(
                    text = day.getDisplayName(TextStyle.SHORT, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        val cells = (0 until totalCells).map { idx ->
            val dayNum = idx - startOffset + 1
            if (dayNum in 1..daysInMonth) dayNum else null
        }
        cells.chunked(7).forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            ) {
                row.forEach { dayNum ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (dayNum != null) {
                            val date = month.atDay(dayNum)
                            val isToday = date == today
                            val hasReadings = dayNum in daysWithReadings

                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .background(
                                        color = if (isToday) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        } else {
                                            Color.Transparent
                                        },
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = dayNum.toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isToday || hasReadings) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (hasReadings) DiastolicColor else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }

        if (daysWithReadings.isEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.history_no_readings_month),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun TrendsView(
    month: YearMonth,
    readings: List<HistoricalReading>,
    zone: ZoneId
) {
    val dailyAverages: Map<Int, DailyAverage> = remember(readings, month, zone) {
        readings
            .filter { YearMonth.from(it.time.atZone(zone).toLocalDate()) == month }
            .groupBy { it.time.atZone(zone).dayOfMonth }
            .mapValues { (_, list) ->
                DailyAverage(
                    sys = list.map { it.sys }.average(),
                    dia = list.map { it.dia }.average()
                )
            }
            .toSortedMap()
    }

    if (dailyAverages.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.history_no_chart),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TrendChart(
            month = month,
            dailyAverages = dailyAverages,
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            LegendSwatch(color = SystolicColor, label = stringResource(R.string.history_legend_systolic))
            Spacer(modifier = Modifier.width(24.dp))
            LegendSwatch(color = DiastolicColor, label = stringResource(R.string.history_legend_diastolic))
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                val sysAvg = dailyAverages.values.map { it.sys }.average()
                val diaAvg = dailyAverages.values.map { it.dia }.average()
                val sysMax = dailyAverages.values.maxOf { it.sys }
                val sysMin = dailyAverages.values.minOf { it.sys }
                val diaMax = dailyAverages.values.maxOf { it.dia }
                val diaMin = dailyAverages.values.minOf { it.dia }

                Text(stringResource(R.string.history_summary_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.history_summary_sys, sysAvg.toInt(), sysMin.toInt(), sysMax.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    stringResource(R.string.history_summary_dia, diaAvg.toInt(), diaMin.toInt(), diaMax.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    stringResource(R.string.history_summary_days, dailyAverages.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun LegendSwatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 18.dp, height = 4.dp)
                .background(color)
        )
        Text(
            text = " $label",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

@Composable
private fun TrendChart(
    month: YearMonth,
    dailyAverages: Map<Int, DailyAverage>,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val monthTitlePattern = stringResource(R.string.history_short_format)
    val monthTitleFormatter = remember(monthTitlePattern) { DateTimeFormatter.ofPattern(monthTitlePattern) }
    val leftGutter = with(density) { 36.dp.toPx() }
    val bottomGutter = with(density) { 28.dp.toPx() }
    val topPad = with(density) { 8.dp.toPx() }
    val rightPad = with(density) { 8.dp.toPx() }
    val axisTextSize = with(density) { 11.sp.toPx() }
    val dotRadius = with(density) { 3.dp.toPx() }
    val lineWidth = with(density) { 2.dp.toPx() }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val axisPaint = remember(axisTextSize, labelColor) {
        Paint().apply {
            color = labelColor.toArgb()
            isAntiAlias = true
            textSize = axisTextSize
        }
    }
    val xPaddingPx = with(density) { 4.dp.toPx() }
    val tickGapPx = with(density) { 14.dp.toPx() }
    val xTitleBottomPx = with(density) { 2.dp.toPx() }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val plotW = w - leftGutter - rightPad
        val plotH = h - bottomGutter - topPad
        if (plotW <= 0f || plotH <= 0f) return@Canvas

        val daysInMonth = month.lengthOfMonth()
        val yMin = 40.0
        val yMax = 200.0
        val xSpan = (daysInMonth - 1).coerceAtLeast(1).toFloat()

        fun mapX(day: Int): Float = leftGutter + (day - 1) / xSpan * plotW
        fun mapY(v: Double): Float = topPad + (((yMax - v) / (yMax - yMin)).toFloat()) * plotH

        val canvas = drawContext.canvas.nativeCanvas

        listOf(60, 80, 100, 120, 140, 160, 180).forEach { v ->
            val y = mapY(v.toDouble())
            drawLine(
                color = gridColor,
                start = Offset(leftGutter, y),
                end = Offset(w - rightPad, y),
                strokeWidth = 1f
            )
            canvas.drawText(
                v.toString(),
                leftGutter - axisPaint.measureText(v.toString()) - xPaddingPx,
                y + axisPaint.textSize / 3f,
                axisPaint
            )
        }

        val tickEvery = when {
            daysInMonth <= 7 -> 1
            daysInMonth <= 15 -> 2
            else -> 5
        }
        var d = 1
        while (d <= daysInMonth) {
            val x = mapX(d)
            canvas.drawText(
                d.toString(),
                x - axisPaint.measureText(d.toString()) / 2f,
                h - bottomGutter + tickGapPx,
                axisPaint
            )
            d += tickEvery
        }
        val monthTitle = month.format(monthTitleFormatter)
        canvas.drawText(
            monthTitle,
            leftGutter + plotW / 2f - axisPaint.measureText(monthTitle) / 2f,
            h - xTitleBottomPx,
            axisPaint
        )

        fun drawSeries(seriesColor: Color, points: List<Pair<Int, Double>>) {
            if (points.isEmpty()) return
            val path = Path()
            points.forEachIndexed { idx, (day, v) ->
                val x = mapX(day)
                val y = mapY(v)
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = seriesColor, style = Stroke(width = lineWidth))
            points.forEach { (day, v) ->
                drawCircle(seriesColor, radius = dotRadius, center = Offset(mapX(day), mapY(v)))
            }
        }

        drawSeries(SystolicColor, dailyAverages.map { it.key to it.value.sys })
        drawSeries(DiastolicColor, dailyAverages.map { it.key to it.value.dia })
    }
}

@Composable
private fun ReadPermissionRequired(
    healthAvailable: HealthConnectManager.Availability,
    previouslyDenied: Boolean,
    onGrantClick: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onInstallHealthConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val installNeeded = healthAvailable == HealthConnectManager.Availability.NotInstalled ||
        healthAvailable == HealthConnectManager.Availability.NeedsUpdate

    val titleRes = when {
        installNeeded -> R.string.history_read_permission_install_title
        else -> R.string.history_read_permission_title
    }
    val bodyRes = when {
        installNeeded -> R.string.history_read_permission_install_body
        previouslyDenied -> R.string.history_read_permission_denied_body
        else -> R.string.history_read_permission_body
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.HealthAndSafety,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        when {
            installNeeded -> Button(onClick = onInstallHealthConnect) {
                Text(stringResource(R.string.history_read_permission_install))
            }
            previouslyDenied -> Button(onClick = onOpenHealthConnect) {
                Text(stringResource(R.string.history_read_permission_open_hc))
            }
            else -> Button(onClick = onGrantClick) {
                Text(stringResource(R.string.history_read_permission_grant))
            }
        }
    }
}

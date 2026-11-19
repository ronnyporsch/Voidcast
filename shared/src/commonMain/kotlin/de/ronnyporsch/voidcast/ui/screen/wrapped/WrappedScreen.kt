package de.ronnyporsch.voidcast.ui.screen.wrapped

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ronnyporsch.voidcast.data.jellyfin.WrappedArtistDto
import de.ronnyporsch.voidcast.data.jellyfin.WrappedDto
import de.ronnyporsch.voidcast.data.jellyfin.WrappedSongDto
import de.ronnyporsch.voidcast.ui.LocalCoverArtUrlProvider
import de.ronnyporsch.voidcast.ui.components.CoverArtImage
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import de.ronnyporsch.voidcast.ui.theme.SpotifyLightGray
import de.ronnyporsch.voidcast.ui.theme.SpotifySurface
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.math.roundToLong

/** The user's listening stats of a period as Spotify Wrapped style story slides. */
@Composable
fun WrappedScreen(viewModel: WrappedViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var pickingDays by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("←", fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                text = "Wrapped",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        PeriodSelector(
            state = state,
            onPreset = { preset ->
                if (preset == WrappedPreset.Custom) pickingDays = true
                else viewModel.onEvent(WrappedEvent.PresetSelected(preset))
            },
        )

        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val wrapped = state.wrapped
            when {
                state.isLoading -> CircularProgressIndicator(color = AccentColor)
                state.error != null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(state.error!!, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    OutlinedButton(
                        onClick = { viewModel.onEvent(WrappedEvent.Retry) },
                        shape = RoundedCornerShape(0.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentColor),
                    ) {
                        Text("Try again", fontWeight = FontWeight.Bold)
                    }
                }
                wrapped != null && wrapped.playCount == 0 -> Text(
                    text = "No plays in this period. Songs count once you've listened to them for 30 seconds.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SpotifyLightGray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
                wrapped != null -> key(wrapped) { WrappedStories(wrapped, state.period.label(state.today)) }
            }
        }
    }

    if (pickingDays) {
        CustomPeriodDialog(
            period = state.period,
            today = state.today,
            onDismiss = { pickingDays = false },
            onConfirm = { from, to ->
                pickingDays = false
                viewModel.onEvent(WrappedEvent.CustomSelected(from, to))
            },
        )
    }
}

@Composable
private fun PeriodSelector(state: WrappedState, onPreset: (WrappedPreset) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (preset in WrappedPreset.entries) {
            val selected = state.period.preset == preset
            val label = if (selected) state.period.label(state.today) else WrappedPeriod(preset).label(state.today)
            OutlinedButton(
                onClick = { onPreset(preset) },
                shape = RoundedCornerShape(0.dp),
                border = BorderStroke(1.dp, if (selected) AccentColor else SpotifySurface),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = if (selected) AccentColor else SpotifyLightGray),
            ) {
                Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomPeriodDialog(
    period: WrappedPeriod,
    today: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
) {
    // The picker's days are UTC midnights
    fun LocalDate.millis() = toEpochDays() * DAY_MILLIS
    fun Long.day() = LocalDate.fromEpochDays(this / DAY_MILLIS)
    val todayMillis = today.millis()
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = period.customFrom?.millis(),
        initialSelectedEndDateMillis = period.customTo?.millis(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMillis
            override fun isSelectableYear(year: Int) = year <= today.year
        },
    )
    val start = pickerState.selectedStartDateMillis
    val end = pickerState.selectedEndDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(0.dp),
        confirmButton = {
            TextButton(
                onClick = { if (start != null) onConfirm(start.day(), (end ?: start).day()) },
                enabled = start != null,
            ) { Text("Show", color = if (start != null) AccentColor else SpotifyLightGray) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = SpotifyLightGray) } },
    ) {
        DateRangePicker(
            state = pickerState,
            modifier = Modifier.weight(1f),
            title = { Text("Choose the days", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
            showModeToggle = true,
            colors = DatePickerDefaults.colors(
                selectedDayContainerColor = AccentColor,
                todayDateBorderColor = AccentColor,
                dayInSelectionRangeContainerColor = AccentColor.copy(alpha = 0.25f),
            ),
        )
    }
}

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

/** A story slide, shown if its stats exist. */
private enum class Slide(val top: Color, val bottom: Color) {
    Intro(Color(0xFFB5179E), Color(0xFF3A0CA3)),
    Minutes(Color(0xFF0F9B8E), Color(0xFF1A2A6C)),
    TopArtist(Color(0xFFFF6B35), Color(0xFF8E0E00)),
    TopSongs(Color(0xFF4361EE), Color(0xFF240046)),
    TopAlbums(Color(0xFFF72585), Color(0xFF560BAD)),
    TopGenres(Color(0xFF06A77D), Color(0xFF073B4C)),
    Patterns(Color(0xFF3A86FF), Color(0xFF0B132B)),
    Days(Color(0xFF7B2CBF), Color(0xFF10002B)),
    Discoveries(Color(0xFFE85D04), Color(0xFF9D0208)),
    OnRepeat(Color(0xFFFF006E), Color(0xFF3A0CA3)),
    MostSkipped(Color(0xFF6C757D), Color(0xFF212529)),
    Summary(Color(0xFF2B2B2B), Color(0xFF000000)),
}

private fun slidesOf(wrapped: WrappedDto): List<Slide> = Slide.entries.filter { slide ->
    when (slide) {
        Slide.TopArtist -> wrapped.topArtists.isNotEmpty()
        Slide.TopSongs -> wrapped.topSongs.isNotEmpty()
        Slide.TopAlbums -> wrapped.topAlbums.isNotEmpty()
        Slide.TopGenres -> wrapped.topGenres.isNotEmpty()
        Slide.Days -> wrapped.busiestDay != null
        Slide.Discoveries -> wrapped.newArtistCount > 0 || wrapped.newSongCount > 0
        Slide.OnRepeat -> wrapped.onRepeat != null
        Slide.MostSkipped -> wrapped.mostSkipped != null
        else -> true
    }
}

@Composable
private fun WrappedStories(wrapped: WrappedDto, periodLabel: String) {
    val slides = remember(wrapped) { slidesOf(wrapped) }
    val pagerState = rememberPagerState { slides.size }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .widthIn(max = 480.dp)
            .fillMaxHeight()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
            .clip(RoundedCornerShape(12.dp)),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                // Like stories: a tap on the left goes back, elsewhere forward
                .pointerInput(slides.size) {
                    detectTapGestures { offset ->
                        val target = if (offset.x < size.width / 3f) pagerState.currentPage - 1 else pagerState.currentPage + 1
                        if (target in slides.indices) scope.launch { pagerState.animateScrollToPage(target) }
                    }
                },
        ) { page ->
            val slide = slides[page]
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(slide.top, slide.bottom)))) {
                SlideContent(slide, wrapped, periodLabel, isCurrent = pagerState.settledPage == page)
            }
        }

        // Which slide of how many
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (index in slides.indices) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = if (index <= pagerState.currentPage) 0.9f else 0.3f))
                        .clickable { scope.launch { pagerState.animateScrollToPage(index) } },
                )
            }
        }
    }
}

@Composable
private fun SlideContent(slide: Slide, wrapped: WrappedDto, periodLabel: String, isCurrent: Boolean) {
    // Each slide animates in the first time it is shown, and stays then
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(isCurrent) { if (isCurrent) shown = true }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(500)) + slideInVertically(tween(600, easing = FastOutSlowInEasing)) { it / 8 },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            when (slide) {
                Slide.Intro -> IntroSlide(periodLabel)
                Slide.Minutes -> MinutesSlide(wrapped, shown)
                Slide.TopArtist -> TopArtistSlide(wrapped.topArtists)
                Slide.TopSongs -> TopSongsSlide(wrapped.topSongs)
                Slide.TopAlbums -> TopAlbumsSlide(wrapped)
                Slide.TopGenres -> TopGenresSlide(wrapped, shown)
                Slide.Patterns -> PatternsSlide(wrapped, shown)
                Slide.Days -> DaysSlide(wrapped)
                Slide.Discoveries -> DiscoveriesSlide(wrapped)
                Slide.OnRepeat -> OnRepeatSlide(wrapped)
                Slide.MostSkipped -> MostSkippedSlide(wrapped)
                Slide.Summary -> SummarySlide(wrapped, periodLabel)
            }
        }
    }
}

@Composable
private fun ColumnScope.IntroSlide(periodLabel: String) {
    Caption("Your")
    BigText(periodLabel)
    Text("Wrapped", fontSize = 56.sp, fontWeight = FontWeight.Black, color = Color.White, lineHeight = 60.sp)
    Spacer(Modifier.height(24.dp))
    Caption("Let's look at how you listened. Tap on the right to continue, on the left to go back.")
}

@Composable
private fun ColumnScope.MinutesSlide(wrapped: WrappedDto, shown: Boolean) {
    val minutes = wrapped.totalSeconds / 60
    Caption("You listened for")
    BigText(formatCount(countUp(minutes, shown)), fontSize = 64.sp)
    Caption("minutes")
    Spacer(Modifier.height(16.dp))
    Body(
        "That's ${plural(wrapped.playCount, "play")} of ${plural(wrapped.songCount, "song")} by " +
            "${plural(wrapped.artistCount, "artist")}" +
            if (wrapped.albumCount > 0) " from ${plural(wrapped.albumCount, "album")}." else "."
    )
    when {
        minutes >= 24 * 60 -> Body("Or about ${plural((minutes / (24 * 60)).toInt(), "whole day")} of music.")
        minutes >= 120 -> Body("Or about ${plural((minutes / 60).toInt(), "hour")} of music.")
    }
}

@Composable
private fun ColumnScope.TopArtistSlide(artists: List<WrappedArtistDto>) {
    val top = artists.first()
    Caption("Your top artist")
    ArtistImage(top, 180.dp, Modifier.align(Alignment.CenterHorizontally))
    BigText(top.name, fontSize = 36.sp)
    Body("${plural(top.plays, "play")} · ${formatCount(top.seconds / 60)} minutes")
    if (artists.size > 1) {
        Spacer(Modifier.height(8.dp))
        artists.drop(1).forEachIndexed { index, artist ->
            RankedRow(index + 2, imageUrl = artistUrl(artist, 96), circle = true, title = artist.name, subtitle = plural(artist.plays, "play"))
        }
    }
}

@Composable
private fun ColumnScope.TopSongsSlide(songs: List<WrappedSongDto>) {
    Caption("Your top songs")
    Spacer(Modifier.height(4.dp))
    songs.forEachIndexed { index, song ->
        RankedRow(index + 1, songUrl(song, 128), circle = false, title = song.name, subtitle = "${song.artist} · ${plural(song.plays, "play")}", large = index == 0)
    }
}

@Composable
private fun ColumnScope.TopAlbumsSlide(wrapped: WrappedDto) {
    val coverArt = LocalCoverArtUrlProvider.current
    Caption("Your top albums")
    Spacer(Modifier.height(4.dp))
    wrapped.topAlbums.forEachIndexed { index, album ->
        RankedRow(index + 1, coverArt.getUrl(album.id, 128), circle = false, title = album.name, subtitle = "${album.artist} · ${plural(album.plays, "play")}", large = index == 0)
    }
}

@Composable
private fun ColumnScope.TopGenresSlide(wrapped: WrappedDto, shown: Boolean) {
    val genres = wrapped.topGenres
    Caption("Your top genres")
    BigText(genres.first().name, fontSize = 40.sp)
    Spacer(Modifier.height(8.dp))
    val most = genres.maxOf { it.plays }.coerceAtLeast(1)
    genres.forEachIndexed { index, genre ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("${index + 1}  ${genre.name}", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(plural(genre.plays, "play"), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
            }
            Bar(genre.plays.toFloat() / most, shown, Modifier.fillMaxWidth().height(10.dp), horizontal = true)
        }
    }
}

@Composable
private fun ColumnScope.PatternsSlide(wrapped: WrappedDto, shown: Boolean) {
    val hours = wrapped.playsByHour.takeIf { it.size == 24 } ?: List(24) { 0 }
    val weekdays = wrapped.playsByWeekday.takeIf { it.size == 7 } ?: List(7) { 0 }
    val peakHour = hours.indices.maxBy { hours[it] }
    Caption("You're")
    BigText(
        when (peakHour) {
            in 5..11 -> "An early bird"
            in 12..17 -> "An afternoon listener"
            in 18..22 -> "An evening listener"
            else -> "A night owl"
        },
        fontSize = 36.sp,
    )
    Body("You listen the most around ${hourName(peakHour)}.")
    BarChart(hours, shown, labels = listOf(0, 6, 12, 18).map(::hourName))
    Spacer(Modifier.height(8.dp))
    val peakDay = weekdays.indices.maxBy { weekdays[it] }
    Body("Your favorite day for music: ${WEEKDAYS[peakDay]}.")
    BarChart(weekdays, shown, labels = WEEKDAYS.map { it.take(3) })
}

@Composable
private fun ColumnScope.DaysSlide(wrapped: WrappedDto) {
    val day = wrapped.busiestDay ?: return
    Caption("Your biggest day")
    BigText(formatDay(day.date), fontSize = 36.sp)
    Body("${formatCount(day.seconds / 60)} minutes and ${plural(day.plays, "play")} in one day.")
    wrapped.longestStreak?.takeIf { it.days > 1 }?.let { streak ->
        Spacer(Modifier.height(24.dp))
        Caption("Your longest streak")
        BigText(plural(streak.days, "day"), fontSize = 44.sp)
        Body("In a row with music, from ${formatDay(streak.start)} to ${formatDay(streak.end)}.")
    }
}

@Composable
private fun ColumnScope.DiscoveriesSlide(wrapped: WrappedDto) {
    Caption("You discovered")
    BigText(plural(wrapped.newArtistCount, "new artist"), fontSize = 40.sp)
    Body("and ${plural(wrapped.newSongCount, "song")} you had never played before.")
    wrapped.topNewArtist?.let { artist ->
        Spacer(Modifier.height(16.dp))
        Caption("Your top discovery")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ArtistImage(artist, 96.dp)
            Column {
                Text(artist.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(plural(artist.plays, "play"), color = Color.White.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun ColumnScope.OnRepeatSlide(wrapped: WrappedDto) {
    val repeat = wrapped.onRepeat ?: return
    Caption("On repeat")
    CoverArtImage(songUrl(repeat.song, 600), Modifier.size(200.dp).align(Alignment.CenterHorizontally), cornerRadius = 4.dp, placeholderFontSize = 64.sp)
    BigText(repeat.song.name, fontSize = 32.sp)
    Body(repeat.song.artist)
    Body("You played it ${repeat.plays} times on ${formatDay(repeat.date)}.")
}

@Composable
private fun ColumnScope.MostSkippedSlide(wrapped: WrappedDto) {
    val skipped = wrapped.mostSkipped ?: return
    Caption("Not feeling it")
    CoverArtImage(songUrl(skipped.song, 300), Modifier.size(120.dp), cornerRadius = 4.dp, placeholderFontSize = 40.sp)
    BigText(skipped.song.name, fontSize = 32.sp)
    Body(skipped.song.artist)
    Body("You stopped it before halfway ${skipped.skips} times.")
}

@Composable
private fun ColumnScope.SummarySlide(wrapped: WrappedDto, periodLabel: String) {
    Text("$periodLabel Wrapped", color = AccentColor, fontSize = 22.sp, fontWeight = FontWeight.Black)
    wrapped.topArtists.firstOrNull()?.let { ArtistImage(it, 140.dp, Modifier.align(Alignment.CenterHorizontally)) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SummaryList("Top artists", wrapped.topArtists.map { it.name }, Modifier.weight(1f))
        SummaryList("Top songs", wrapped.topSongs.map { it.name }, Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SummaryValue("Minutes listened", formatCount(wrapped.totalSeconds / 60), Modifier.weight(1f))
        SummaryValue("Top genre", wrapped.topGenres.firstOrNull()?.name ?: "–", Modifier.weight(1f))
    }
}

@Composable
private fun SummaryList(title: String, names: List<String>, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = SpotifyLightGray, style = MaterialTheme.typography.bodySmall)
        names.forEachIndexed { index, name ->
            Text("${index + 1} $name", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SummaryValue(title: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(title, color = SpotifyLightGray, style = MaterialTheme.typography.bodySmall)
        Text(value, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun BigText(text: String, fontSize: androidx.compose.ui.unit.TextUnit = 48.sp) {
    Text(text, color = Color.White, fontSize = fontSize, lineHeight = fontSize * 1.1f, fontWeight = FontWeight.Black)
}

@Composable
private fun Body(text: String) {
    Text(text, color = Color.White, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun RankedRow(rank: Int, imageUrl: String?, circle: Boolean, title: String, subtitle: String, large: Boolean = false) {
    val imageSize = if (large) 64.dp else 48.dp
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(rank.toString(), color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp, modifier = Modifier.width(24.dp))
        CoverArtImage(imageUrl, Modifier.size(imageSize).then(if (circle) Modifier.clip(CircleShape) else Modifier), cornerRadius = if (circle) imageSize / 2 else 2.dp)
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (large) 18.sp else 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ArtistImage(artist: WrappedArtistDto, size: Dp, modifier: Modifier = Modifier) {
    CoverArtImage(
        url = artistUrl(artist, (size.value * 2).toInt()),
        modifier = modifier.size(size).clip(CircleShape),
        cornerRadius = size / 2,
        placeholderText = artist.name.take(1).uppercase(),
        placeholderFontSize = (size.value / 2.5f).sp,
    )
}

@Composable
private fun artistUrl(artist: WrappedArtistDto, size: Int): String? = LocalCoverArtUrlProvider.current.getUrl(artist.id, size)

/** Songs show their album's cover; songs no longer in the library have none. */
@Composable
private fun songUrl(song: WrappedSongDto, size: Int): String? = LocalCoverArtUrlProvider.current.getUrl(song.albumId ?: song.id, size)

/** Bars of [values]; [labels] are spread evenly below them, each under as many bars. */
@Composable
private fun BarChart(values: List<Int>, shown: Boolean, labels: List<String>) {
    val most = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            for (value in values) {
                Bar(value.toFloat() / most, shown, Modifier.weight(1f).fillMaxHeight(), horizontal = false)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (label in labels) {
                Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** A bar filled to [fraction], growing once [shown]. */
@Composable
private fun Bar(fraction: Float, shown: Boolean, modifier: Modifier, horizontal: Boolean) {
    val animated by animateFloatAsState(if (shown) fraction else 0f, tween(900, delayMillis = 200, easing = FastOutSlowInEasing))
    Box(modifier.clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.15f)), contentAlignment = if (horizontal) Alignment.CenterStart else Alignment.BottomCenter) {
        Box(
            (if (horizontal) Modifier.fillMaxHeight().fillMaxWidth(animated) else Modifier.fillMaxWidth().fillMaxHeight(animated))
                .background(Color.White),
        )
    }
}

/** [target] counted up from 0 once [shown]. */
@Composable
private fun countUp(target: Long, shown: Boolean): Long {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        if (shown) progress.animateTo(1f, tween(1500, easing = FastOutSlowInEasing))
    }
    return (target * progress.value.toDouble()).roundToLong()
}

private val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

private fun hourName(hour: Int): String = when (hour) {
    0 -> "12 AM"
    12 -> "12 PM"
    in 1..11 -> "$hour AM"
    else -> "${hour - 12} PM"
}

package app.vitune.android.ui.screens.player

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaMetadata
import app.vitune.android.Database
import app.vitune.android.LocalPlayerServiceBinder
import app.vitune.android.R
import app.vitune.android.models.Lyrics
import app.vitune.android.preferences.PlayerPreferences
import app.vitune.android.query
import app.vitune.android.service.LOCAL_KEY_PREFIX
import app.vitune.android.transaction
import app.vitune.android.ui.components.LocalMenuState
import app.vitune.android.ui.components.themed.CircularProgressIndicator
import app.vitune.android.ui.components.themed.DefaultDialog
import app.vitune.android.ui.components.themed.Menu
import app.vitune.android.ui.components.themed.MenuEntry
import app.vitune.android.ui.components.themed.TextField
import app.vitune.android.ui.components.themed.TextFieldDialog
import app.vitune.android.ui.components.themed.TextPlaceholder
import app.vitune.android.ui.components.themed.ValueSelectorDialogBody
import app.vitune.android.ui.modifiers.verticalFadingEdge
import app.vitune.android.utils.LyricsFetcher
import app.vitune.android.utils.SynchronizedLyrics
import app.vitune.android.utils.SynchronizedLyricsState
import app.vitune.android.utils.WordSynchronizedLyrics
import app.vitune.android.utils.bold
import app.vitune.android.utils.center
import app.vitune.android.utils.color
import app.vitune.android.utils.isInPip
import app.vitune.android.utils.medium
import app.vitune.android.utils.semiBold
import app.vitune.android.utils.toast
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.onOverlay
import app.vitune.core.ui.onOverlayShimmer
import app.vitune.core.ui.overlay
import app.vitune.core.ui.utils.dp
import app.vitune.providers.common.models.WordSyncedLyrics
import app.vitune.providers.lrclib.LrcLib
import app.vitune.providers.lrclib.LrcParser
import app.vitune.providers.lrclib.models.Track
import app.vitune.providers.lrclib.toLrcFile
import com.valentinilk.shimmer.shimmer
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val UPDATE_DELAY = 50L

@Composable
fun Lyrics(
    mediaId: String,
    isDisplayed: Boolean,
    onDismiss: () -> Unit,
    mediaMetadataProvider: () -> MediaMetadata,
    durationProvider: () -> Long,
    ensureSongInserted: () -> Unit,
    modifier: Modifier = Modifier,
    onMenuLaunch: () -> Unit = { },
    onOpenDialog: (() -> Unit)? = null,
    shouldShowSynchronizedLyrics: Boolean = PlayerPreferences.isShowingSynchronizedLyrics,
    setShouldShowSynchronizedLyrics: (Boolean) -> Unit = {
        PlayerPreferences.isShowingSynchronizedLyrics = it
    },
    shouldKeepScreenAwake: Boolean = PlayerPreferences.lyricsKeepScreenAwake,
    shouldUpdateLyrics: Boolean = true,
    showControls: Boolean = true
) = AnimatedVisibility(
    visible = isDisplayed,
    enter = fadeIn(),
    exit = fadeOut()
) {
    val currentEnsureSongInserted by rememberUpdatedState(ensureSongInserted)
    val currentMediaMetadataProvider by rememberUpdatedState(mediaMetadataProvider)
    val currentDurationProvider by rememberUpdatedState(durationProvider)

    val (colorPalette, typography) = LocalAppearance.current
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val binder = LocalPlayerServiceBinder.current
    val density = LocalDensity.current
    val view = LocalView.current

    val pip = isInPip()

    var lyrics by remember { mutableStateOf<Lyrics?>(null) }

    val showSynchronizedLyrics = remember(shouldShowSynchronizedLyrics, lyrics) {
        shouldShowSynchronizedLyrics &&
            (lyrics?.wordSynced != null || lyrics?.synced?.isBlank() != true)
    }

    var editing by remember(mediaId, shouldShowSynchronizedLyrics) { mutableStateOf(false) }
    var picking by remember(mediaId, shouldShowSynchronizedLyrics) { mutableStateOf(false) }
    var error by remember(mediaId, shouldShowSynchronizedLyrics) { mutableStateOf(false) }

    val text = remember(lyrics, showSynchronizedLyrics) {
        if (showSynchronizedLyrics) lyrics?.wordSynced?.text ?: lyrics?.synced
        else lyrics?.fixed ?: lyrics?.wordSynced?.text
    }
    var invalidLrc by remember(text) { mutableStateOf(false) }

    DisposableEffect(shouldKeepScreenAwake) {
        view.keepScreenOn = shouldKeepScreenAwake

        onDispose {
            view.keepScreenOn = false
        }
    }

    LaunchedEffect(mediaId, shouldUpdateLyrics) {
        runCatching {
            withContext(Dispatchers.IO) {
                Database
                    .lyrics(mediaId)
                    .distinctUntilChanged()
                    .cancellable()
                    .collect { currentLyrics ->
                        if (!shouldUpdateLyrics || currentLyrics?.wordSyncedChecked != null) {
                            lyrics = currentLyrics
                        } else {
                            val resolvedLyrics = LyricsFetcher.ensure(
                                mediaId = mediaId,
                                mediaMetadata = currentMediaMetadataProvider(),
                                ensureSongInserted = currentEnsureSongInserted,
                                playerDuration = {
                                    withContext(Dispatchers.Main) { currentDurationProvider() }
                                }
                            )

                            ensureActive()

                            if (resolvedLyrics != null) lyrics = resolvedLyrics
                        }

                        error =
                            (showSynchronizedLyrics && lyrics?.wordSynced == null && lyrics?.synced?.isBlank() == true) ||
                                (!showSynchronizedLyrics && lyrics?.fixed?.isBlank() == true)
                    }
            }
        }.exceptionOrNull()?.let {
            if (it is CancellationException) throw it
            else it.printStackTrace()
        }
    }

    if (editing) TextFieldDialog(
        hintText = stringResource(R.string.enter_lyrics),
        initialTextInput = (
            if (shouldShowSynchronizedLyrics) lyrics?.fixed ?: lyrics?.wordSynced?.text ?: lyrics?.synced
            else lyrics?.fixed ?: lyrics?.wordSynced?.text
            ).orEmpty(),
        singleLine = false,
        maxLines = 10,
        isTextInputValid = { true },
        onDismiss = { editing = false },
        onAccept = {
            transaction {
                runCatching {
                    currentEnsureSongInserted()

                    Database.upsert(
                        when {
                            lyrics?.wordSynced != null -> lyrics!!.copy(fixed = it)
                            shouldShowSynchronizedLyrics -> Lyrics(
                                songId = mediaId,
                                fixed = lyrics?.fixed,
                                synced = it
                            )
                            else -> Lyrics(
                                songId = mediaId,
                                fixed = it,
                                synced = lyrics?.synced
                            )
                        }
                    )
                }
            }
        }
    )

    if (picking && shouldShowSynchronizedLyrics && lyrics?.wordSynced == null) {
        var query by rememberSaveable {
            mutableStateOf(
                currentMediaMetadataProvider().title?.toString().orEmpty().let {
                    if (mediaId.startsWith(LOCAL_KEY_PREFIX)) it
                        .substringBeforeLast('.')
                        .trim()
                    else it
                }
            )
        }

        LrcLibSearchDialog(
            query = query,
            setQuery = { query = it },
            onDismiss = { picking = false },
            onPick = {
                runCatching {
                    transaction {
                        Database.upsert(
                            Lyrics(
                                songId = mediaId,
                                fixed = lyrics?.fixed,
                                synced = it.syncedLyrics
                            )
                        )
                    }
                }
            }
        )
    }

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onDismiss() })
            }
            .fillMaxSize()
            .background(colorPalette.overlay)
    ) {
        val animatedHeight by animateDpAsState(
            targetValue = this.maxHeight,
            label = ""
        )

        AnimatedVisibility(
            visible = error,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            BasicText(
                text = stringResource(
                    if (shouldShowSynchronizedLyrics) R.string.synchronized_lyrics_not_available
                    else R.string.lyrics_not_available
                ),
                style = typography.xs.center.medium.color(colorPalette.onOverlay),
                modifier = Modifier
                    .background(Color.Black.copy(0.4f))
                    .padding(all = 8.dp)
                    .fillMaxWidth(),
                maxLines = if (pip) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis
            )
        }

        AnimatedVisibility(
            visible = showSynchronizedLyrics && !error && invalidLrc,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            BasicText(
                text = stringResource(R.string.invalid_synchronized_lyrics),
                style = typography.xs.center.medium.color(colorPalette.onOverlay),
                modifier = Modifier
                    .background(Color.Black.copy(0.4f))
                    .padding(all = 8.dp)
                    .fillMaxWidth(),
                maxLines = if (pip) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis
            )
        }

        val lyricsState = rememberSaveable(lyrics?.synced) {
            val file = lyrics?.synced?.takeIf { it.isNotBlank() }?.let {
                LrcParser.parse(it)?.toLrcFile()
            }

            SynchronizedLyricsState(
                sentences = file?.lines,
                offset = file?.offset?.inWholeMilliseconds ?: 0L
            )
        }

        val synchronizedLyrics = remember(lyricsState) {
            invalidLrc = showSynchronizedLyrics &&
                lyrics?.wordSynced == null &&
                lyrics?.synced?.isNotBlank() == true &&
                lyricsState.sentences == null

            lyricsState.sentences?.let {
                SynchronizedLyrics(it.toImmutableMap()) {
                    binder?.player?.let { player ->
                        player.currentPosition + UPDATE_DELAY + lyricsState.offset -
                            (lyrics?.startTime ?: 0L)
                    } ?: 0L
                }
            }
        }

        AnimatedContent(
            targetState = showSynchronizedLyrics,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = ""
        ) { synchronized ->
            val lazyListState = rememberLazyListState()
            val wordSyncedLyrics = remember(lyrics?.wordSynced) {
                lyrics?.wordSynced?.let { wordSynced ->
                    WordSynchronizedLyrics(
                        lines = wordSynced.lines.toImmutableList(),
                        positionProvider = {
                            binder?.player?.let { player ->
                                player.currentPosition + UPDATE_DELAY - (lyrics?.startTime ?: 0L)
                            } ?: 0L
                        },
                        isPlayingProvider = { binder?.player?.isPlaying == true }
                    )
                }
            }

            if (synchronized) {
                when {
                    wordSyncedLyrics != null -> {
                        LaunchedEffect(wordSyncedLyrics, density, animatedHeight) {
                            val currentWordSyncedLyrics = wordSyncedLyrics
                            val centerOffset = with(density) { (-animatedHeight / 3).roundToPx() }

                            lazyListState.scrollToItem(
                                index = currentWordSyncedLyrics.index + 1,
                                scrollOffset = centerOffset
                            )

                            while (true) {
                                if (!withFrameNanos(currentWordSyncedLyrics::onFrame)) continue

                                launch {
                                    lazyListState.animateScrollToItem(
                                        index = currentWordSyncedLyrics.index + 1,
                                        scrollOffset = centerOffset
                                    )
                                }
                            }
                        }

                        val hasDuet = remember(wordSyncedLyrics) {
                            wordSyncedLyrics.lines.any { it.oppositeAligned }
                        }

                        LazyColumn(
                            state = lazyListState,
                            userScrollEnabled = false,
                            horizontalAlignment = Alignment.Start,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .verticalFadingEdge()
                                .fillMaxWidth()
                        ) {
                            item(key = "header", contentType = 0) {
                                Spacer(modifier = Modifier.height(maxHeight))
                            }
                            itemsIndexed(
                                items = wordSyncedLyrics.lines
                            ) { index, sentence ->
                                if (sentence.text.isBlank()) Image(
                                    painter = painterResource(R.drawable.musical_notes),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(
                                        if (index == wordSyncedLyrics.index) Color.White
                                        else colorPalette.textDisabled
                                    ),
                                    modifier = Modifier
                                        .animateItem(
                                            placementSpec = tween(
                                                durationMillis = 250,
                                                easing = FastOutSlowInEasing
                                            )
                                        )
                                        .padding(vertical = 4.dp, horizontal = 32.dp)
                                        .size(typography.m.fontSize.dp)
                                ) else WordSyncedLine(
                                    line = sentence,
                                    positionProvider = { wordSyncedLyrics.position },
                                    activeColor = Color.White,
                                    inactiveColor = colorPalette.textDisabled,
                                    backgroundActiveColor = Color.White.copy(alpha = 0.7f),
                                    backgroundInactiveColor = colorPalette.textDisabled.copy(alpha = 0.75f),
                                    style = typography.m.bold,
                                    modifier = Modifier
                                        .animateItem(
                                            placementSpec = tween(
                                                durationMillis = 250,
                                                easing = FastOutSlowInEasing
                                            )
                                        )
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp, horizontal = 32.dp)
                                        .padding(
                                            start = if (hasDuet && sentence.oppositeAligned) 48.dp else 0.dp,
                                            end = if (hasDuet && !sentence.oppositeAligned) 48.dp else 0.dp
                                        )
                                )
                            }
                            item(key = "footer", contentType = 0) {
                                Spacer(modifier = Modifier.height(maxHeight))
                            }
                        }
                    }

                    synchronizedLyrics != null -> {
                        LaunchedEffect(synchronizedLyrics, density, animatedHeight) {
                            val currentSynchronizedLyrics = synchronizedLyrics
                            val centerOffset = with(density) { (-animatedHeight / 3).roundToPx() }

                            lazyListState.scrollToItem(
                                index = currentSynchronizedLyrics.index + 1,
                                scrollOffset = centerOffset
                            )

                            while (true) {
                                delay(UPDATE_DELAY)
                                if (!currentSynchronizedLyrics.update()) continue

                                launch {
                                    lazyListState.animateScrollToItem(
                                        index = currentSynchronizedLyrics.index + 1,
                                        scrollOffset = centerOffset
                                    )
                                }
                            }
                        }

                        LazyColumn(
                            state = lazyListState,
                            userScrollEnabled = false,
                            horizontalAlignment = Alignment.Start,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .verticalFadingEdge()
                                .fillMaxWidth()
                        ) {
                            item(key = "header", contentType = 0) {
                                Spacer(modifier = Modifier.height(maxHeight))
                            }
                            itemsIndexed(
                                items = synchronizedLyrics.sentences.values.toImmutableList()
                            ) { index, sentence ->
                                val color by animateColorAsState(
                                    if (index == synchronizedLyrics.index) Color.White
                                    else colorPalette.textDisabled
                                )

                                if (sentence.isBlank()) Image(
                                    painter = painterResource(R.drawable.musical_notes),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(color),
                                    modifier = Modifier
                                        .animateItem(
                                            placementSpec = tween(
                                                durationMillis = 250,
                                                easing = FastOutSlowInEasing
                                            )
                                        )
                                        .padding(vertical = 4.dp, horizontal = 32.dp)
                                        .size(typography.m.fontSize.dp)
                                ) else BasicText(
                                    text = sentence,
                                    style = typography.m.bold.color(color),
                                    modifier = Modifier
                                        .animateItem(
                                            placementSpec = tween(
                                                durationMillis = 250,
                                                easing = FastOutSlowInEasing
                                            )
                                        )
                                        .padding(vertical = 4.dp, horizontal = 32.dp)
                                )
                            }
                            item(key = "footer", contentType = 0) {
                                Spacer(modifier = Modifier.height(maxHeight))
                            }
                        }
                    }

                    else -> BasicText(
                        text = lyrics?.synced.orEmpty(),
                        style = typography.m.bold.color(colorPalette.onOverlay),
                        modifier = Modifier
                            .verticalFadingEdge()
                            .verticalScroll(rememberScrollState())
                            .fillMaxWidth()
                            .padding(vertical = maxHeight / 4, horizontal = 32.dp)
                    )
                }
            } else BasicText(
                text = lyrics?.fixed.orEmpty(),
                style = typography.m.bold.color(colorPalette.onOverlay),
                modifier = Modifier
                    .verticalFadingEdge()
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
                    .padding(vertical = maxHeight / 4, horizontal = 32.dp)
            )
        }

        if (text == null && !error) Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier
                .shimmer()
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        ) {
            listOf(0.9f, 0.7f, 0.85f, 0.55f, 0.8f, 0.65f, 0.75f).forEachIndexed { index, width ->
                TextPlaceholder(
                    color = colorPalette.onOverlayShimmer,
                    width = width,
                    height = 26.dp,
                    modifier = Modifier.alpha(1f - index * 0.1f)
                )
            }
        }

        if (showControls) {
            if (onOpenDialog != null) Image(
                painter = painterResource(R.drawable.expand),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colorPalette.onOverlay),
                modifier = Modifier
                    .padding(all = 4.dp)
                    .clickable(
                        indication = ripple(bounded = false),
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {
                            onOpenDialog()
                        }
                    )
                    .padding(all = 8.dp)
                    .size(20.dp)
                    .align(Alignment.BottomStart)
            )

            Image(
                painter = painterResource(R.drawable.ellipsis_horizontal),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colorPalette.onOverlay),
                modifier = Modifier
                    .padding(all = 4.dp)
                    .clickable(
                        indication = ripple(bounded = false),
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {
                            onMenuLaunch()
                            menuState.display {
                                Menu {
                                    MenuEntry(
                                        icon = R.drawable.time,
                                        text = stringResource(
                                            if (shouldShowSynchronizedLyrics) R.string.show_unsynchronized_lyrics
                                            else R.string.show_synchronized_lyrics
                                        ),
                                        secondaryText = if (shouldShowSynchronizedLyrics) null
                                        else stringResource(R.string.provided_lyrics_by),
                                        onClick = {
                                            menuState.hide()
                                            setShouldShowSynchronizedLyrics(!shouldShowSynchronizedLyrics)
                                        }
                                    )

                                    MenuEntry(
                                        icon = R.drawable.pencil,
                                        text = stringResource(R.string.edit_lyrics),
                                        onClick = {
                                            menuState.hide()
                                            editing = true
                                        }
                                    )

                                    val errorMsg = stringResource(R.string.no_browser_installed)
                                    MenuEntry(
                                        icon = R.drawable.search,
                                        text = stringResource(R.string.search_lyrics_online),
                                        onClick = {
                                            menuState.hide()
                                            val mediaMetadata = currentMediaMetadataProvider()

                                            try {
                                                context.startActivity(
                                                    Intent(Intent.ACTION_WEB_SEARCH).apply {
                                                        putExtra(
                                                            SearchManager.QUERY,
                                                            "${mediaMetadata.title} ${mediaMetadata.artist} lyrics"
                                                        )
                                                    }
                                                )
                                            } catch (_: ActivityNotFoundException) {
                                                context.toast(errorMsg)
                                            }
                                        }
                                    )

                                    MenuEntry(
                                        icon = R.drawable.sync,
                                        text = stringResource(R.string.refetch_lyrics),
                                        enabled = lyrics != null,
                                        onClick = {
                                            menuState.hide()

                                            transaction {
                                                runCatching {
                                                    currentEnsureSongInserted()

                                                    Database.upsert(
                                                        if (shouldShowSynchronizedLyrics) Lyrics(
                                                            songId = mediaId,
                                                            fixed = lyrics?.fixed,
                                                            synced = null,
                                                            wordSynced = null,
                                                            wordSyncedChecked = null
                                                        ) else Lyrics(
                                                            songId = mediaId,
                                                            fixed = null,
                                                            synced = lyrics?.synced,
                                                            wordSynced = null,
                                                            wordSyncedChecked = null
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    )

                                    if (shouldShowSynchronizedLyrics && lyrics?.wordSynced == null) {
                                        MenuEntry(
                                            icon = R.drawable.download,
                                            text = stringResource(R.string.pick_from_lrclib),
                                            onClick = {
                                                menuState.hide()
                                                picking = true
                                            }
                                        )
                                        MenuEntry(
                                            icon = R.drawable.play_skip_forward,
                                            text = stringResource(R.string.set_lyrics_start_offset),
                                            secondaryText = stringResource(
                                                R.string.set_lyrics_start_offset_description
                                            ),
                                            onClick = {
                                                menuState.hide()
                                                lyrics?.let {
                                                    val startTime = binder?.player?.currentPosition
                                                    query {
                                                        Database.upsert(it.copy(startTime = startTime))
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    )
                    .padding(all = 8.dp)
                    .size(20.dp)
                    .align(Alignment.BottomEnd)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordSyncedLine(
    line: WordSyncedLyrics.Line,
    positionProvider: () -> Float,
    activeColor: Color,
    inactiveColor: Color,
    backgroundActiveColor: Color,
    backgroundInactiveColor: Color,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val currentPositionProvider by rememberUpdatedState(positionProvider)
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val spaceWidth = remember(style, density) {
        with(density) {
            val spaced = textMeasurer.measure("a a", style).size.width
            val joined = textMeasurer.measure("aa", style).size.width
            (spaced - joined).toDp()
        }
    }
    val groups = remember(line) { line.words.toWordGroups() }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(
            space = spaceWidth,
            alignment = if (line.oppositeAligned) Alignment.End else Alignment.Start
        )
    ) {
        groups.forEach { group ->
            Row {
                group.forEach { word ->
                    WordSyncedWord(
                        word = word,
                        positionProvider = { currentPositionProvider() },
                        activeColor = if (word.background) backgroundActiveColor else activeColor,
                        inactiveColor = if (word.background) backgroundInactiveColor else inactiveColor,
                        style = style
                    )
                }
            }
        }
    }
}

@Composable
private fun WordSyncedWord(
    word: WordSyncedLyrics.Word,
    positionProvider: () -> Float,
    activeColor: Color,
    inactiveColor: Color,
    style: TextStyle
) {
    val fill by remember(word) { derivedStateOf { word.fill(positionProvider()) } }
    val lift by remember(word) { derivedStateOf { word.lift(positionProvider()) } }
    val text = remember(word) { if (word.separator.isBlank()) word.text else word.text + word.separator }

    Box(
        modifier = Modifier.graphicsLayer {
            translationY = -WORD_LIFT.toPx() * lift
        }
    ) {
        BasicText(
            text = text,
            style = style.copy(color = inactiveColor)
        )
        BasicText(
            text = text,
            style = style.copy(color = activeColor),
            modifier = Modifier.drawWithContent {
                if (fill > 0f) clipRect(right = size.width * fill) {
                    this@drawWithContent.drawContent()
                }
            }
        )
    }
}

private fun List<WordSyncedLyrics.Word>.toWordGroups(): List<List<WordSyncedLyrics.Word>> = buildList {
    var current = mutableListOf<WordSyncedLyrics.Word>()
    this@toWordGroups.forEach { word ->
        current += word
        if (word.separator.isNotEmpty()) {
            add(current)
            current = mutableListOf()
        }
    }
    if (current.isNotEmpty()) add(current)
}

private val WORD_LIFT = 3.dp
private const val WORD_LIFT_DURATION = 150f

private fun WordSyncedLyrics.Word.fill(position: Float) = when {
    position <= begin -> 0f
    position >= end || end <= begin -> 1f
    else -> (position - begin) / (end - begin)
}

private fun WordSyncedLyrics.Word.lift(position: Float): Float {
    val rise = ((position - begin) / WORD_LIFT_DURATION).coerceIn(0f, 1f)
    val fall = ((position - end) / WORD_LIFT_DURATION).coerceIn(0f, 1f)
    val amount = rise * (1f - fall)
    return amount * amount * (3f - 2f * amount)
}

@Composable
fun LrcLibSearchDialog(
    query: String,
    setQuery: (String) -> Unit,
    onDismiss: () -> Unit,
    onPick: (Track) -> Unit,
    modifier: Modifier = Modifier
) = DefaultDialog(
    onDismiss = onDismiss,
    horizontalPadding = 0.dp,
    modifier = modifier
) {
    val [_, typography] = LocalAppearance.current

    val tracks = remember { mutableStateListOf<Track>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        loading = true
        error = false

        delay(1000.milliseconds)

        LrcLib.lyrics(
            query = query,
            synced = true
        )?.onSuccess { newTracks ->
            tracks.clear()
            tracks.addAll(newTracks.filter { !it.syncedLyrics.isNullOrBlank() })
            loading = false
            error = false
        }?.onFailure {
            loading = false
            error = true
            it.printStackTrace()
        } ?: run { loading = false }
    }

    TextField(
        value = query,
        onValueChange = setQuery,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        maxLines = 1,
        singleLine = true
    )
    Spacer(modifier = Modifier.height(8.dp))

    when {
        loading -> CircularProgressIndicator(
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        error || tracks.isEmpty() -> BasicText(
            text = stringResource(R.string.no_lyrics_found),
            style = typography.s.semiBold.center,
            modifier = Modifier
                .padding(all = 24.dp)
                .align(Alignment.CenterHorizontally)
        )

        else -> ValueSelectorDialogBody(
            onDismiss = onDismiss,
            title = stringResource(R.string.choose_lyric_track),
            selectedValue = null,
            values = tracks.toImmutableList(),
            onValueSelect = {
                transaction {
                    onPick(it)
                    onDismiss()
                }
            },
            valueText = {
                "${it.artistName} - ${it.trackName} (${
                    it.duration.seconds.toComponents { minutes, seconds, _ ->
                        "$minutes:${seconds.toString().padStart(2, '0')}"
                    }
                })"
            }
        )
    }
}

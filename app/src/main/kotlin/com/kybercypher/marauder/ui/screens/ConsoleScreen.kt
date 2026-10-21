package com.kybercypher.marauder.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kybercypher.marauder.device.LogLine
import com.kybercypher.marauder.ui.Pill
import com.kybercypher.marauder.ui.theme.Palette
import com.kybercypher.marauder.ui.theme.color

/**
 * The console.
 *
 * Auto-follow is the subtle part, and it has a documented two-stage bug:
 *  1. Geometry heuristics for "am I at the bottom" are true almost everywhere
 *     when the list has bottom padding, so the list yanks to the end constantly.
 *  2. After fixing that with !isScrollInProgress, a quick flick still gets
 *     undone, because Compose starts the fling in a SEPARATE scroll session
 *     after the finger lifts — leaving a frame where isScrollInProgress is
 *     false, during which a scroll request cancels the fling.
 *
 * The fix: read every signal in ONE snapshotFlow so the decision comes from a
 * single consistent snapshot, use canScrollForward rather than geometry, and
 * use scrollToItem (NOT animateScrollToItem — at 15 Hz appends the animations
 * queue up, interfere, and each keeps isScrollInProgress true).
 *
 * When following is paused, show a pill. Fighting the user is bad; silently
 * stopping with no indication is also bad.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConsoleScreen(
    lines: List<LogLine>,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
) {
    var following by remember { mutableStateOf(true) }

    LaunchedEffect(state) {
        snapshotFlow {
            Triple(state.isScrollInProgress, state.canScrollForward, state.layoutInfo.totalItemsCount)
        }.collect { (scrolling, canForward, count) ->
            when {
                scrolling -> following = false
                !canForward && count > 0 -> following = true
            }
        }
    }

    LaunchedEffect(lines.size, following) {
        if (following && lines.isNotEmpty()) state.scrollToItem(lines.lastIndex)
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        ) {
            items(
                items = lines,
                key = { it.seq },
                contentType = { it.kind },   // keeps item reuse buckets clean
            ) { line ->
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = line.kind.color(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!following) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            ) {
                Pill("JUMP TO LATEST", Palette.Violet) { following = true }
            }
        }
    }
}

package com.kybercypher.marauder.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kybercypher.marauder.ui.theme.Palette

/**
 * The `//` from the Kyber//Cypher wordmark, used as the single structural motif.
 * One motif used consistently beats five used once.
 */
@Composable
fun Breadcrumb(vararg parts: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, part ->
            if (i > 0) {
                Text(
                    "  //  ",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.Violet,
                )
            }
            Text(
                part.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = if (i == parts.lastIndex) Palette.White else Palette.Dim,
            )
        }
    }
}

/**
 * Glow, done as a radial brush behind the content rather than with blur or
 * RenderEffect. Setting a RenderEffect always forces an offscreen buffer, and
 * Modifier.blur is API 31+ and blurs the whole layer.
 *
 * HARD RULE: never put this inside a LazyColumn item. Per-item offscreen work at
 * 15 Hz plus scrolling destroys the frame budget. Glow is punctuation, not
 * texture — at most three to five glowing elements on screen, and ideally only
 * ONE thing glows at a time: whatever is currently live.
 */
@Composable
fun GlowDot(color: Color, pulsing: Boolean, size: Int = 10) {
    // Animate unconditionally and select, rather than calling animateFloat
    // inside an if — a conditional composable call here would restart the
    // animation whenever `pulsing` flipped.
    val transition = rememberInfiniteTransition(label = "glow")
    val pulse by transition.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val alpha = if (pulsing) pulse else 1f

    Box(
        Modifier
            .size((size * 3).dp)
            .drawBehind {
                // Motion must mean something: this pulses only while live.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.55f * alpha), Color.Transparent),
                        center = Offset(this.size.width / 2, this.size.height / 2),
                        radius = this.size.minDimension / 2,
                    )
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size.dp).background(color.copy(alpha = alpha), CircleShape))
    }
}

/** A hairline rule in dim chrome. Density and alignment are what make it look like a tool. */
@Composable
fun Rule(modifier: Modifier = Modifier, color: Color = Palette.VioletDim) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
fun Pill(text: String, color: Color, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .border(1.dp, color.copy(alpha = 0.7f), shape)
            .background(color.copy(alpha = 0.10f), shape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

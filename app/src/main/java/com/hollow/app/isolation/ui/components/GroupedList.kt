package com.hollow.app.isolation.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Signature look: rows in one stack, 2dp gap, outer corners 24dp, inner 6dp. */
class GroupedListScope {
    internal val items = mutableListOf<Pair<Boolean, @Composable () -> Unit>>()
    fun add(visible: Boolean = true, content: @Composable () -> Unit) {
        items += visible to content
    }
}

@Composable
fun GroupedList(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
    content: GroupedListScope.() -> Unit
) {
    val items = GroupedListScope().apply(content).items
    if (items.isEmpty()) return
    val visibleIdx = items.mapIndexedNotNull { i, item -> if (item.first) i else null }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = horizontalPadding)) {
        items.forEachIndexed { index, (visible, body) ->
            val pos = visibleIdx.indexOf(index)
            val shape by animateShape(pos == 0, pos >= 0 && pos == visibleIdx.lastIndex)
            AnimatedVisibility(
                visible = visible,
                enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) +
                        fadeIn(spring(stiffness = Spring.StiffnessMediumLow)),
                exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
            ) {
                Column {
                    Surface(
                        shape = shape,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        body()
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

@Composable
private fun animateShape(first: Boolean, last: Boolean): State<Shape> {
    val top by animateDpAsState(
        targetValue = if (first) 24.dp else 6.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "top"
    )
    val bottom by animateDpAsState(
        targetValue = if (last) 24.dp else 6.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "bottom"
    )
    return remember { derivedStateOf { RoundedCornerShape(top, top, bottom, bottom) } }
}

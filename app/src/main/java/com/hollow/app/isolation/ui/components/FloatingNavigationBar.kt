package com.hollow.app.isolation.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class NavItem(
    val label: String,
    val icon: ImageVector,
    val testTag: String = ""
)

private data class Slot(val left: Dp, val width: Dp)

/**
 * Floating pill nav: 64dp tall, 32dp corners, 12dp margin. ONE primary pill springs between items;
 * selected item expands to show icon + label, unselected shows label only.
 */
@Composable
fun FloatingNavigationBar(
    items: List<NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var slots by remember { mutableStateOf(emptyMap<Int, Slot>()) }
    val sel = slots[selectedIndex]
    val pillX by animateDpAsState(sel?.left ?: 0.dp, spring(0.8f, Spring.StiffnessMediumLow), label = "x")
    val pillW by animateDpAsState(sel?.width ?: 0.dp, spring(0.8f, Spring.StiffnessMediumLow), label = "w")

    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(12.dp)
            .testTag("floating_navigation_bar")
    ) {
        Box(
            modifier = Modifier
                .height(64.dp)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (sel != null) {
                Box(
                    Modifier
                        .offset(x = pillX)
                        .width(pillW)
                        .height(44.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { i, item ->
                    val selected = i == selectedIndex
                    val color by animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        label = "c"
                    )
                    Row(
                        modifier = Modifier
                            .onGloballyPositioned { c ->
                                val l = with(density) { c.positionInParent().x.toDp() }
                                val w = with(density) { c.size.width.toDp() }
                                if (slots[i] != Slot(l, w)) slots = slots + (i to Slot(l, w))
                            }
                            .clip(CircleShape)
                            .selectable(selected, role = Role.Tab) { if (!selected) onSelect(i) }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .then(if (item.testTag.isNotEmpty()) Modifier.testTag(item.testTag) else Modifier),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AnimatedVisibility(
                            visible = selected,
                            enter = expandHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) +
                                    fadeIn(spring(stiffness = Spring.StiffnessMediumLow)),
                            exit = shrinkHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(item.icon, null, Modifier.size(20.dp), tint = color)
                                Spacer(Modifier.width(6.dp))
                            }
                        }
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = color,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

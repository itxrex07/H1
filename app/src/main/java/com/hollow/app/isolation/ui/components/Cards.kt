package com.hollow.app.isolation.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Hero card: primaryContainer + 28dp corners + 20dp padding. */
@Composable
fun HeroCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) = Card(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.extraLarge,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        content = content
    )
}

/** Info card: surfaceContainerLow + 28dp corners + 20dp padding. */
@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) = Card(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.extraLarge,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        content = content
    )
}

/** Error/warning card: errorContainer + 28dp. Button inside inverts: container=onErrorContainer. */
@Composable
fun WarningCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) = Surface(
    shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.errorContainer,
    modifier = modifier.fillMaxWidth()
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        content = content
    )
}

/** Grid tile: 16dp corners, surfaceContainerLow (secondaryContainer when selected), 1:1 thumb + 10/8dp text pad. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridTile(
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    thumb: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) { thumb() }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

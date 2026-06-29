package com.greenart7c3.citrine.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest

/**
 * Shows an nsite's icon (a Blossom URL or a local [java.io.File]) via Coil, falling back to a
 * globe placeholder while loading or when no icon is available/decodable.
 */
@Composable
fun NsiteIcon(
    model: Any?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        if (model == null) {
            Placeholder()
        } else {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(model)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp),
                loading = { Placeholder() },
                error = { Placeholder() },
            )
        }
    }
}

@Composable
private fun Placeholder() {
    Icon(
        imageVector = Icons.Default.Public,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(28.dp),
    )
}

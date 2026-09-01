package com.mewmix.nabu.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mewmix.nabu.ui.brutalist.PanelBox

data class CreditEntry(
    val name: String,
    val contribution: String,
    val url: String? = null,
    val featured: Boolean = false,
)

data class CreditGroup(
    val title: String,
    val subtitle: String,
    val entries: List<CreditEntry>,
)

@Composable
fun CreditsConstellationScreen() {
    val communityCredits = remember {
        CreditGroup(
            title = "Community",
            subtitle = "People and projects",
            entries = listOf(
                CreditEntry(
                    name = "djzeroone",
                    contribution = "Bubble Pop, UI fixes, and Voice Lab polish",
                    url = "https://github.com/djzeroone",
                    featured = true,
                ),
                CreditEntry(
                    name = "richdrummer33 · PR #73",
                    contribution = "Enhancements shipped in 0.5.0 and 0.5.1",
                    url = "https://github.com/mewmix/nabu/pull/73",
                    featured = true,
                ),
                CreditEntry(
                    name = "puff-dayo",
                    contribution = "Original Android Kokoro app and style mixer",
                    url = "https://github.com/puff-dayo/Kokoro-82M-Android",
                    featured = true,
                ),
                CreditEntry(
                    name = "Google AI Edge Gallery",
                    contribution = "LLM integration and model downloads",
                    url = "https://github.com/google-ai-edge/gallery",
                ),
                CreditEntry(
                    name = "LiteRT Community",
                    contribution = "Android-ready models",
                    url = "https://huggingface.co/litert-community",
                ),
                CreditEntry(
                    name = "jsoup",
                    contribution = "EPUB HTML parsing (MIT)",
                    url = "https://jsoup.org/",
                ),
            ),
        )
    }
    val modelCredits = remember {
        CreditGroup(
            title = "Models & foundations",
            subtitle = "Open work beneath Nabu",
            entries = listOf(
                CreditEntry(
                    name = "Kokoro",
                    contribution = "Text-to-speech model (Apache 2.0)",
                    url = "https://huggingface.co/hexgrad/Kokoro-82M",
                ),
                CreditEntry(
                    name = "Kokoro ONNX",
                    contribution = "ONNX conversion and runtime (MIT)",
                    url = "https://github.com/thewh1teagle/kokoro-onnx",
                ),
                CreditEntry(
                    name = "Supertonic",
                    contribution = "Lightweight Android TTS",
                    url = "https://github.com/supertonic-tts/supertonic",
                ),
                CreditEntry(
                    name = "Soprano",
                    contribution = "Model and inference code (Apache 2.0)",
                    url = "https://github.com/ekwek1/soprano",
                ),
                CreditEntry(
                    name = "Soprano Web ONNX",
                    contribution = "Browser inference and ONNX packaging",
                    url = "https://github.com/KevinAHM/soprano-web-onnx",
                ),
                CreditEntry(
                    name = "CMU Pronouncing Dictionary",
                    contribution = "Pronunciation data",
                    url = "https://www.speech.cs.cmu.edu/cgi-bin/cmudict",
                ),
                CreditEntry(
                    name = "IPA Transcribers",
                    contribution = "Language transliteration (GPL-3.0)",
                    url = "https://github.com/kotlinguistics/IPA-Transcribers",
                ),
                CreditEntry(
                    name = "Android NNAPI",
                    contribution = "Neural-network acceleration",
                    url = "https://developer.android.com/ndk/guides/neuralnetworks",
                ),
            ),
        )
    }

    PanelBox(
        title = "Credits",
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            val useColumns = maxWidth >= 720.dp
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                CreditsHero()
                if (useColumns) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        CreditSection(communityCredits, Modifier.weight(1f))
                        CreditSection(modelCredits, Modifier.weight(1f))
                    }
                } else {
                    CreditSection(communityCredits)
                    CreditSection(modelCredits)
                }
                Text(
                    text = "Built with open source and shared experiments.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CreditsHero() {
    val transition = rememberInfiniteTransition(label = "creditsTwinkle")
    val pulse = transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "creditsStarPulse",
    ).value
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val sparkle = MaterialTheme.colorScheme.onPrimaryContainer

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        lerp(surface, MaterialTheme.colorScheme.secondaryContainer, 0.62f),
                    ),
                ),
            )
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                RoundedCornerShape(22.dp),
            )
            .padding(horizontal = 22.dp, vertical = 26.dp),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val stars = listOf(
                0.08f to 0.22f,
                0.17f to 0.78f,
                0.46f to 0.14f,
                0.72f to 0.74f,
                0.87f to 0.25f,
                0.94f to 0.62f,
            )
            stars.forEachIndexed { index, (x, y) ->
                drawCircle(
                    color = if (index % 2 == 0) primary else secondary,
                    radius = if (index % 3 == 0) 5f else 3f,
                    center = Offset(size.width * x, size.height * y),
                    alpha = 0.55f,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .graphicsLayer {
                            scaleX = pulse
                            scaleY = pulse
                            rotationZ = (pulse - 1f) * 18f
                        },
                    tint = sparkle,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "OPEN SOURCE • BUILT TOGETHER",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = "Built Together",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = "Tap a credit to explore the work.",
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun CreditSection(group: CreditGroup, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = group.title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = group.subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(2.dp))
        group.entries.forEach { entry ->
            CreditCard(entry)
        }
    }
}

@Composable
private fun CreditCard(entry: CreditEntry) {
    val uriHandler = LocalUriHandler.current
    val accent = if (entry.featured) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.secondary
    }
    val container = if (entry.featured) {
        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.url != null) {
                entry.url?.let { url -> runCatching { uriHandler.openUri(url) } }
            },
        shape = RoundedCornerShape(16.dp),
        color = container,
        tonalElevation = if (entry.featured) 3.dp else 0.dp,
        border = BorderStroke(
            1.dp,
            accent.copy(alpha = if (entry.featured) 0.72f else 0.28f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(accent.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    modifier = Modifier.size(if (entry.featured) 20.dp else 16.dp),
                    tint = accent,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = entry.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (entry.featured) FontWeight.ExtraBold else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.contribution,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (entry.url != null) {
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Open ${entry.name}",
                    modifier = Modifier.size(18.dp),
                    tint = accent,
                )
            }
        }
    }
}

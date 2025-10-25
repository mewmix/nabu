package com.example.nabu.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.mewmix.nabu.ui.brutalist.PanelBox
import com.example.nabu.utils.getAppVersion

// Data classes representing credits hierarchy

data class CreditEntry(val text: String, val url: String? = null)

data class CreditGroup(
    val title: String,
    val entries: List<CreditEntry> = emptyList(),
    val children: List<CreditGroup> = emptyList()
)

@Composable
fun CreditsConstellationScreen() {
    // FIX: The legacyCredits data structure was flattened to prevent overlapping nodes.
    // The nested group was removed, and its entries were moved to the parent.
    val legacyCredits = remember {
        CreditGroup(
            title = "Original Credits",
            entries = listOf(
                CreditEntry(
                    "Kokoro: a frontier TTS model (Apache 2.0)",
                    "https://huggingface.co/hexgrad/Kokoro-82M"
                ),
                CreditEntry(
                    "Kokoro-ONNX: converted kokoro (MIT)",
                    "https://github.com/thewh1teagle/kokoro-onnx"
                ),
                CreditEntry(
                    "CMU dict: a pronunciation dictionary",
                    "http://www.speech.cs.cmu.edu/cgi-bin/cmudict"
                ),
                CreditEntry(
                    "IPA Transcribers: language transliterators (GPL-3.0)",
                    "https://github.com/kotlinguistics/IPA-Transcribers"
                ),
                CreditEntry(
                    "Android NNAPI: a machine learning API",
                    "https://developer.android.com/ndk/guides/neuralnetworks"
                )
            ),
            children = emptyList() // No longer has nested children
        )
    }

    val ourCredits = remember {
        CreditGroup(
            title = "Our Credit Wall",
            entries = listOf(
                CreditEntry(
                    "puff-dayo: original Android Kokoro TTS implementation, ONNX runtime, style mixer, app template",
                    "https://github.com/puff-dayo/Kokoro-82M-Android"
                ),
                CreditEntry(
                    "Google AI Edge Gallery for LLM and Hugging Face model downloading",
                    "https://github.com/google-ai-edge/gallery"
                ),
                CreditEntry(
                    "LiteRT community on Hugging Face",
                    "https://huggingface.co/litert-community",
                ),
                CreditEntry(
                    "jsoup: HTML parser for EPUB support (MIT) by Jonathan Hedley",
                    "https://jsoup.org/",
                )
            )
        )
    }

    var expandedGroup by remember { mutableStateOf<CreditGroup?>(null) }
    val context = LocalContext.current
    val versionName = remember { getAppVersion(context) }

    PanelBox(
        title = "Credits",
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(expandedGroup) {
                        if (expandedGroup != null) {
                            detectTapGestures { expandedGroup = null }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    StarCluster(
                        group = legacyCredits,
                        expanded = expandedGroup == legacyCredits,
                        onToggle = {
                            expandedGroup = if (expandedGroup == legacyCredits) null else legacyCredits
                        }
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    StarCluster(
                        group = ourCredits,
                        expanded = expandedGroup == ourCredits,
                        onToggle = {
                            expandedGroup = if (expandedGroup == ourCredits) null else ourCredits
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Version $versionName",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun StarCluster(
    group: CreditGroup,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
) {
    var internalExpanded by remember { mutableStateOf(false) }
    val isExpanded = expanded ?: internalExpanded
    val toggle = onToggle ?: { internalExpanded = !internalExpanded }
    var radius = (size / 2) - 24.dp
    val density = LocalDensity.current

    // Constant-time base angle that drives all child orbiters
    val baseAngle by rememberInfiniteTransition(label = "orbitBase")
        .animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 22000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "orbitAngle"
        )

    val nodes = remember(group) {
        group.children.map { Node.Group(it) } + group.entries.map { Node.Entry(it) }
    }
    val numNodes = nodes.size
    if (numNodes > 1) {
        val nodeMaxWidth = 120.dp
        val safetyPadding = 20.dp
        val minChord = nodeMaxWidth + safetyPadding
        val halfAngle = PI / numNodes.toDouble()
        val sinHalf = sin(halfAngle)
        if (sinHalf > 0.0001) {
            val minRadiusValue = minChord.value / (2f * sinHalf.toFloat())
            val calculatedMinRadius = minRadiusValue.dp
            if (calculatedMinRadius > radius) {
                radius = calculatedMinRadius
            }
        }
    }

    Box(
        modifier = modifier
            .padding(8.dp)
            .size(size),
        contentAlignment = Alignment.Center
    ) {
        // Draw orbiting nodes behind the hub
        AnimatedVisibility(visible = isExpanded) {
            if (nodes.isNotEmpty()) {
                val radiusPx = with(density) { radius.toPx() }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    nodes.forEachIndexed { index, node ->
                        // Deterministic per-node parameters
                        val offsetAngle = (2f * PI * index / nodes.size).toFloat()
                        val speed = 1.0f
                        val ring = 1.0f
                        // Slight wobble to avoid perfect symmetry
                        val wobble = 1f

                        val angle = baseAngle * speed + offsetAngle
                        val distancePx = radiusPx * ring * wobble

                        val x = (cos(angle) * distancePx).roundToInt()
                        val y = (sin(angle) * distancePx).roundToInt()

                        when (node) {
                            is Node.Group -> StarCluster(
                                group = node.group,
                                modifier = Modifier.offset { IntOffset(x, y) },
                                size = size * 0.5f
                            )
                            is Node.Entry -> StarNode(
                                entry = node.entry,
                                modifier = Modifier.offset { IntOffset(x, y) }
                            )
                        }
                    }
                }
            }
        }

        // Hub (central star + title) drawn above orbiters
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = toggle) {
                Icon(Icons.Default.Star, contentDescription = group.title)
            }
            Text(
                text = group.title,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center
            )
        }
    }
}


private sealed class Node {
    data class Group(val group: CreditGroup) : Node()
    data class Entry(val entry: CreditEntry) : Node()
}

@Composable
private fun StarNode(entry: CreditEntry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(onClick = {
            entry.url?.let {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it))
                context.startActivity(intent)
            }
        }) {
            Icon(Icons.Default.Star, contentDescription = entry.text, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
        }
        Text(
            text = entry.text,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 120.dp)
        )
    }
}
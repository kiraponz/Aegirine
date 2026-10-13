package com.kira3.ametrine.emulation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import android.graphics.Matrix
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kira3.ametrine.R
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.mutableStateListOf
import com.kira3.ametrine.data.GeminiChat
import com.kira3.ametrine.data.GeminiMessage
import java.io.ByteArrayOutputStream
import java.io.File

private val RainbowColors = listOf(
    Color(0xFFFF5C8A), Color(0xFFFFB347), Color(0xFFF7F75E), Color(0xFF5CF08A),
    Color(0xFF4FD8FF), Color(0xFF8A7CFF), Color(0xFFFF5CF0), Color(0xFFFF5C8A),
)

/** A slowly turning rainbow edge with a soft glow around it. */
private fun Modifier.rainbowAura(shape: Shape): Modifier = composed {
    val angle by rememberInfiniteTransition(label = "aura").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)),
        label = "auraAngle",
    )
    drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this) as? Outline.Rounded ?: return@drawBehind
        val path = Path().apply { addRoundRect(outline.roundRect) }.asAndroidPath()
        val shader = android.graphics.SweepGradient(
            size.width / 2, size.height / 2,
            RainbowColors.map { it.toArgb() }.toIntArray(), null,
        ).apply { setLocalMatrix(Matrix().apply { setRotate(angle, size.width / 2, size.height / 2) }) }
        // A real blur for the glow (stacked strokes show their corners); the thin line stays sharp.
        for ((width, blur, alpha) in listOf(
            Triple(14.dp, 24.dp, 0.9f),
            Triple(7.dp, 10.dp, 0.9f),
            Triple(1.5.dp, 0.dp, 0.95f),
        )) {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeJoin = android.graphics.Paint.Join.ROUND
                strokeWidth = width.toPx()
                this.shader = shader
                this.alpha = (alpha * 255).toInt()
                if (blur > 0.dp) maskFilter = android.graphics.BlurMaskFilter(blur.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            drawContext.canvas.nativeCanvas.drawPath(path, paint)
        }
    }
}

/** A frame ready to send: [jpeg] for the API, [preview] for the panel. */
class AiShot(val jpeg: ByteArray, val preview: ImageBitmap)

/** Where the AI panel stands while a question is out. */
sealed interface AiState {
    data object Idle : AiState
    data object Loading : AiState
    data class Error(val message: String) : AiState
}

/** What the AI panel shows. [hasImage] is false for a conversation picked from the history: its screenshot is gone. */
class AiPanelData(
    val shot: AiShot,
    val hasImage: Boolean,
    val messages: List<GeminiMessage>,
    val state: AiState,
    val history: List<GeminiChat>,
    /** Memos and history are kept per game id; without one there is nowhere to put them. */
    val canKeep: Boolean,
)

/** What the AI panel can ask for. */
interface AiPanelActions {
    fun ask(question: String)
    fun saveMemo(question: String, answer: String)
    fun openChat(chat: GeminiChat)
    fun deleteChat(chat: GeminiChat)
    fun newChat()
    fun close()
}

/** Reads [png], shrinks it so the longer side is at most [MAX_SIDE] pixels, and compresses it to a JPEG. Null if it cannot be read. */
fun prepareAiShot(png: File): AiShot? {
    val source = BitmapFactory.decodeFile(png.path) ?: return null
    val scale = MAX_SIDE.toFloat() / maxOf(source.width, source.height)
    val bitmap = if (scale < 1f) {
        Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true)
    } else {
        source
    }
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
    return AiShot(out.toByteArray(), bitmap.asImageBitmap())
}

private const val MAX_SIDE = 1280
private const val JPEG_QUALITY = 85

/** Asks Gemini about the game screen. Drawn over the game like the touch pad. */
@Composable
fun AiButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .border(1.5.dp, Color.White.copy(alpha = 0.5f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_ai),
            contentDescription = stringResource(R.string.ai_button),
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.padding(10.dp),
        )
    }
}

/**
 * The panel for [data] (null = closed). It fades in and floats up when opened, and floats back
 * down while fading out when closed; during the fade-out it ignores touches.
 */
@Composable
fun AiPanelHost(data: AiPanelData?, actions: AiPanelActions) {
    val appear = remember { Animatable(0f) }
    var last by remember { mutableStateOf<AiPanelData?>(null) }
    if (data != null) last = data
    LaunchedEffect(data != null) {
        appear.animateTo(if (data != null) 1f else 0f, tween(450, easing = FastOutSlowInEasing))
        if (data == null) last = null
    }
    val showing = last ?: return
    val open = data != null
    AiPanel(
        showing,
        object : AiPanelActions {
            override fun ask(question: String) { if (open) actions.ask(question) }
            override fun saveMemo(question: String, answer: String) { if (open) actions.saveMemo(question, answer) }
            override fun openChat(chat: GeminiChat) { if (open) actions.openChat(chat) }
            override fun deleteChat(chat: GeminiChat) { if (open) actions.deleteChat(chat) }
            override fun newChat() { if (open) actions.newChat() }
            override fun close() { if (open) actions.close() }
        },
        appear = appear.value,
    )
}

@Composable
fun AiPanel(data: AiPanelData, actions: AiPanelActions, appear: Float) {
    val state = data.state
    val messages = data.messages
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var question by rememberSaveable { mutableStateOf("") }
    val savedAnswers = remember { mutableStateListOf<String>() }
    val canSend = question.isNotBlank() && state != AiState.Loading
    val scroll = rememberScrollState()
    // A new answer is the news: show it, and empty the box for the next question.
    LaunchedEffect(messages) {
        question = ""
        scroll.animateScrollTo(scroll.maxValue)
    }
    LaunchedEffect(state) { if (state != AiState.Idle) scroll.animateScrollTo(scroll.maxValue) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f * appear))
            .clickable(interactionSource = null, indication = null, onClick = actions::close)
            .safeDrawingPadding()
            .imePadding()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    // Without this the fading panel is flattened into a layer clipped to its bounds,
                    // which cuts the glow off into hard corners.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    alpha = appear
                    val scale = 0.92f + 0.08f * appear
                    scaleX = scale
                    scaleY = scale
                    translationY = (1f - appear) * 24.dp.toPx()
                }
                .rainbowAura(MaterialTheme.shapes.extraLarge)
                .clickable(interactionSource = null, indication = null) {},
        ) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.ai_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (showHistory) {
                        TextButton(onClick = { showHistory = false }) { Text(stringResource(R.string.ai_back)) }
                    } else {
                        if (messages.isNotEmpty() || !data.hasImage) {
                            TextButton(onClick = actions::newChat) { Text(stringResource(R.string.ai_new_chat)) }
                        }
                        if (data.canKeep) {
                            TextButton(onClick = { showHistory = true }) { Text(stringResource(R.string.ai_history)) }
                        }
                    }
                    TextButton(onClick = actions::close) { Text(stringResource(R.string.menu_resume)) }
                }
                if (showHistory) {
                    HistoryList(
                        data.history,
                        onOpen = { showHistory = false; actions.openChat(it) },
                        onDelete = actions::deleteChat,
                    )
                } else {
                    val hasConversation = messages.isNotEmpty() || state != AiState.Idle
                    if (hasConversation) {
                        Column(
                            Modifier
                                .heightIn(max = 320.dp)
                                .verticalScroll(scroll)
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            messages.forEachIndexed { index, message ->
                                if (message.fromUser) {
                                    Text(
                                        message.text,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    SelectionContainer {
                                        Text(message.text, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    if (data.canKeep) {
                                        val asked = messages.getOrNull(index - 1)?.takeIf { it.fromUser }?.text.orEmpty()
                                        val saved = message.text in savedAnswers
                                        TextButton(
                                            onClick = {
                                                actions.saveMemo(asked, message.text)
                                                savedAnswers += message.text
                                            },
                                            enabled = !saved,
                                        ) { Text(stringResource(if (saved) R.string.ai_memo_saved else R.string.ai_save_memo)) }
                                    }
                                }
                            }
                            when (state) {
                                AiState.Idle -> Unit
                                AiState.Loading -> Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                                is AiState.Error -> Text(
                                    state.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        if (data.hasImage) {
                            Image(
                                data.shot.preview,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(width = 112.dp, height = 84.dp).clip(MaterialTheme.shapes.small),
                            )
                        }
                        OutlinedTextField(
                            value = question,
                            onValueChange = { question = it },
                            placeholder = { Text(stringResource(R.string.ai_question_hint)) },
                            supportingText = if (data.hasImage) null else ({ Text(stringResource(R.string.ai_no_screenshot)) }),
                            minLines = 2,
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    FilledTonalButton(
                        onClick = { actions.ask(question.trim()) },
                        enabled = canSend,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    ) { Text(stringResource(R.string.ai_send)) }
                }
            }
        }
    }
}

/** The game's earlier conversations, newest first: tap to open, the bin to delete. */
@Composable
private fun HistoryList(history: List<GeminiChat>, onOpen: (GeminiChat) -> Unit, onDelete: (GeminiChat) -> Unit) {
    if (history.isEmpty()) {
        Text(
            stringResource(R.string.ai_history_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        )
        return
    }
    val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
    LazyColumn(Modifier.heightIn(max = 400.dp)) {
        items(history, key = { it.id }) { chat ->
            ListItem(
                headlineContent = { Text(chat.title, maxLines = 2) },
                supportingContent = { Text(format.format(java.util.Date(chat.updatedAt))) },
                trailingContent = {
                    IconButton(onClick = { onDelete(chat) }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.ai_history_delete))
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onOpen(chat) },
            )
        }
    }
}

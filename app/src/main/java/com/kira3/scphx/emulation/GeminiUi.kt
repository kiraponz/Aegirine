package com.kira3.scphx.emulation

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
import com.kira3.scphx.R
import java.io.ByteArrayOutputStream
import java.io.File

/** A frame ready to send: [jpeg] for the API, [preview] for the panel. */
class AiShot(val jpeg: ByteArray, val preview: ImageBitmap)

/** What the AI panel shows below the question. */
sealed interface AiState {
    data object Idle : AiState
    data object Loading : AiState
    data class Answer(val text: String) : AiState
    data class Error(val message: String) : AiState
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

/** The screen as it was when the button was pressed, a question box, and the answer once it arrives. The game stays paused. */
@Composable
fun AiPanel(
    shot: AiShot,
    state: AiState,
    onAsk: (String) -> Unit,
    /** Null when there is nowhere to keep a memo (no game id). */
    onSaveMemo: ((question: String, answer: String) -> Unit)?,
    onClose: () -> Unit,
) {
    var savedAnswer by rememberSaveable { mutableStateOf<String?>(null) }
    var question by rememberSaveable { mutableStateOf("") }
    val canSend = question.isNotBlank() && state != AiState.Loading
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = null, indication = null, onClick = onClose)
            .safeDrawingPadding()
            .imePadding()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().clickable(interactionSource = null, indication = null) {},
        ) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.ai_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClose) { Text(stringResource(R.string.menu_resume)) }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Image(
                        shot.preview,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(width = 112.dp, height = 84.dp).clip(MaterialTheme.shapes.small),
                    )
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it },
                        placeholder = { Text(stringResource(R.string.ai_question_hint)) },
                        minLines = 2,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        modifier = Modifier.weight(1f),
                    )
                }
                FilledTonalButton(
                    onClick = { onAsk(question.trim()) },
                    enabled = canSend,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                ) { Text(stringResource(R.string.ai_send)) }
                when (state) {
                    AiState.Idle -> Unit
                    AiState.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    is AiState.Answer -> {
                        SelectionContainer {
                            Text(
                                state.text,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .heightIn(max = 280.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                            )
                        }
                        if (onSaveMemo != null) {
                            TextButton(
                                onClick = {
                                    onSaveMemo(question.trim(), state.text)
                                    savedAnswer = state.text
                                },
                                enabled = savedAnswer != state.text,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            ) { Text(stringResource(if (savedAnswer == state.text) R.string.ai_memo_saved else R.string.ai_save_memo)) }
                        }
                    }
                    is AiState.Error -> Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

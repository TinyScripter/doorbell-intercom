package de.kleinabersmart.doorbellintercom.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.kleinabersmart.doorbellintercom.R
import de.kleinabersmart.doorbellintercom.ui.theme.DoorBrush
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme
import de.kleinabersmart.doorbellintercom.ui.theme.onVideoTextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Ein eingeblendeter Slider: [label] steht in der Leiste und ist die TalkBack-Beschreibung,
 * [actionLabel] nennt TalkBack als Aktion beim Doppeltipp. Ein neuer [token] setzt den Knopf
 * zurück und startet das automatische Ausblenden neu.
 */
@Immutable
data class SlideConfirmRequest(val label: String, val actionLabel: String, val token: Long)

/**
 * Tür-Button getippt: blendet den Slider mit [label] ein bzw. – ist er für dasselbe Label
 * schon sichtbar – wieder aus (zweiter Tap = abbrechen).
 */
fun SlideConfirmRequest?.toggled(label: String, actionLabel: String): SlideConfirmRequest? =
    if (this?.label == label) null else SlideConfirmRequest(label, actionLabel, token = (this?.token ?: 0L) + 1)

internal object SlideToConfirmDefaults {
    /** Ab diesem Anteil des Wegs gilt das Schieben als bestätigt. */
    const val CONFIRM_THRESHOLD = 0.9f
    const val AUTO_HIDE_MS = 6000L
    const val SNAP_BACK_MS = 200

    val Height = 64.dp
    val TrackInset = 4.dp

    /** Abstand zu Bildschirmrand und Buttons (vorher Widget.DoorbellIntercom.DoorSlider). */
    val OuterPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 8.dp)
}

/** Greift nur am Knopf (± [inset]); ein Tap auf die Leiste soll nichts auslösen. */
internal fun isOnThumb(x: Float, offset: Float, thumbSize: Float, inset: Float): Boolean {
    val thumbStart = inset + offset
    return x >= thumbStart - inset && x <= thumbStart + thumbSize + inset
}

internal fun slideProgress(offset: Float, maxOffset: Float): Float = if (maxOffset > 0) offset / maxOffset else 0f

/** Wie AccelerateDecelerateInterpolator, der Standard von ValueAnimator. */
private val AccelerateDecelerateEasing = Easing { t -> (cos((t + 1) * PI) / 2.0 + 0.5).toFloat() }

/**
 * "Zum Öffnen schieben": Bestätigung für "Tür öffnen", damit ein versehentlicher Tap
 * (Hosentasche, Kind) nicht die Haustür öffnet. Der Tür-Button blendet den Slider nur ein
 * ([toggled]); ausgelöst wird erst, wenn man den Knopf bis ans Ende zieht. Ohne Bedienung
 * ruft er nach [SlideToConfirmDefaults.AUTO_HIDE_MS] [onDismiss] auf.
 *
 * Ohne [request] nimmt er keinen Platz ein (wie visibility=gone). Bestätigen ruft erst
 * [onDismiss] (ausblenden), dann [onConfirmed] – die Aktion selbst kennt nur der Aufrufer.
 *
 * Für TalkBack gilt der Doppeltipp (Klick) als Bestätigung – Wischen ist dort kaum möglich,
 * und der Doppeltipp ist schon eine bewusste Aktion.
 */
@Composable
fun SlideToConfirm(
    request: SlideConfirmRequest?,
    onConfirmed: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (request == null) return
    // Neuer Token = frischer Zustand (Knopf links, Ausblend-Timer neu).
    key(request.token) {
        SlideTrack(request, onConfirmed, onDismiss, modifier)
    }
}

@Composable
private fun SlideTrack(
    request: SlideConfirmRequest,
    onConfirmed: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // Position des Knopfs in Pixeln ab dem linken Anschlag.
    val offset = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    // Zählt beendete Gesten, damit auch ein kurzer Tap den Timer neu startet.
    var armCount by remember { mutableIntStateOf(0) }
    var confirmed by remember { mutableStateOf(false) }
    val currentOnConfirmed by rememberUpdatedState(onConfirmed)
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    val confirm: () -> Unit = {
        if (!confirmed) {
            confirmed = true
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.VIRTUAL_KEY
            )
            currentOnDismiss()
            currentOnConfirmed()
        }
    }

    // Ausblenden nach 6 s ohne Bedienung; während des Ziehens pausiert, danach wieder volle 6 s.
    LaunchedEffect(dragging, armCount) {
        if (!dragging) {
            delay(SlideToConfirmDefaults.AUTO_HIDE_MS)
            currentOnDismiss()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(SlideToConfirmDefaults.Height)
            .focusable()
            .clearAndSetSemantics {
                contentDescription = request.label
                onClick(label = request.actionLabel) {
                    confirm()
                    true
                }
            }
            .background(colorResource(R.color.slider_track), CircleShape)
            .border(1.dp, colorResource(R.color.slider_track_outline), CircleShape),
    ) {
        val density = LocalDensity.current
        val inset = with(density) { SlideToConfirmDefaults.TrackInset.toPx() }
        val width = constraints.maxWidth.toFloat()
        val thumbPx = (constraints.maxHeight - 2 * inset).coerceAtLeast(0f)
        val thumbDp = with(density) { thumbPx.toDp() }
        val maxOffset = (width - 2 * inset - thumbPx).coerceAtLeast(0f)

        // Immer von links nach rechts, auch bei RTL (absolute Positionen).
        Box(
            contentAlignment = AbsoluteAlignment.TopLeft,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(maxOffset, thumbPx, inset) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startOffset = offset.value
                        if (!isOnThumb(down.position.x, startOffset, thumbPx, inset)) return@awaitEachGesture
                        down.consume()
                        dragging = true
                        // Bricht ein laufendes Zurückschnappen ab.
                        scope.launch { offset.snapTo(startOffset) }
                        var current = startOffset
                        // Ohne Touch-Slop: der Knopf folgt dem Finger sofort.
                        val completed = horizontalDrag(down.id) { change ->
                            current = (startOffset + change.position.x - down.position.x).coerceIn(0f, maxOffset)
                            val target = current
                            scope.launch { offset.snapTo(target) }
                            change.consume()
                        }
                        dragging = false
                        if (completed && slideProgress(current, maxOffset) >= SlideToConfirmDefaults.CONFIRM_THRESHOLD) {
                            confirm()
                        } else {
                            // Nicht weit genug geschoben: der Knopf schnappt zurück.
                            armCount++
                            scope.launch {
                                offset.animateTo(
                                    0f,
                                    tween(SlideToConfirmDefaults.SNAP_BACK_MS, easing = AccelerateDecelerateEasing),
                                )
                            }
                        }
                    }
                },
        ) {
            // Text mittig im freien Teil rechts vom Knopf; verblasst beim Schieben. offset wird
            // nur in Layout/Zeichnen gelesen, damit Ziehen nicht jedes Bild neu komponiert.
            Text(
                text = request.label,
                style = onVideoTextStyle(16.sp),
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = (1f - 2 * slideProgress(offset.value, maxOffset)).coerceIn(0f, 1f)
                    }
                    .layout { measurable, constraints ->
                        val textStart = inset + offset.value + thumbPx
                        val textEnd = width - inset
                        val available = (textEnd - textStart - 2 * inset).toInt()
                        if (available <= 0) return@layout layout(0, 0) {}
                        val placeable = measurable.measure(Constraints(maxWidth = available))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.place(
                                x = ((textStart + textEnd) / 2 - placeable.width / 2f).roundToInt(),
                                y = (constraints.maxHeight - placeable.height) / 2,
                            )
                        }
                    },
            )
            Box(
                modifier = Modifier
                    .absoluteOffset { IntOffset((inset + offset.value).roundToInt(), inset.roundToInt()) }
                    .size(thumbDp)
                    .clip(CircleShape)
                    .background(DoorBrush),
            ) {
                // Gleiches Verhältnis wie bei den runden Buttons (17dp Rand bei 88dp).
                Image(
                    painter = painterResource(R.drawable.ic_door),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(thumbDp * (CallRoundButtonDefaults.IconPadding / CallRoundButtonDefaults.ButtonSize)),
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF3A3A3A, widthDp = 411)
@Composable
private fun SlideToConfirmPreview() {
    DoorbellTheme {
        SlideToConfirm(
            request = SlideConfirmRequest(
                label = stringResource(R.string.slide_to_open),
                actionLabel = stringResource(R.string.btn_door_open),
                token = 1,
            ),
            onConfirmed = {},
            onDismiss = {},
            modifier = Modifier.padding(SlideToConfirmDefaults.OuterPadding),
        )
    }
}

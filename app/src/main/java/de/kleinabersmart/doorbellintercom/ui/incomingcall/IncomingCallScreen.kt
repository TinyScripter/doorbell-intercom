package de.kleinabersmart.doorbellintercom.ui.incomingcall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.kleinabersmart.doorbellintercom.R
import de.kleinabersmart.doorbellintercom.ui.components.CallRoundButton
import de.kleinabersmart.doorbellintercom.ui.components.SlideConfirmRequest
import de.kleinabersmart.doorbellintercom.ui.components.SlideToConfirm
import de.kleinabersmart.doorbellintercom.ui.components.SlideToConfirmDefaults
import de.kleinabersmart.doorbellintercom.ui.components.TalkButton
import de.kleinabersmart.doorbellintercom.ui.components.TalkStatusText
import de.kleinabersmart.doorbellintercom.ui.theme.AcceptBrush
import de.kleinabersmart.doorbellintercom.ui.theme.DoorBrush
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme
import de.kleinabersmart.doorbellintercom.ui.theme.HangUpBrush
import de.kleinabersmart.doorbellintercom.ui.theme.RippleOnDark
import de.kleinabersmart.doorbellintercom.ui.theme.RippleOnLight
import de.kleinabersmart.doorbellintercom.ui.theme.onVideoTextStyle

/** Test-Tags des Klingelbildschirms (wie die früheren View-IDs). */
object IncomingCallTestTags {
    const val STATUS_TEXT = "status_text"
    const val TALK_STATUS_TEXT = "talk_status_text"
    const val DOOR_SLIDER = "door_slider"
    const val BTN_DECLINE = "btn_decline"
    const val BTN_ACCEPT = "btn_accept"
    const val BTN_DOOR_OPEN_AND_HANG_UP = "btn_door_open_and_hang_up"
    const val BTN_TALK = "btn_talk"
    const val BTN_DOOR_OPEN = "btn_door_open"
    const val BTN_HANG_UP = "btn_hang_up"
}

/**
 * Vollbild-Anrufansicht: Live-Bild ([video], unterste Ebene), oben der Status, unten
 * Mikro-Status, Tür-Slider und je nach [IncomingCallUiState.phase] eine der beiden
 * Button-Reihen. Zustandslos; die Activity hält den Zustand und entscheidet über die Aktionen.
 *
 * Die Tür-Buttons blenden nur den Slider ein ([onDoorOpenClick], [onDoorOpenAndHangUpClick]);
 * ausgelöst wird erst über [onDoorConfirmed] nach dem Schieben.
 */
@Composable
fun IncomingCallScreen(
    state: IncomingCallUiState,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onDoorOpenAndHangUpClick: () -> Unit,
    onTalk: () -> Unit,
    onDoorOpenClick: () -> Unit,
    onHangUp: () -> Unit,
    onDoorConfirmed: (DoorAction) -> Unit,
    onDoorDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    video: @Composable () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colorResource(R.color.video_background)),
    ) {
        video()

        // Status- und Kameraleiste können oben ins Bild ragen (Notch), daher deren Abstand dazu.
        Text(
            text = state.statusText,
            style = onVideoTextStyle(20.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(
                    WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top)
                )
                .padding(top = 32.dp)
                .padding(horizontal = 16.dp)
                .testTag(IncomingCallTestTags.STATUS_TEXT),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom)),
        ) {
            TalkStatusText(state.talkStatusText, Modifier.testTag(IncomingCallTestTags.TALK_STATUS_TEXT))
            SlideToConfirm(
                request = state.doorConfirm,
                // doorAction bleibt beim Ausblenden stehen, daher auch nach onDismiss gültig.
                onConfirmed = { onDoorConfirmed(state.doorAction) },
                onDismiss = onDoorDismiss,
                modifier = Modifier
                    .padding(SlideToConfirmDefaults.OuterPadding)
                    .testTag(IncomingCallTestTags.DOOR_SLIDER),
            )
            // Drei Spalten à 104dp passen ab 336dp Breite.
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 24.dp),
            ) {
                when (state.phase) {
                    IncomingCallPhase.Ringing -> RingingButtons(onDecline, onAccept, onDoorOpenAndHangUpClick)
                    IncomingCallPhase.Active -> CallButtons(state.talking, onTalk, onDoorOpenClick, onHangUp)
                }
            }
        }
    }
}

@Composable
private fun RingingButtons(onDecline: () -> Unit, onAccept: () -> Unit, onDoorOpenAndHangUpClick: () -> Unit) {
    CallRoundButton(
        icon = painterResource(R.drawable.ic_call_end),
        contentDescription = stringResource(R.string.btn_decline),
        label = stringResource(R.string.btn_decline),
        onClick = onDecline,
        background = HangUpBrush,
        rippleColor = RippleOnDark,
        testTag = IncomingCallTestTags.BTN_DECLINE,
    )
    CallRoundButton(
        icon = painterResource(R.drawable.ic_call),
        contentDescription = stringResource(R.string.btn_accept),
        label = stringResource(R.string.btn_accept),
        onClick = onAccept,
        background = AcceptBrush,
        rippleColor = RippleOnDark,
        testTag = IncomingCallTestTags.BTN_ACCEPT,
    )
    // Das Symbol bringt seine runde Fläche selbst mit: ohne Hintergrund und Innenabstand.
    CallRoundButton(
        icon = painterResource(R.drawable.ic_door_hangup),
        contentDescription = stringResource(R.string.btn_door_open_and_hang_up),
        label = stringResource(R.string.label_door_open_and_hang_up),
        onClick = onDoorOpenAndHangUpClick,
        background = null,
        rippleColor = RippleOnDark,
        iconPadding = 0.dp,
        contentScale = ContentScale.FillBounds,
        testTag = IncomingCallTestTags.BTN_DOOR_OPEN_AND_HANG_UP,
    )
}

@Composable
private fun CallButtons(talking: Boolean, onTalk: () -> Unit, onDoorOpenClick: () -> Unit, onHangUp: () -> Unit) {
    TalkButton(talking = talking, onClick = onTalk, testTag = IncomingCallTestTags.BTN_TALK)
    CallRoundButton(
        icon = painterResource(R.drawable.ic_door),
        contentDescription = stringResource(R.string.btn_door_open),
        label = stringResource(R.string.btn_door_open),
        onClick = onDoorOpenClick,
        background = DoorBrush,
        rippleColor = RippleOnLight,
        testTag = IncomingCallTestTags.BTN_DOOR_OPEN,
    )
    CallRoundButton(
        icon = painterResource(R.drawable.ic_call_end),
        contentDescription = stringResource(R.string.btn_hang_up),
        label = stringResource(R.string.btn_hang_up),
        onClick = onHangUp,
        background = HangUpBrush,
        rippleColor = RippleOnDark,
        testTag = IncomingCallTestTags.BTN_HANG_UP,
    )
}

@Composable
private fun IncomingCallPreview(state: IncomingCallUiState) {
    DoorbellTheme {
        IncomingCallScreen(
            state = state,
            onAccept = {},
            onDecline = {},
            onDoorOpenAndHangUpClick = {},
            onTalk = {},
            onDoorOpenClick = {},
            onHangUp = {},
            onDoorConfirmed = {},
            onDoorDismiss = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923)
@Composable
private fun IncomingCallRingingPreview() {
    IncomingCallPreview(
        IncomingCallUiState(
            phase = IncomingCallPhase.Ringing,
            statusText = stringResource(R.string.incoming_call_title),
        )
    )
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923)
@Composable
private fun IncomingCallRingingSliderPreview() {
    IncomingCallPreview(
        IncomingCallUiState(
            phase = IncomingCallPhase.Ringing,
            statusText = stringResource(R.string.incoming_call_title),
            doorConfirm = SlideConfirmRequest(
                label = stringResource(R.string.slide_to_open_and_hang_up),
                actionLabel = stringResource(R.string.btn_door_open_and_hang_up),
                token = 1,
            ),
            doorAction = DoorAction.DoorOpenAndHangUp,
        )
    )
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923)
@Composable
private fun IncomingCallActiveTalkingPreview() {
    IncomingCallPreview(
        IncomingCallUiState(
            phase = IncomingCallPhase.Active,
            statusText = stringResource(R.string.status_connected),
            talkStatusText = stringResource(R.string.status_talk_active),
            talking = true,
        )
    )
}

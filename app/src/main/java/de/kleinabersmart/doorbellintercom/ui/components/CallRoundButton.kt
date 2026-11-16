package de.kleinabersmart.doorbellintercom.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.kleinabersmart.doorbellintercom.R
import de.kleinabersmart.doorbellintercom.ui.theme.AcceptBrush
import de.kleinabersmart.doorbellintercom.ui.theme.DoorBrush
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme
import de.kleinabersmart.doorbellintercom.ui.theme.HangUpBrush
import de.kleinabersmart.doorbellintercom.ui.theme.MicOff
import de.kleinabersmart.doorbellintercom.ui.theme.MicOffBorder
import de.kleinabersmart.doorbellintercom.ui.theme.MicOn
import de.kleinabersmart.doorbellintercom.ui.theme.RippleOnDark
import de.kleinabersmart.doorbellintercom.ui.theme.RippleOnLight
import de.kleinabersmart.doorbellintercom.ui.theme.overlayTextStyle

/** Maße der runden Buttons über dem Video (vorher Widget.DoorbellIntercom.IconButton/ButtonColumn). */
object CallRoundButtonDefaults {
    val ButtonSize = 88.dp
    val IconPadding = 17.dp
    val Elevation = 6.dp

    /** Breiter als der Button, damit längere Beschriftungen ("Öffnen & Ende") nicht umbrechen. */
    val ColumnWidth = 104.dp
    val LabelSpacing = 6.dp
}

/**
 * Runder Button mit Beschriftung darunter (Annehmen, Auflegen, Tür öffnen, …). Nur der Kreis
 * ist klickbar und trägt [contentDescription]; die Beschriftung ist für TalkBack ausgeblendet,
 * da sie sonst doppelt vorgelesen würde. [testTag] sitzt auf dem klickbaren Kreis.
 *
 * Ohne [background] (Bild bringt seine eigene Fläche mit, z. B. ic_door_hangup) gibt es wie
 * beim View ohne Hintergrund auch keinen Schatten.
 */
@Composable
fun CallRoundButton(
    icon: Painter,
    contentDescription: String,
    label: String,
    onClick: () -> Unit,
    background: Brush?,
    rippleColor: Color,
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    iconPadding: Dp = CallRoundButtonDefaults.IconPadding,
    contentScale: ContentScale = ContentScale.Fit,
    testTag: String? = null,
) {
    Column(
        modifier = modifier.width(CallRoundButtonDefaults.ColumnWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(CallRoundButtonDefaults.ButtonSize)
                .then(if (background != null) Modifier.shadow(CallRoundButtonDefaults.Elevation, CircleShape) else Modifier)
                .clip(CircleShape)
                .then(if (background != null) Modifier.background(background) else Modifier)
                .then(if (border != null) Modifier.border(border, CircleShape) else Modifier)
                .clickable(
                    interactionSource = null,
                    indication = ripple(color = rippleColor),
                    role = Role.Button,
                    onClick = onClick,
                )
                .semantics { this.contentDescription = contentDescription }
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        ) {
            Image(
                painter = icon,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(iconPadding),
            )
        }
        ButtonLabel(label, Modifier.padding(top = CallRoundButtonDefaults.LabelSpacing))
    }
}

/** Beschriftung unter einem runden Button (vorher Widget.DoorbellIntercom.ButtonLabel). */
@Composable
fun ButtonLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = overlayTextStyle(14.sp),
        textAlign = TextAlign.Center,
        maxLines = 2,
        modifier = modifier.clearAndSetSemantics {},
    )
}

/**
 * Sprech-Button, zeigt den Mikro-Zustand: heller Button = an, dunkler mit durchgestrichenem
 * Mikrofon = aus.
 */
@Composable
fun TalkButton(
    talking: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    CallRoundButton(
        icon = painterResource(if (talking) R.drawable.ic_mic else R.drawable.ic_mic_off),
        contentDescription = stringResource(if (talking) R.string.btn_talk_stop else R.string.btn_talk_start),
        label = stringResource(R.string.label_talk),
        onClick = onClick,
        background = SolidColor(if (talking) MicOn else MicOff),
        rippleColor = if (talking) RippleOnLight else RippleOnDark,
        border = if (talking) null else BorderStroke(1.dp, MicOffBorder),
        modifier = modifier,
        testTag = testTag,
    )
}

/**
 * Mikro-Status mittig über den Buttons bzw. dem Tür-Slider; der Schatten hält ihn auch über
 * hellem Videobild lesbar. Bleibt leer im Layout (eine Zeile hoch), wie der TextView.
 */
@Composable
fun TalkStatusText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = overlayTextStyle(16.sp),
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF3A3A3A)
@Composable
private fun CallRoundButtonsPreview() {
    DoorbellTheme {
        Row {
            CallRoundButton(
                icon = painterResource(R.drawable.ic_call_end),
                contentDescription = stringResource(R.string.btn_decline),
                label = stringResource(R.string.btn_decline),
                onClick = {},
                background = HangUpBrush,
                rippleColor = RippleOnDark,
            )
            CallRoundButton(
                icon = painterResource(R.drawable.ic_call),
                contentDescription = stringResource(R.string.btn_accept),
                label = stringResource(R.string.btn_accept),
                onClick = {},
                background = AcceptBrush,
                rippleColor = RippleOnDark,
            )
            CallRoundButton(
                icon = painterResource(R.drawable.ic_door_hangup),
                contentDescription = stringResource(R.string.btn_door_open_and_hang_up),
                label = stringResource(R.string.label_door_open_and_hang_up),
                onClick = {},
                background = null,
                rippleColor = RippleOnDark,
                iconPadding = 0.dp,
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF3A3A3A)
@Composable
private fun TalkAndDoorButtonsPreview() {
    DoorbellTheme {
        Column {
            TalkStatusText(stringResource(R.string.status_talk_active))
            Row {
                TalkButton(talking = false, onClick = {})
                TalkButton(talking = true, onClick = {})
                CallRoundButton(
                    icon = painterResource(R.drawable.ic_door),
                    contentDescription = stringResource(R.string.btn_door_open),
                    label = stringResource(R.string.btn_door_open),
                    onClick = {},
                    background = DoorBrush,
                    rippleColor = RippleOnLight,
                )
            }
        }
    }
}

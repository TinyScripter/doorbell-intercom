package de.kleinabersmart.doorbellintercom.ui.main

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import de.kleinabersmart.doorbellintercom.ui.theme.DoorBrush
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme
import de.kleinabersmart.doorbellintercom.ui.theme.RippleOnLight
import de.kleinabersmart.doorbellintercom.ui.theme.onVideoTextStyle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Test-Tags des Startbildschirms (wie die früheren View-IDs). */
object MainTestTags {
    const val TOOLBAR = "main_toolbar"
    const val ACTION_SETTINGS = "action_settings"
    const val STATUS_TEXT = "status_text"
    const val TALK_STATUS_TEXT = "talk_status_text"
    const val DOOR_SLIDER = "door_slider"
    const val BTN_TALK = "btn_talk"
    const val BTN_DOOR_OPEN = "btn_door_open"
    const val SNACKBAR_HOST = "system_check_snackbar"
}

/**
 * Nicht dauerhaft: der Hinweis soll das Live-Bild nicht verdecken, kommt aber bei jedem Öffnen
 * wieder. Compose kennt nur 4 s bzw. 10 s, daher Indefinite und selbst schließen.
 */
internal const val SYSTEM_CHECK_HINT_MS = 8000L

/**
 * Startbildschirm: Live-Bild ([video], unterste Ebene), oben eine abgedunkelte Leiste mit
 * Titel und Einstellungen, darunter der Status; unten Mikro-Status, Tür-Slider sowie
 * Sprechen und Tür öffnen. Zustandslos; die Activity hält den Zustand.
 *
 * "Tür öffnen" blendet nur den Slider ein ([onDoorOpenClick]); geöffnet wird erst über
 * [onDoorConfirmed] nach dem Schieben. Die Aktion des Einrichtungs-Hinweises führt wie das
 * Symbol oben rechts in die Einstellungen ([onOpenSettings]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainUiState,
    onToggleTalk: () -> Unit,
    onDoorOpenClick: () -> Unit,
    onDoorConfirmed: () -> Unit,
    onDoorDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    video: @Composable () -> Unit = {},
) {
    SystemCheckHint(state, snackbarHostState, onOpenSettings)
    // Höhe der Button-Leiste: der Hinweis liegt direkt darüber (vorher setAnchorView(bottomBar)).
    var bottomBarHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val onVideo = colorResource(R.color.on_video)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colorResource(R.color.video_background)),
    ) {
        video()

        Column(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            // Das Statusleisten-Inset gehört in die abgedunkelte Fläche, nicht in die feste
            // Höhe der Leiste – sonst würden Titel und Symbol abgeschnitten.
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    // Drei Punkte als eigenes Symbol, das direkt die Einstellungen öffnet (kein Überlaufmenü).
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag(MainTestTags.ACTION_SETTINGS)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings_dots),
                            contentDescription = stringResource(R.string.menu_settings),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colorResource(R.color.video_scrim),
                    titleContentColor = onVideo,
                    actionIconContentColor = onVideo,
                ),
                windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top),
                modifier = Modifier.testTag(MainTestTags.TOOLBAR),
            )
            Text(
                text = state.statusText,
                style = onVideoTextStyle(16.sp),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .padding(horizontal = 16.dp)
                    .testTag(MainTestTags.STATUS_TEXT),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom)),
        ) {
            TalkStatusText(state.talkStatusText, Modifier.testTag(MainTestTags.TALK_STATUS_TEXT))
            SlideToConfirm(
                request = state.doorConfirm,
                onConfirmed = onDoorConfirmed,
                onDismiss = onDoorDismiss,
                modifier = Modifier
                    .padding(SlideToConfirmDefaults.OuterPadding)
                    .testTag(MainTestTags.DOOR_SLIDER),
            )
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { bottomBarHeight = it.height }
                    .padding(24.dp),
            ) {
                TalkButton(
                    talking = state.talking,
                    onClick = onToggleTalk,
                    modifier = Modifier.padding(end = 16.dp),
                    testTag = MainTestTags.BTN_TALK,
                )
                CallRoundButton(
                    icon = painterResource(R.drawable.ic_door),
                    contentDescription = stringResource(R.string.btn_door_open),
                    label = stringResource(R.string.btn_door_open),
                    onClick = onDoorOpenClick,
                    background = DoorBrush,
                    rippleColor = RippleOnLight,
                    testTag = MainTestTags.BTN_DOOR_OPEN,
                )
            }
        }

        // Über den übrigen Ebenen, damit der Hinweis Slider und Mikro-Status überdeckt statt verschiebt.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))
                .padding(bottom = with(density) { bottomBarHeight.toDp() })
                .testTag(MainTestTags.SNACKBAR_HOST),
        )
    }
}

/**
 * Fehlt eine Voraussetzung fürs Klingeln, ein Hinweis mit Weg in die Einstellungen (siehe
 * SystemCheck). Ein schon sichtbarer Hinweis bleibt stehen; ohne Befund wird er geschlossen.
 */
@Composable
private fun SystemCheckHint(state: MainUiState, hostState: SnackbarHostState, onOpenSettings: () -> Unit) {
    val message = stringResource(R.string.check_snackbar)
    val actionLabel = stringResource(R.string.btn_check)
    val currentOnOpenSettings by rememberUpdatedState(onOpenSettings)
    // Eigener Scope: ein Neustart des Effekts (nächstes onResume) darf den laufenden Hinweis nicht abbrechen.
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.showSystemCheckHint, state.systemCheckHintRequest) {
        if (!state.showSystemCheckHint) {
            hostState.currentSnackbarData?.dismiss()
            return@LaunchedEffect
        }
        if (hostState.currentSnackbarData != null) return@LaunchedEffect
        scope.launch {
            val result = coroutineScope {
                val autoDismiss = launch {
                    delay(SYSTEM_CHECK_HINT_MS)
                    hostState.currentSnackbarData?.dismiss()
                }
                hostState.showSnackbar(message, actionLabel = actionLabel, duration = SnackbarDuration.Indefinite)
                    .also { autoDismiss.cancel() }
            }
            if (result == SnackbarResult.ActionPerformed) currentOnOpenSettings()
        }
    }
}

@Composable
private fun MainPreview(state: MainUiState) {
    DoorbellTheme {
        MainScreen(
            state = state,
            onToggleTalk = {},
            onDoorOpenClick = {},
            onDoorConfirmed = {},
            onDoorDismiss = {},
            onOpenSettings = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923)
@Composable
private fun MainIdlePreview() {
    MainPreview(MainUiState(statusText = stringResource(R.string.status_connected)))
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923)
@Composable
private fun MainTalkingSliderPreview() {
    MainPreview(
        MainUiState(
            statusText = stringResource(R.string.status_connected),
            talkStatusText = stringResource(R.string.status_talk_active),
            talking = true,
            doorConfirm = SlideConfirmRequest(
                label = stringResource(R.string.slide_to_open),
                actionLabel = stringResource(R.string.btn_door_open),
                token = 1,
            ),
        )
    )
}

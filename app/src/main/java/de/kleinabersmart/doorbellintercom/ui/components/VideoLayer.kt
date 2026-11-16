package de.kleinabersmart.doorbellintercom.ui.components

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import de.kleinabersmart.doorbellintercom.R
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme

/**
 * Bläht den [PlayerView] aus view_live_player.xml auf. Die Activity erzeugt ihn selbst in
 * onCreate, damit LiveViewController und PinchZoomController wie bisher sofort eine echte
 * Instanz bekommen – die Komposition (und damit eine AndroidView-Factory) läuft erst später.
 */
fun inflateLivePlayerView(context: Context): PlayerView =
    // Mit Eltern-Container, aber ohne Anhängen: so gelten die Layout-Parameter aus dem XML.
    LayoutInflater.from(context).inflate(R.layout.view_live_player, FrameLayout(context), false) as PlayerView

/**
 * Live-Bild als unterste Ebene der Video-Bildschirme. Der [PlayerView] bleibt ein View
 * (RTSP-Wiedergabe und Zoom laufen über ihn), Compose zeigt ihn nur an.
 */
@Composable
fun VideoLayer(playerView: PlayerView, modifier: Modifier = Modifier) {
    AndroidView(
        factory = {
            // Hängt er nach einer neuen Komposition noch am alten Container, erst lösen –
            // ein View kann nur einen Elternteil haben.
            (playerView.parent as? ViewGroup)?.removeView(playerView)
            playerView
        },
        modifier = modifier
            .fillMaxSize()
            .background(colorResource(R.color.video_background)),
    )
}

@Preview(showBackground = true, widthDp = 411, heightDp = 300)
@Composable
private fun VideoLayerPreview() {
    DoorbellTheme {
        val context = LocalContext.current
        val playerView = remember { inflateLivePlayerView(context) }
        VideoLayer(playerView)
    }
}

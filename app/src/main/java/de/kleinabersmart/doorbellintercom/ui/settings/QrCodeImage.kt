package de.kleinabersmart.doorbellintercom.ui.settings

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import de.kleinabersmart.doorbellintercom.R
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme

/**
 * QR-Code über die volle verfügbare Breite. Die Grafik hat nur ein Pixel pro Modul und wird stark
 * vergrößert: [FilterQuality.None] ist Pflicht, sonst werden die Kanten weichgezeichnet und der
 * Code lässt sich schlechter scannen (die ImageView skalierte ebenfalls ohne Filter).
 */
@Composable
fun QrCodeImage(
    bitmap: ImageBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        contentScale = ContentScale.FillWidth,
        filterQuality = FilterQuality.None,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(bitmap.width.toFloat() / bitmap.height),
    )
}

/**
 * Schwarz auf weiß mit Ruhezone, unabhängig vom App-Theme – sonst scannt er schlecht. Ein Pixel
 * pro Modul; vergrößert wird erst in [QrCodeImage].
 */
internal fun createQrBitmap(content: String): Bitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.MARGIN to 2,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            pixels[y * matrix.width + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun QrCodeImagePreview() {
    val bitmap = remember { createQrBitmap("""{"v":1,"cameraHost":"192.168.178.40","cameraBcPort":9000}""").asImageBitmap() }
    DoorbellTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            QrCodeImage(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.title_qr_dialog),
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

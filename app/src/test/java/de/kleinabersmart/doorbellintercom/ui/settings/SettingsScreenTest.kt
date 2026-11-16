package de.kleinabersmart.doorbellintercom.ui.settings

import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import de.kleinabersmart.doorbellintercom.R
import de.kleinabersmart.doorbellintercom.ui.settings.SettingsTestTags as Tags
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Interaction tests of the stateless [SettingsScreen] (replaces settings_main.xml +
 * item_settings_entry.xml): entry clicks map to their [SettingsEntry], system-check hints, the
 * backup buttons, the status line and both dialogs (show QR code, confirm QR import).
 *
 * Every callback appends to [events], so a test also proves that no other callback fired. The
 * dialog callbacks clear the state like SettingsActivity does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = SETTINGS_TEST_QUALIFIERS)
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var state by mutableStateOf(SettingsUiState())
    private val events = mutableListOf<Any>()

    @Before
    fun setUp() {
        composeTestRule.setContent {
            DoorbellTheme {
                SettingsScreen(
                    state = state,
                    onNavigateUp = { events += "up" },
                    onEntryClick = { events += it },
                    onIssueClick = { events += it },
                    onExportClick = { events += "export" },
                    onImportClick = { events += "import" },
                    onShowQrClick = { events += "showQr" },
                    onScanQrClick = { events += "scanQr" },
                    onQrDialogDismiss = {
                        events += "qrDismiss"
                        state = state.copy(qrCode = null)
                    },
                    onQrImportConfirm = {
                        events += "qrImportConfirm"
                        state = state.copy(pendingQrImport = null)
                    },
                    onQrImportDismiss = {
                        events += "qrImportDismiss"
                        state = state.copy(pendingQrImport = null)
                    },
                )
            }
        }
    }

    private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

    private fun setState(transform: (SettingsUiState) -> SettingsUiState) {
        state = transform(state)
        composeTestRule.waitForIdle()
    }

    @Test
    fun entryClicks_reportTheirEntryInOrder() {
        for (entry in SettingsEntry.entries) {
            node(entry.testTag).performScrollTo().performClick()
        }

        assertEquals(SettingsEntry.entries.toList(), events)
    }

    @Test
    fun noIssues_noSystemCheckSection() {
        node(Tags.CHECK_TITLE).assertDoesNotExist()
        node(Tags.checkIssue(0)).assertDoesNotExist()
    }

    @Test
    fun issueClick_reportsThatIssue() {
        setState { it.copy(issues = ISSUES) }

        node(Tags.CHECK_TITLE).assertIsDisplayed().assertTextEquals(str(R.string.check_title))
        node(Tags.checkIssue(0)).assertTextEquals(str(ISSUES[0].title), str(ISSUES[0].summary))
        node(Tags.checkIssue(1)).assertTextEquals(str(ISSUES[1].title), str(ISSUES[1].summary)).performClick()
        node(Tags.checkIssue(0)).performClick()

        assertEquals(listOf(ISSUES[1], ISSUES[0]), events)

        // Fixed on the next resume: the section disappears again.
        setState { it.copy(issues = emptyList()) }
        node(Tags.CHECK_TITLE).assertDoesNotExist()
    }

    @Test
    fun backupButtons_reportTheirClick() {
        node(Tags.BTN_EXPORT).performScrollTo().performClick()
        node(Tags.BTN_IMPORT).performScrollTo().performClick()
        node(Tags.BTN_SHOW_QR).performScrollTo().performClick()
        node(Tags.BTN_SCAN_QR).performScrollTo().performClick()

        assertEquals(listOf("export", "import", "showQr", "scanQr"), events)
    }

    @Test
    fun statusMessage_isShownInStatusLine() {
        node(Tags.STATUS_TEXT).assertTextEquals("")

        setState { it.copy(statusMessage = R.string.status_settings_imported) }
        node(Tags.STATUS_TEXT).assertTextEquals(str(R.string.status_settings_imported))

        setState { it.copy(statusMessage = R.string.status_qr_failed) }
        node(Tags.STATUS_TEXT).assertTextEquals(str(R.string.status_qr_failed))
    }

    @Test
    fun noDialogsWithoutQrState() {
        node(Tags.QR_DIALOG).assertDoesNotExist()
        node(Tags.QR_IMPORT_DIALOG).assertDoesNotExist()
    }

    @Test
    fun qrDialog_shownForQrCode_closeDismisses() {
        setState { it.copy(qrCode = createQrBitmap("""{"v":1}""").asImageBitmap()) }

        node(Tags.QR_DIALOG).assertIsDisplayed()
        node(Tags.QR_IMAGE).assertIsDisplayed()
        node(Tags.QR_IMPORT_DIALOG).assertDoesNotExist()
        node(Tags.BTN_QR_CLOSE).assertTextEquals(str(R.string.btn_close)).performClick()

        assertEquals(listOf("qrDismiss"), events)
        node(Tags.QR_DIALOG).assertDoesNotExist()
    }

    @Test
    fun qrImportDialog_confirmReportsConfirm() {
        setState { it.copy(pendingQrImport = """{"v":1}""") }

        node(Tags.QR_IMPORT_DIALOG).assertIsDisplayed()
        node(Tags.QR_DIALOG).assertDoesNotExist()
        node(Tags.BTN_QR_IMPORT_CONFIRM).assertTextEquals(str(R.string.btn_import_confirm)).performClick()

        assertEquals(listOf("qrImportConfirm"), events)
        node(Tags.QR_IMPORT_DIALOG).assertDoesNotExist()
    }

    @Test
    fun qrImportDialog_cancelReportsDismiss() {
        setState { it.copy(pendingQrImport = """{"v":1}""") }

        node(Tags.BTN_QR_IMPORT_CANCEL).assertTextEquals(str(R.string.btn_cancel)).performClick()

        assertEquals(listOf("qrImportDismiss"), events)
        node(Tags.QR_IMPORT_DIALOG).assertDoesNotExist()
    }

    @Test
    fun upButton_navigatesUp() {
        node(Tags.UP).performClick()

        assertEquals(listOf("up"), events)
    }

    /**
     * The bitmap behind the QR dialog: black on white, one pixel per module with a 2-module quiet
     * zone, and it decodes back to the exact payload.
     */
    @Test
    fun createQrBitmap_isPureBlackAndWhiteAndDecodesToPayload() {
        val payload = """{"v":1,"cameraHost":"192.168.178.40","cameraBcPort":9000,"mqttTopic":"doorbell/ring"}"""
        val bitmap = createQrBitmap(payload)

        assertEquals(bitmap.width, bitmap.height)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue(pixels.all { it == Color.BLACK || it == Color.WHITE })
        // Quiet zone: the two outer rows and columns are white.
        for (i in 0 until bitmap.width) {
            for (edge in listOf(0, 1, bitmap.width - 2, bitmap.width - 1)) {
                assertEquals(Color.WHITE, bitmap.getPixel(i, edge))
                assertEquals(Color.WHITE, bitmap.getPixel(edge, i))
            }
        }
        // QR version n has 17 + 4n modules, plus 2 x 2 quiet zone modules.
        assertEquals(0, (bitmap.width - 4 - 17) % 4)

        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val decoded = QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(source)),
            mapOf(DecodeHintType.PURE_BARCODE to true),
        )
        assertEquals(payload, decoded.text)
    }

    private companion object {
        val ISSUES = listOf(
            SystemCheckIssueUi(R.string.check_notifications_off, R.string.check_notifications_off_summary),
            SystemCheckIssueUi(R.string.check_battery_optimized, R.string.check_battery_optimized_summary),
        )
    }
}

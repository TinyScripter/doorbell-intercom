package de.kleinabersmart.doorbellintercom.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import de.kleinabersmart.doorbellintercom.R
import de.kleinabersmart.doorbellintercom.ui.settings.SettingsTestTags as Tags
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [AboutScreen] (replaces settings_about.xml) and [linkifyWebUrls], which replaced autoLink="web":
 * the web addresses of the texts are exposed as [LinkAnnotation.Url] (styled like URLSpan) and a
 * tap opens them through the UriHandler. The flavor-specific about_libraries_variant contains no
 * address in either flavor, so the set of links is the same for play and free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = SETTINGS_TEST_QUALIFIERS)
class AboutScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val openedUris = mutableListOf<String>()
    private val upClicks = mutableListOf<Unit>()

    private fun showAbout() {
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                openedUris += uri
            }
        }
        composeTestRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                DoorbellTheme {
                    AboutScreen(versionName = "1.2.3-test-debug", onNavigateUp = { upClicks += Unit })
                }
            }
        }
    }

    // ---- linkifyWebUrls (pure) -------------------------------------------------------------------

    @Test
    fun linkifyWebUrls_marksEachAddressWithItsRangeAndStyle() {
        val text = "Source: https://example.org/a-b?x=1 and www.example.com/docs, mail me@example.net."
        val styles = TextLinkStyles(SpanStyle(color = Color.Red, textDecoration = TextDecoration.Underline))

        val annotated = linkifyWebUrls(text, styles)

        assertEquals(text, annotated.text)
        val links = urlLinks(annotated)
        assertEquals(
            listOf(
                "https://example.org/a-b?x=1" to "https://example.org/a-b?x=1",
                // Like Linkify/URLSpan before: addresses without scheme open as http://.
                "http://www.example.com/docs" to "www.example.com/docs",
            ),
            links.map { (url, range) -> url.url to text.substring(range.first, range.second) },
        )
        links.forEach { (url, _) -> assertSame(styles, url.styles) }
    }

    @Test
    fun linkifyWebUrls_withoutAddress_hasNoLinks() {
        val annotated = linkifyWebUrls(str(R.string.about_libraries_variant), styles = null)

        assertEquals(str(R.string.about_libraries_variant), annotated.text)
        assertTrue(urlLinks(annotated).isEmpty())
    }

    @Test
    fun linkifyWebUrls_aboutTexts_linkTheirAddresses() {
        val expected = mapOf(
            R.string.about_license to listOf("https://www.gnu.org/licenses/gpl-3.0.html"),
            R.string.about_reolink_aio to listOf("https://github.com/starkillerOG/reolink_aio"),
            R.string.about_feather_icons to listOf("https://github.com/feathericons/feather"),
            // Inside parentheses, followed by "):": neither character belongs to the address.
            R.string.about_libraries to listOf("https://www.apache.org/licenses/LICENSE-2.0"),
        )
        for ((res, urls) in expected) {
            val text = str(res)
            val links = urlLinks(linkifyWebUrls(text, styles = null))
            assertEquals(testApp.resources.getResourceEntryName(res), urls, links.map { it.first.url })
            links.forEach { (url, range) -> assertEquals(url.url, text.substring(range.first, range.second)) }
        }
    }

    // ---- AboutScreen -----------------------------------------------------------------------------

    @Test
    fun version_isShownWithVersionName() {
        showAbout()

        composeTestRule.onNodeWithTag(Tags.ABOUT_VERSION).assertTextEquals(str(R.string.about_version, "1.2.3-test-debug"))
    }

    @Test
    fun screen_exposesAllAddressesAsUnderlinedUrlLinks() {
        showAbout()

        val links = composeTestRule.onAllNodes(hasText(), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .flatMap { urlLinks(it) }
            .map { it.first }

        assertEquals(EXPECTED_URLS.sorted(), links.map { it.url }.sorted())
        for (link in links) {
            assertEquals(link.url, TextDecoration.Underline, link.styles?.style?.textDecoration)
        }
    }

    @Test
    fun tappingLinks_opensThemThroughUriHandler() {
        showAbout()

        val linkNodes = composeTestRule.onAllNodes(hasClickAction() and !hasTestTag(Tags.UP), useUnmergedTree = true)
        val count = linkNodes.fetchSemanticsNodes().size
        assertEquals(EXPECTED_URLS.size, count)
        for (i in 0 until count) {
            linkNodes[i].performSemanticsAction(SemanticsActions.OnClick)
        }

        assertEquals(EXPECTED_URLS.sorted(), openedUris.sorted())
        assertTrue(upClicks.isEmpty())
    }

    @Test
    fun upButton_navigatesUp() {
        showAbout()

        composeTestRule.onNodeWithTag(Tags.UP).performClick()

        assertEquals(1, upClicks.size)
        assertTrue(openedUris.isEmpty())
    }

    private fun hasText() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)

    /** URL links of [text] with their (start, end) range. */
    private fun urlLinks(text: AnnotatedString): List<Pair<LinkAnnotation.Url, Pair<Int, Int>>> =
        text.getLinkAnnotations(0, text.length)
            .map { range -> (range.item as LinkAnnotation.Url) to (range.start to range.end) }
            .sortedBy { it.second.first }

    private companion object {
        val EXPECTED_URLS = listOf(
            "https://www.gnu.org/licenses/gpl-3.0.html",
            "https://github.com/starkillerOG/reolink_aio",
            "https://github.com/feathericons/feather",
            "https://www.apache.org/licenses/LICENSE-2.0",
        )
    }
}

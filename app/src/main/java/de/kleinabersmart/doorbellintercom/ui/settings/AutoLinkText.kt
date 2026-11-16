package de.kleinabersmart.doorbellintercom.ui.settings

import android.content.res.Configuration
import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.kleinabersmart.doorbellintercom.R
import de.kleinabersmart.doorbellintercom.ui.theme.DoorbellTheme

/**
 * Text mit klickbaren Web-Adressen (vorher TextView mit android:autoLink="web"). Die Adressen
 * findet weiterhin Linkify, damit dieselben Stellen verlinkt werden wie bisher (auch ohne
 * "https://"); ein Tipp öffnet sie über den UriHandler im Browser. Darstellung wie URLSpan:
 * unterstrichen in textColorLink (= colorPrimary). Sonst wie [SettingsText] (14sp, sekundäre
 * Textfarbe).
 */
@Composable
fun AutoLinkText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) {
        linkifyWebUrls(text, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))
    }
    Text(
        text = annotated,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = textViewStyle(14.sp),
        modifier = modifier,
    )
}

/** Wandelt die von Linkify gefundenen Web-Adressen in [LinkAnnotation.Url] um. */
internal fun linkifyWebUrls(text: String, styles: TextLinkStyles?): AnnotatedString {
    val spannable = SpannableString(text)
    Linkify.addLinks(spannable, Linkify.WEB_URLS)
    return buildAnnotatedString {
        append(text)
        for (span in spannable.getSpans(0, spannable.length, URLSpan::class.java)) {
            addLink(
                LinkAnnotation.Url(span.url, styles),
                spannable.getSpanStart(span),
                spannable.getSpanEnd(span),
            )
        }
    }
}

@Composable
private fun AutoLinkTextSample() {
    DoorbellTheme {
        SettingsScaffold(title = stringResource(R.string.section_about), onNavigateUp = {}) {
            SettingsCard {
                SettingsSectionTitle(text = stringResource(R.string.app_name))
                SettingsSummary(
                    text = stringResource(R.string.about_version, "1.0.1-play-debug"),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                AutoLinkText(text = stringResource(R.string.about_license))
            }
            SettingsCard {
                SettingsSectionTitle(
                    text = stringResource(R.string.about_third_party_title),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                AutoLinkText(text = stringResource(R.string.about_reolink_aio))
                AutoLinkText(
                    text = stringResource(R.string.about_feather_icons),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923)
@Composable
private fun AutoLinkTextPreview() {
    AutoLinkTextSample()
}

@Preview(showBackground = true, widthDp = 411, heightDp = 923, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AutoLinkTextDarkPreview() {
    AutoLinkTextSample()
}

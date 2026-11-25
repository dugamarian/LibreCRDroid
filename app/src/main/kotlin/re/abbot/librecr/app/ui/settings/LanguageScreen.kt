package re.abbot.librecr.app.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.launch
import re.abbot.librecr.app.GlucoRitm
import re.abbot.librecr.app.R
import re.abbot.librecr.app.ui.common.LocalAppSettings
import re.abbot.librecr.app.ui.common.SectionCard

private val LANGUAGES = listOf(
    "ro" to "Română",
    "en" to "English",
    "fr" to "Français",
)

@Composable
fun LanguageScreen(modifier: Modifier = Modifier) {
    val settings = LocalAppSettings.current
    val scope = rememberCoroutineScope()

    fun apply(tag: String) {
        scope.launch { GlucoRitm.settings.setLanguageTag(tag) }
        AppCompatDelegate.setApplicationLocales(
            if (tag.isBlank()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
        )
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(stringResource(R.string.title_language)) {
            val options = listOf("" to stringResource(R.string.lang_system)) + LANGUAGES
            options.forEach { (tag, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = settings.languageTag == tag, onClick = { apply(tag) })
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = settings.languageTag == tag, onClick = { apply(tag) })
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(stringResource(R.string.lang_desc), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

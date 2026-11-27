package app.droidnative.ui

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.droidnative.R

@Composable
internal fun AppLanguageSettings() {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (Build.VERSION.SDK_INT >= 33) AppLanguagePicker()
        else Text(stringResource(R.string.app_language_older_android))
    }
}

@RequiresApi(33)
@Composable
private fun AppLanguagePicker() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val localeManager = context.getSystemService(LocaleManager::class.java) ?: return
    var expanded by remember { mutableStateOf(false) }
    val choices = listOf(
        "" to R.string.app_language_system,
        "ko" to R.string.app_language_ko,
        "en" to R.string.app_language_en,
    )
    val active = remember(configuration, expanded) {
        localeManager.applicationLocales.takeUnless { it.isEmpty }?.get(0)?.language.orEmpty()
    }
    val selected = choices.firstOrNull { it.first == active }?.second ?: R.string.app_language_system
    OutlinedButton(onClick = { expanded = true }) { Text(stringResource(selected)) }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        choices.forEach { (tag, label) ->
            DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                expanded = false
                if (tag != active) {
                    localeManager.applicationLocales =
                        if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
                }
            })
        }
    }
}

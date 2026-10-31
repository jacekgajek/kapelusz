package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.search_close
import com.jacekgajek.kapelusz.app.generated.search_people
import com.jacekgajek.kapelusz.engine.Limits
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Bigger groups (ITERATION_8): what changes with size appears only past [THRESHOLD], so a small band sees no change.
 */
object Crowd {
    /** More people than this in a list: a search field, the picker instead of chips. */
    const val THRESHOLD = 12
}

/**
 * Searching by name ([com.jacekgajek.kapelusz.text.Names.matches]), with a button clearing it; people by default,
 * else what [placeholder] says (in Polish a person's "imię" is not an event's "nazwa").
 */
@Composable
fun NameSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier, placeholder: StringResource = Res.string.search_people) {
    OutlinedTextField(
        query,
        { onQuery(it.take(Limits.MAX_NAME)) },
        modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(placeholder)) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = if (query.isEmpty()) {
            null
        } else {
            (
                {
                    IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Clear, stringResource(Res.string.search_close)) }
                }
                )
        },
        singleLine = true,
    )
}

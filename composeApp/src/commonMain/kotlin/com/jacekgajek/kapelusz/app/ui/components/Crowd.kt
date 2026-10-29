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
import com.jacekgajek.kapelusz.ops.Limits
import org.jetbrains.compose.resources.stringResource

/**
 * Bigger groups (ITERATION_8): what changes with size appears only past [THRESHOLD], so a small band sees no change.
 */
object Crowd {
    /** More people than this in a list: a search field, the picker instead of chips. */
    const val THRESHOLD = 12

    /** Rows shown of a list longer than [THRESHOLD] until "Show all". */
    const val COLLAPSED_ROWS = 8
}

/** Searching people by name ([com.jacekgajek.kapelusz.domain.Names.matches]), with a button clearing it. */
@Composable
fun NameSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        query,
        { onQuery(it.take(Limits.MAX_NAME)) },
        modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(Res.string.search_people)) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = if (query.isEmpty()) null else ({
            IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Clear, stringResource(Res.string.search_close)) }
        }),
        singleLine = true,
    )
}

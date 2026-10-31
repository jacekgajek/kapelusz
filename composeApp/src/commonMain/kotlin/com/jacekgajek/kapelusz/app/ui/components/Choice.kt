package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

/** One of a [SegmentedChoice]'s options: its [label], and what a screen reader says when the label is a symbol. */
data class Choice<T>(val value: T, val label: String, val description: String? = null)

/** A row of segmented buttons with one selected: the Material row, without each caller repeating its shapes. */
@Composable
fun <T> SegmentedChoice(choices: List<Choice<T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, choice ->
            val described = choice.description?.let { description -> Modifier.semantics { contentDescription = description } } ?: Modifier
            SegmentedButton(
                selected = selected == choice.value,
                onClick = { onSelect(choice.value) },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                icon = {},
                modifier = described,
            ) { Text(choice.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

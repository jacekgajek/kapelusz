package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.unnamed_cost
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.Money
import org.jetbrains.compose.resources.stringResource

/**
 * A label with an amount at its end: a total, a payout, a balance. Bold for the line that sums the others; [onEdit]
 * puts the pencil after the amount; [labelStyle] when the label is not body text.
 */
@Composable
fun AmountRow(
    label: String,
    amount: Money,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    colored: Boolean = false,
    labelStyle: TextStyle? = null,
    onEdit: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val style = labelStyle ?: if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = style)
        MoneyText(amount, bold = bold, colored = colored)
        if (onEdit != null) EditButton(onClick = onEdit)
        trailing()
    }
}

/** What a cost is called in a list: its description, else its category, else "Cost". */
@Composable
fun costTitle(cost: Cost): String = cost.description ?: cost.category?.label() ?: stringResource(Res.string.unnamed_cost)

/**
 * A cost in a list: the category's icon over both lines (I6 §4), its [title] and [subtitle], and at the end
 * the amount with its [status] under it (none when [showAmount] is off, e.g. where the amount is a field).
 */
@Composable
fun CostRow(
    cost: Cost,
    subtitle: String?,
    modifier: Modifier = Modifier,
    title: String = costTitle(cost),
    status: String? = null,
    showAmount: Boolean = true,
    bold: Boolean = false,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CostIcon(cost, Modifier.padding(end = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = if (bold) MaterialTheme.typography.titleSmall else LocalTextStyle.current)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        if (showAmount) {
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(cost.amount, bold = bold)
                if (status != null) Text(status, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

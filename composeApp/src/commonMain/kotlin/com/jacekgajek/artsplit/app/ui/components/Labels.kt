package com.jacekgajek.artsplit.app.ui.components

import androidx.compose.runtime.Composable
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.category_accommodation
import com.jacekgajek.artsplit.app.generated.category_equipment
import com.jacekgajek.artsplit.app.generated.category_food
import com.jacekgajek.artsplit.app.generated.category_other
import com.jacekgajek.artsplit.app.generated.category_travel
import com.jacekgajek.artsplit.app.generated.role_admin
import com.jacekgajek.artsplit.app.generated.role_lead
import com.jacekgajek.artsplit.app.generated.role_member
import com.jacekgajek.artsplit.app.generated.role_owner
import com.jacekgajek.artsplit.app.generated.role_viewer
import com.jacekgajek.artsplit.app.generated.source_contract
import com.jacekgajek.artsplit.app.generated.source_door
import com.jacekgajek.artsplit.app.generated.source_hat
import com.jacekgajek.artsplit.app.generated.source_merch
import com.jacekgajek.artsplit.app.generated.source_other
import com.jacekgajek.artsplit.app.generated.source_own_contribution
import com.jacekgajek.artsplit.app.generated.source_sponsorship
import com.jacekgajek.artsplit.app.generated.source_tickets_online
import com.jacekgajek.artsplit.model.CostCategory
import com.jacekgajek.artsplit.model.IncomeSource
import com.jacekgajek.artsplit.model.Role
import org.jetbrains.compose.resources.stringResource

@Composable
fun IncomeSource.label(): String = stringResource(
    when (this) {
        IncomeSource.TICKETS_ONLINE -> Res.string.source_tickets_online
        IncomeSource.DOOR -> Res.string.source_door
        IncomeSource.HAT -> Res.string.source_hat
        IncomeSource.MERCH -> Res.string.source_merch
        IncomeSource.CONTRACT -> Res.string.source_contract
        IncomeSource.SPONSORSHIP -> Res.string.source_sponsorship
        IncomeSource.OWN_CONTRIBUTION -> Res.string.source_own_contribution
        IncomeSource.OTHER -> Res.string.source_other
    },
)

@Composable
fun CostCategory.label(): String = stringResource(
    when (this) {
        CostCategory.TRAVEL -> Res.string.category_travel
        CostCategory.ACCOMMODATION -> Res.string.category_accommodation
        CostCategory.EQUIPMENT -> Res.string.category_equipment
        CostCategory.FOOD -> Res.string.category_food
        CostCategory.OTHER -> Res.string.category_other
    },
)

@Composable
fun Role.label(): String = stringResource(
    when (this) {
        Role.VIEWER -> Res.string.role_viewer
        Role.MEMBER -> Res.string.role_member
        Role.LEAD -> Res.string.role_lead
        Role.ADMIN -> Res.string.role_admin
        Role.OWNER -> Res.string.role_owner
    },
)

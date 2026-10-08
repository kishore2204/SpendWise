package com.spendwise.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.spendwise.app.data.db.entity.CategoryEntity
import com.spendwise.app.ui.theme.SpendWise
import com.spendwise.app.ui.theme.Spacing

/**
 * Category chips plus "+ New": type any name and it becomes a category (for every account) and is selected.
 * [onCreate] returns null on success, or a message to show.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryPicker(
    categories: List<CategoryEntity>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onCreate: (name: String, onResult: (error: String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SpendWise.colors
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth()) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.screen),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            categories.forEach { c -> Chip(c.name, CategoryIcons.of(c.iconKey), c.id == selectedId) { onSelect(c.id) } }
            Chip("New", Icons.Rounded.Add, adding) { adding = !adding; error = null }
        }
        AnimatedVisibility(adding) {
            Column(Modifier.padding(horizontal = Spacing.screen)) {
                Spacer(Modifier.height(Spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextInput(name, { name = it; error = null }, "New category name", modifier = Modifier.weight(1f), error = error)
                    TextButton(onClick = {
                        onCreate(name) { problem ->
                            if (problem == null) {
                                adding = false
                                name = ""
                            } else {
                                error = problem
                            }
                        }
                    }) { Text("Add", color = colors.primary) }
                }
            }
        }
    }
}

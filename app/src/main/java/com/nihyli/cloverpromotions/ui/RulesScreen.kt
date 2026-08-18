package com.nihyli.cloverpromotions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nihyli.cloverpromotions.data.PromoRule

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(viewModel: MainViewModel) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<PromoRule?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Promotions") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editing = null
                showEditor = true
            }) { Text("+", style = MaterialTheme.typography.headlineMedium) }
        },
    ) { padding ->
        if (rules.isEmpty()) {
            Column(Modifier.padding(padding).padding(24.dp)) {
                Text("No promotions yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Tap + to create one, e.g. \u201c2 x Red Bull for $5.00\u201d. " +
                        "Active promotions apply automatically in Register as items are scanned.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                item {
                    Text(
                        "Keep this app open (or leave its notification) so Register can apply promo prices.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(rules, key = { it.id }) { rule ->
                    ListItem(
                        headlineContent = { Text(rule.name) },
                        supportingContent = {
                            Text(
                                "${rule.requiredQty} x ${rule.itemName} for " +
                                    centsToDollars(rule.bundlePriceCents),
                            )
                        },
                        leadingContent = {
                            Switch(
                                checked = rule.active,
                                onCheckedChange = { viewModel.toggleActive(rule) },
                            )
                        },
                        trailingContent = {
                            Row {
                                TextButton(onClick = {
                                    editing = rule
                                    showEditor = true
                                }) { Text("Edit") }
                                TextButton(onClick = { viewModel.delete(rule) }) { Text("Delete") }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showEditor) {
        RuleEditorDialog(
            viewModel = viewModel,
            existing = editing,
            existingRules = rules,
            onDismiss = { showEditor = false },
            onSave = { rule ->
                viewModel.save(rule)
                showEditor = false
            },
        )
    }
}

@Composable
private fun RuleEditorDialog(
    viewModel: MainViewModel,
    existing: PromoRule?,
    existingRules: List<PromoRule>,
    onDismiss: () -> Unit,
    onSave: (PromoRule) -> Unit,
) {
    var selectedItem by remember {
        mutableStateOf(
            existing?.let { PickerItem(it.itemId, it.itemName, 0L) },
        )
    }
    var qtyText by remember { mutableStateOf(existing?.requiredQty?.toString() ?: "2") }
    var priceText by remember {
        mutableStateOf(
            existing?.let { String.format(java.util.Locale.US, "%.2f", it.bundlePriceCents / 100.0) } ?: "",
        )
    }
    var showItemPicker by remember { mutableStateOf(false) }

    val qty = qtyText.toIntOrNull()
    val priceCents = dollarsToCents(priceText)
    val valid = selectedItem != null && qty != null && qty >= 2 && priceCents != null && priceCents > 0

    val autoName = if (valid) {
        "$qty x ${selectedItem!!.name} for ${centsToDollars(priceCents!!)}"
    } else {
        ""
    }
    val overlap = existingRules.filter {
        it.active &&
            it.itemId == selectedItem?.id &&
            it.id != (existing?.id ?: 0L)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New promotion" else "Edit promotion") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { showItemPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        selectedItem?.name ?: "Choose item\u2026",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { qtyText = it },
                    label = { Text("Quantity required (min 2)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text("Bundle price, e.g. 5.00") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (autoName.isNotEmpty()) {
                    Text(
                        "Will show on receipts as: PROMO: $autoName",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (overlap.isNotEmpty()) {
                    Text(
                        "This item already has ${overlap.joinToString { it.name }}. " +
                            "Register will use only the better deal for the cart quantity — they will not stack.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        PromoRule(
                            id = existing?.id ?: 0,
                            name = autoName,
                            itemId = selectedItem!!.id,
                            itemName = selectedItem!!.name,
                            requiredQty = qty!!,
                            bundlePriceCents = priceCents!!,
                            active = existing?.active ?: true,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (showItemPicker) {
        ItemPickerDialog(
            viewModel = viewModel,
            onDismiss = { showItemPicker = false },
            onSelect = {
                selectedItem = it
                showItemPicker = false
            },
        )
    }
}

@Composable
private fun ItemPickerDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onSelect: (PickerItem) -> Unit,
) {
    var allItems by remember { mutableStateOf<List<PickerItem>?>(null) }
    var query by remember { mutableStateOf("") }
    val inventoryError by viewModel.inventoryError.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        allItems = viewModel.loadInventory()
    }

    val filtered = allItems.orEmpty().filter {
        query.isBlank() || it.name.contains(query, ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose inventory item") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    allItems == null -> Text("Loading inventory\u2026", Modifier.padding(top = 16.dp))
                    inventoryError != null -> Text(inventoryError!!, Modifier.padding(top = 16.dp))
                    filtered.isEmpty() -> Text("No items found", Modifier.padding(top = 16.dp))
                    else -> LazyColumn(Modifier.heightIn(max = 400.dp).padding(top = 8.dp)) {
                        items(filtered, key = { it.id }) { item ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = { onSelect(item) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "${item.name}  (${centsToDollars(item.priceCents)})",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

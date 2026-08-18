package com.nihyli.cloverpromotions.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clover.sdk.v3.scanner.BarcodeResult
import com.nihyli.cloverpromotions.data.PromoItemRef
import com.nihyli.cloverpromotions.data.PromoRule
import com.nihyli.cloverpromotions.data.formatClockMinutes24
import com.nihyli.cloverpromotions.data.parseClockMinutes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(viewModel: MainViewModel) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<PromoRule?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<PromoRule?>(null) }

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
                        "Add every flavor/SKU that should count together. " +
                        "Active promotions apply automatically in Register as items are scanned.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Promotions stay on after you open this app once. They also come back after a reboot. Don't force-stop Promotions.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                item {
                    Text(
                        "Promotions stay on in the background after you open this app once. They also restart after a reboot. Don't force-stop Promotions.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(rules, key = { it.id }) { rule ->
                    ListItem(
                        headlineContent = { Text(rule.displayTitle()) },
                        supportingContent = {
                            Text(
                                listSupportingLine(rule),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
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
                                TextButton(onClick = { confirmDelete = rule }) { Text("Delete") }
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

    confirmDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete promotion?") },
            text = { Text("\u201c${rule.displayTitle()}\u201d will stop applying in Register immediately.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(rule)
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }
}

private fun itemsSummary(items: List<PromoItemRef>): String = when {
    items.isEmpty() -> "No items"
    items.size == 1 -> items.first().name
    else -> items.joinToString { it.name }
}

private fun listSupportingLine(rule: PromoRule): String {
    val parts = mutableListOf<String>()
    rule.scheduleSummary()?.let { parts += it }
    parts += itemsSummary(rule.items)
    return parts.joinToString(" \u00b7 ")
}

@Composable
private fun RuleEditorDialog(
    viewModel: MainViewModel,
    existing: PromoRule?,
    existingRules: List<PromoRule>,
    onDismiss: () -> Unit,
    onSave: (PromoRule) -> Unit,
) {
    val selectedItems = remember {
        mutableStateListOf<PickerItem>().apply {
            existing?.items?.forEach { add(PickerItem(it.id, it.name, 0L)) }
        }
    }
    var labelText by remember { mutableStateOf(existing?.groupDisplayName().orEmpty()) }
    var qtyText by remember { mutableStateOf(existing?.requiredQty?.toString() ?: "2") }
    var priceText by remember {
        mutableStateOf(
            existing?.let { String.format(java.util.Locale.US, "%.2f", it.bundlePriceCents / 100.0) } ?: "",
        )
    }
    var daysMask by remember { mutableIntStateOf(existing?.daysOfWeek ?: PromoRule.ALL_DAYS) }
    var startText by remember { mutableStateOf(initialClockField(existing, start = true)) }
    var endText by remember { mutableStateOf(initialClockField(existing, start = false)) }
    var showItemPicker by remember { mutableStateOf(false) }

    val qty = qtyText.toIntOrNull()
    val priceCents = dollarsToCents(priceText)
    val effectiveLabel = labelText.ifBlank { selectedItems.firstOrNull()?.name ?: "" }
    val times = editorTimes(startText, endText)
    val valid = selectedItems.isNotEmpty() &&
        effectiveLabel.isNotBlank() &&
        qty != null && qty >= 2 &&
        priceCents != null && priceCents > 0 &&
        daysMask and PromoRule.ALL_DAYS != 0 &&
        times != null

    val autoName = if (valid) {
        "$qty x $effectiveLabel for ${centsToDollars(priceCents!!)}"
    } else {
        ""
    }

    val selectedIds = selectedItems.map { it.id }.toSet()
    val overlap = existingRules.filter { other ->
        other.active &&
            other.id != (existing?.id ?: 0L) &&
            other.items.any { it.id in selectedIds }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New promotion" else "Edit promotion") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { showItemPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (selectedItems.isEmpty()) {
                            "Choose items\u2026"
                        } else {
                            selectedItems.joinToString { it.name }
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "Add every flavor/SKU that should count toward this deal together.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = labelText,
                    onValueChange = { labelText = it },
                    label = { Text("Group name (e.g. Red Bull)") },
                    placeholder = {
                        Text(selectedItems.firstOrNull()?.name ?: "Red Bull")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { qtyText = it },
                    label = { Text("Quantity required (min 2)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text("Bundle price, e.g. 5.00") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Days (device local time)", style = MaterialTheme.typography.bodySmall)
                DayToggles(mask = daysMask, onChange = { daysMask = it })

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        label = { Text("Start HH:mm") },
                        placeholder = { Text("all day") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it },
                        label = { Text("End HH:mm") },
                        placeholder = { Text("all day") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (autoName.isNotEmpty()) {
                    Text(
                        "Will show on receipts as: PROMO: $autoName",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (overlap.isNotEmpty()) {
                    Text(
                        "Some of these items are already in ${overlap.joinToString { it.name }}. " +
                            "An item counts toward only one promotion — the earliest one wins, so remove it there first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val window = times ?: return@TextButton
                    onSave(
                        PromoRule(
                            id = existing?.id ?: 0,
                            name = autoName,
                            label = effectiveLabel,
                            items = selectedItems.map { PromoItemRef(it.id, it.name) },
                            requiredQty = qty!!,
                            bundlePriceCents = priceCents!!,
                            active = existing?.active ?: true,
                            daysOfWeek = daysMask,
                            startMinute = window.first,
                            endMinute = window.second,
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
            initiallySelected = selectedItems.toList(),
            onDismiss = { showItemPicker = false },
            onConfirm = { chosen ->
                selectedItems.clear()
                selectedItems.addAll(chosen)
                showItemPicker = false
            },
        )
    }
}

@Composable
private fun DayToggles(mask: Int, onChange: (Int) -> Unit) {
    val labels = listOf("S", "M", "T", "W", "T", "F", "S")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { i, label ->
            val selected = mask and (1 shl i) != 0
            OutlinedButton(
                onClick = { onChange(mask xor (1 shl i)) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }
        }
    }
}

@Composable
private fun ItemPickerDialog(
    viewModel: MainViewModel,
    initiallySelected: List<PickerItem>,
    onDismiss: () -> Unit,
    onConfirm: (List<PickerItem>) -> Unit,
) {
    var allItems by remember { mutableStateOf<List<PickerItem>?>(null) }
    var query by remember { mutableStateOf("") }
    val inventoryError by viewModel.inventoryError.collectAsStateWithLifecycle()
    val chosen = remember {
        mutableStateListOf<PickerItem>().apply { addAll(initiallySelected) }
    }

    LaunchedEffect(Unit) {
        allItems = viewModel.loadInventory()
    }

    val filtered = allItems.orEmpty().filter { it.matchesQuery(query) }

    BarcodeScanEffect { scanned ->
        query = scanned
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose items (${chosen.size} selected)") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search name, SKU, or barcode") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Scan a barcode to find the item.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                when {
                    allItems == null -> Text("Loading inventory\u2026", Modifier.padding(top = 16.dp))
                    inventoryError != null -> Text(inventoryError!!, Modifier.padding(top = 16.dp))
                    filtered.isEmpty() -> Text("No items found", Modifier.padding(top = 16.dp))
                    else -> LazyColumn(Modifier.heightIn(max = 400.dp).padding(top = 8.dp)) {
                        items(filtered, key = { it.id }) { item ->
                            val isChecked = chosen.any { it.id == item.id }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            if (chosen.none { it.id == item.id }) chosen.add(item)
                                        } else {
                                            chosen.removeAll { it.id == item.id }
                                        }
                                    },
                                )
                                Text(
                                    itemLabel(item),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = chosen.isNotEmpty(),
                onClick = { onConfirm(chosen.toList()) },
            ) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun itemLabel(item: PickerItem) = buildAnnotatedString {
    append("${item.name}  (${centsToDollars(item.priceCents)})")
    val skuText = when {
        item.sku.isNotBlank() -> "sku: ${item.sku}"
        item.barcode.isNotBlank() -> "barcode: ${item.barcode}"
        else -> ""
    }
    if (skuText.isNotBlank()) {
        append("  ")
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
            append(skuText)
        }
    }
}

/** While the item picker is open, a Clover hardware/USB/camera scan fills the search box. */
@Composable
private fun BarcodeScanEffect(onScan: (String) -> Unit) {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onScan)
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val data = intent ?: return
                val result = BarcodeResult(data)
                if (!result.isBarcodeAction) return
                val code = result.barcode?.trim().orEmpty()
                if (code.isNotEmpty()) latest.value(code)
            }
        }
        context.registerReceiver(receiver, IntentFilter(BarcodeResult.INTENT_ACTION))
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}

private fun initialClockField(existing: PromoRule?, start: Boolean): String {
    if (existing == null) return ""
    if (existing.startMinute <= 0 && existing.endMinute >= PromoRule.END_OF_DAY_MINUTE) return ""
    return formatClockMinutes24(if (start) existing.startMinute else existing.endMinute)
}

private fun editorTimes(startText: String, endText: String): Pair<Int, Int>? {
    if (startText.isBlank() && endText.isBlank()) {
        return 0 to PromoRule.END_OF_DAY_MINUTE
    }
    val start = parseClockMinutes(startText) ?: return null
    val end = parseClockMinutes(endText) ?: return null
    if (start == end) return null
    return start to end
}



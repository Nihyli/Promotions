package com.nihyli.cloverpromotions.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clover.sdk.util.CloverAccount
import com.clover.sdk.v3.inventory.InventoryContract
import com.nihyli.cloverpromotions.data.PromoDatabase
import com.nihyli.cloverpromotions.data.PromoRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lightweight projection of a Clover inventory item for the picker UI. */
data class PickerItem(val id: String, val name: String, val priceCents: Long)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = PromoDatabase.get(app).rules()

    val rules = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _inventoryError = MutableStateFlow<String?>(null)
    val inventoryError = _inventoryError.asStateFlow()

    fun save(rule: PromoRule) = viewModelScope.launch { dao.upsert(rule) }

    fun delete(rule: PromoRule) = viewModelScope.launch { dao.delete(rule) }

    fun toggleActive(rule: PromoRule) = viewModelScope.launch {
        dao.upsert(rule.copy(active = !rule.active))
    }

    /**
     * Loads the merchant's inventory for the item picker via the
     * [InventoryContract] content provider (no 500-item binder limit).
     */
    suspend fun loadInventory(): List<PickerItem> = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        val account = CloverAccount.getAccount(context)
        if (account == null) {
            _inventoryError.value = "Not logged into a Clover merchant on this device."
            return@withContext emptyList()
        }
        val items = mutableListOf<PickerItem>()
        try {
            context.contentResolver.query(
                InventoryContract.Item.contentUriWithAccount(account),
                arrayOf(
                    InventoryContract.Item.UUID,
                    InventoryContract.Item.NAME,
                    InventoryContract.Item.PRICE,
                ),
                null,
                null,
                "${InventoryContract.Item.NAME} COLLATE NOCASE ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    items += PickerItem(
                        id = id,
                        name = cursor.getString(1) ?: "(unnamed)",
                        priceCents = cursor.getLong(2),
                    )
                }
            }
            _inventoryError.value = if (items.isEmpty()) {
                "Inventory is empty for this merchant."
            } else {
                null
            }
        } catch (e: SecurityException) {
            Log.e("MainViewModel", "Failed to load inventory", e)
            _inventoryError.value =
                "Clover blocked inventory access (INVENTORY_R). Install Promotions on this test merchant from App Market Preview, then reopen this screen."
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to load inventory", e)
            _inventoryError.value = e.message ?: "Failed to load inventory."
        }
        items
    }
}

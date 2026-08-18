# Clover Promotions

A Clover POS Android app that auto-applies quantity bundle promotions in the
Register app — e.g. **2 x Red Bull for $5.00**. As items are scanned, the app
recomputes qualifying bundles and applies line-item discounts so the cart price
updates live. Merchants manage rules in a simple on-device settings screen.

## How it works

- Clover fires `LINE_ITEM_ADDED` / `LINE_ITEM_DELETED` / `ORDER_CREATED`
  broadcasts as the Register cart changes. `PromoBroadcastReceiver` wakes on
  these and hands the order ID to `PromoEngine`.
- `PromoEngine` fetches the order via `OrderConnector`, groups qualifying line
  items into bundles per rule, and reconciles discounts (tagged `PROMO: `)
  idempotently — adding missing ones, deleting stale ones (e.g. after an item
  is removed from the cart).
- Rules are stored in a local Room database and edited in a Jetpack Compose UI
  (`MainActivity`), with an inventory item picker backed by
  `InventoryConnector`.

## Project layout

- `app/src/main/java/com/nihyli/cloverpromotions/engine/PromoEngine.kt` — bundle math + discount reconciliation
- `.../receiver/PromoBroadcastReceiver.kt` — Clover broadcast entry point
- `.../data/` — Room entity/DAO/database for promo rules
- `.../ui/` — Compose rules list, rule editor, inventory picker

## Requirements

- JDK 17+ and the Android SDK (compileSdk 35)
- A [Clover sandbox developer account](https://www.clover.com/developers) with
  a test merchant
- A Clover emulator (Android Studio AVD with Clover sandbox APKs sideloaded)
  or a Clover Dev Kit — see
  [Clover emulator setup](https://docs.clover.com/dev/docs/setting-up-an-android-emulator)

## Build and install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Manifest receivers are inactive until Clover finishes app install bookkeeping;
# when sideloading, trigger it manually:
adb shell am broadcast -a com.clover.intent.action.APP_INSTALL_DONE \
  -n com.nihyli.cloverpromotions/.receiver.PromoBroadcastReceiver
```

## Test the 2-for-$5 flow

1. In the sandbox Merchant Dashboard, create an inventory item (e.g. Red Bull,
   $3.00).
2. Open the Promotions app on the device, add a rule: item Red Bull,
   quantity 2, bundle price 5.00.
3. In Register, add two Red Bulls — the cart shows a `PROMO:` discount of
   -$1.00 (total $5.00). A third Red Bull stays full price until a fourth
   completes another bundle. Removing an item removes the discount.

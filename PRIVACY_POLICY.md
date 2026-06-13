# Privacy Policy

Last updated: 2026-06-14

Receiptory is a receipt storage and AI extraction app. This policy describes how the current app and included backend handle data.

## Data stored on your device

Receiptory stores the following in the app's local storage:

- Receipt photos you save
- Receipt records, including merchant, date, total, category, item rows, and extracted text
- Categories and AI-suggested category names
- App settings, including theme, currency, backend URL, AI category setting, and a generated installation ID

Manual receipt entry stays on your device unless you later choose to scan that saved receipt with AI.

## Data uploaded for AI scanning

When you choose an AI scan, the Android app sends this data to the backend URL configured in Settings:

- The receipt photo
- Current category IDs and names
- Whether AI may suggest new categories
- The generated installation ID

The included backend forwards the receipt image and category context to OpenAI to extract structured receipt data. The backend returns merchant, date, total, item rows, raw extracted text, and a category decision to the app.

## Purchases and scan credits

Receiptory uses Google Play Billing for AI scan packs. When verifying a scan pack purchase, the app sends the product ID, Google Play purchase token, and generated installation ID to the configured backend. The included backend validates the purchase with the Google Android Publisher API and stores scan credit counters by installation ID in its local `entitlements.json` file.

## Export, restore, and deletion

The app can export a zip backup containing local receipt data and stored photos. Restore imports a selected backup file onto the device.

Deleting a receipt in the app removes the local receipt record and stored photo. Deleting a category removes the local category and receipts/photos in it. Receiptory does not currently include an in-app request flow to delete data that may already have been processed by the configured backend or by OpenAI.

## Permissions

Receiptory requests:

- Camera: to photograph receipts
- Internet: to reach the configured backend, verify scan credits, verify purchases, and open policy links
- Google Play Billing: to sell AI scan packs through Google Play

## Backend configuration

The backend is configured by the user/operator. If you point the app to a backend that you do not control, that backend operator may process data differently from the included backend code.

## Contact

For questions or requests, use the repository issue tracker:

https://github.com/LEC1224/Receiptory/issues

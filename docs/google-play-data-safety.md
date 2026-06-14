# Google Play Data Safety Worksheet

Last updated: 2026-06-14

Use this as an implementation guide when completing Google Play's Data Safety form. Verify final answers against the exact production backend, release build, and any future policy changes before submission.

## Data collection and sharing summary

Receiptory stores receipt data locally by default. AI scanning uploads data to the configured backend by default. The included backend forwards receipt images and category context to OpenAI for extraction and contacts Google APIs for purchase verification. If own OpenAI key mode is enabled, receipt images and category context are sent directly from the device to OpenAI for extraction.

## Data types to review in Play Console

| Data type | Current implementation detail | Likely purpose |
| --- | --- | --- |
| Photos and videos: photos | Receipt photos are stored locally. Default AI scans upload receipt photos to the configured backend, which forwards them to OpenAI. Own-key AI scans send receipt photos directly to OpenAI from the device. | App functionality |
| Files and docs: files/docs | Exported backups contain receipt JSON and photos. Backup files are created only when the user chooses export. | App functionality |
| Financial info: purchase history | Google Play purchase tokens/product IDs are sent to the backend for scan pack verification. The backend stores product ID, order ID if returned by Google, purchase time if returned by Google, and granted scan count. | App functionality, account management |
| User-generated content or other user data | Receipt merchant, date, total, item rows, categories, and extracted text are stored locally. AI scans send category names and return extracted text. | App functionality |
| App activity: app interactions | The app maintains local settings such as theme, currency, optional OpenAI key/model, AI category option, and scan status. | App functionality |
| Device or other IDs | The app generates an installation ID and sends it to the backend for scan credits, AI scan debit checks, and purchase verification. | App functionality, account management |

## Security notes to verify before release

- `android.permission.CAMERA` is used for receipt capture.
- `android.permission.INTERNET` is used for backend access, optional direct OpenAI access in own-key mode, purchase verification through the backend, and policy links.
- `com.android.vending.BILLING` is used for Google Play in-app products.
- The current manifest allows cleartext traffic because the default local backend examples use HTTP. For production, prefer HTTPS and revisit `android:usesCleartextTraffic`.
- The included backend stores entitlement counters in local `entitlements.json`. It does not implement a user-facing remote deletion request endpoint.
- Entitlement counters include free scan allowance, purchased scans, and used scans by installation ID.

## Suggested conservative disclosure language

- Data is collected only when users use AI scanning, purchase verification, or export/restore flows that require it.
- Receipt photos and receipt text can contain personal or financial information depending on the receipt contents.
- AI-scanned receipt photos are shared with the configured backend and forwarded to OpenAI by the included backend by default. In own-key mode, the app sends AI-scanned receipt photos directly to OpenAI.
- Manual receipts remain local unless scanned with AI later.
- Local deletion removes the app's local copy; it does not automatically delete data already processed by backend providers.

## Links for Play listing

- Privacy Policy: https://github.com/LEC1224/Receiptory/blob/master/PRIVACY_POLICY.md
- Terms: https://github.com/LEC1224/Receiptory/blob/master/TERMS.md

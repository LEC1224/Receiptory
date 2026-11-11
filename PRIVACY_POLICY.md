# Privacy Policy

Last updated: 2026-11-11

This policy applies to Receiptory 0.2.0 and later. [Earlier versions](https://github.com/LEC1224/Receiptory/blob/30d7a21112273813e194dd89858513c0793fdba0/PRIVACY_POLICY.md) used a different scanning and purchase system.

## On-device scanning and storage

Receiptory uses a bundled Google ML Kit text recognition model to read photos on the device. The app then extracts receipt details and suggests categories locally. Receipt photos, OCR text, and scan results are not sent to a server for scanning. The app does not request Android's Internet permission and scanning works without a connection.

Receiptory stores saved photos, merchant names, dates, totals, item lists, extracted text, categories, and app preferences in private app storage. When you change a receipt's category, the app can remember that category for the same merchant on this device.

Google describes the SDK's technical diagnostic events in its [ML Kit Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure). See [ML Kit Terms & Privacy](https://developers.google.com/ml-kit/terms) for how the SDK handles recognition input and output.

## Export, restore, and deletion

Use **Local backup** in Settings to export or restore a ZIP file with receipt data and photos. You choose where to save the file. If you choose a cloud storage provider, that provider handles the file according to its own policies. Receiptory disables automatic app backup. Export a backup before uninstalling or moving to another phone.

Deleting a receipt removes the app's local record and saved photo. Deleting a category also deletes its receipts. Deleting app data clears its local records and preferences. Exported backups must be managed separately.

## Permissions

Camera access is used to photograph receipts. Policy links open in your browser.

## Contact

[Receiptory issue tracker](https://github.com/LEC1224/Receiptory/issues)

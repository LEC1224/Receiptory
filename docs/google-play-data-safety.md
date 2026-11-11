# Google Play Data Safety Worksheet

Last updated: 2026-11-11

This worksheet describes the 0.2.0 Android build. Check the exact Play build and current Play requirements before submitting the form.

- **Receipts and photos:** The app stores photos, receipt details, raw text, and categories in private app storage. The bundled OCR model and parser run on the phone. No receipt data is uploaded for scanning.
- **Backup and restore:** The user selects the file destination or source. The backup includes receipt records and photos. A third-party document provider handles any file the user chooses to place there. Android automatic app backup is disabled.
- **Preferences:** Theme, display currency, and remembered merchant categories are stored on the device. The app removes the obsolete API key and scan installation ID preferences when upgraded.
- **Purchases:** The app does not use Play Billing or send purchase tokens. Any charge for downloading the app is handled by the storefront.
- **Bundled SDK:** Review [Google's ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure) for diagnostic data types. Check the merged manifest: Receiptory explicitly removes the Internet permission. No ML Kit download is needed to scan Latin-script receipts.
- **Permissions:** Camera access is used to photograph receipts.

[Privacy Policy](../PRIVACY_POLICY.md) · [Terms](../TERMS.md)

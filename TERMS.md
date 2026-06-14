# Terms

Last updated: 2026-06-14

These terms apply to the current Receiptory Android app and included backend.

## Use of the app

Receiptory helps store receipt photos and extract receipt details. AI extraction may be incomplete or incorrect. Review extracted merchant names, dates, totals, items, categories, and raw text before relying on them.

## AI scanning

AI scanning uses the configured backend URL and available AI scan credits by default. During a default AI scan, receipt photos and category context are uploaded to the configured backend and forwarded to OpenAI for extraction.

You may enable own OpenAI key mode in Settings. In that mode, AI scans use the OpenAI API key and model you provide, send receipt photos and category context directly to OpenAI from your device, and do not consume backend scan credits for extraction.

Manual receipt entry does not require AI scan credits.

## Purchases

AI scan packs are purchased through Google Play Billing. The included backend validates purchases with Google Play before adding scan credits. Scan credit availability depends on successful backend purchase verification.

## Your responsibility

You are responsible for:

- Choosing and operating a backend URL you trust
- Safeguarding any OpenAI API key you enter into the app
- Keeping exported backups secure
- Reviewing AI-extracted data before using it for financial, tax, reimbursement, or recordkeeping decisions
- Complying with laws and policies that apply to your use of receipt data

## No professional advice

Receiptory does not provide tax, accounting, legal, or financial advice.

## Availability

Backend availability, network access, Google Play services, and OpenAI services may affect AI scanning and purchase verification.

## Contact

For questions, use the repository issue tracker:

https://github.com/LEC1224/Receiptory/issues

# Receiptory AI backend

This backend runs on your Windows PC and keeps the OpenAI API key off the Android device. The Android app sends receipt photos and category names to this service, then this service calls OpenAI and returns the structured receipt JSON.

## Requirements

- Node.js 18 or newer on the Windows PC
- An OpenAI API key stored only on the PC
- The phone and PC on the same network, or an emulator using `http://10.0.2.2:8787`

## Setup

1. Copy `backend/.env.example` to `backend/.env`.
2. Put your OpenAI key in `OPENAI_API_KEY`.
3. Start the backend:

```powershell
cd D:\Documents\Kvittordning\backend
.\start-receiptory-backend.ps1
```

4. Test from the PC:

```powershell
Invoke-RestMethod http://localhost:8787/health
```

5. If using a physical phone, set the app's Backend URL to your PC LAN address, for example:

```text
http://192.168.1.50:8787
```

Windows Firewall may ask for access the first time Node starts. Allow private network access so your phone can reach the service.

## Run on Windows startup

Run this once:

```powershell
cd D:\Documents\Kvittordning\backend
.\install-startup.ps1
Start-ScheduledTask -TaskName "Receiptory AI Backend"
```

The scheduled task starts the backend at Windows logon. It reads `backend/.env`, so you can change `OPENAI_MODEL` or `PORT` without editing the app.

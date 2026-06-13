# Receiptory

En native Android-app for att fotografera kvitton, extrahera inkopsdata via en lokal AI-backend och lagra kvitton efter kategori.

## Funktioner

- Kamera som startvy med slutarknapp, retake och submit.
- Settings-vy med ljust, morkt eller systemstyrt lage samt URL till AI-backend.
- Lokal lagring av originalbild, extraherad text, varurader och totalsumma.
- Kvitton kan sparas manuellt for att AI-skannas senare.
- AI-skanning anvander kopta Google Play scan credits. Manuell kvittoinmatning ar gratis.
- Kategorivy med total spendering per kategori och filter for manad, ar eller eget datumspann.
- Kvitton visas kronologiskt med thumbnail, datum och total.
- Kvitton kan flyttas mellan kategorier och anvandaren kan skapa egna kategorier.
- Storage-vyn kan skanna alla manuellt sparade, oskannade kvitton i efterhand.
- AI-backenden skickar med alla befintliga kategorier och later modellen antingen valja en befintlig kategori eller foresla en ny.
- Settings-vyn visar kvarvarande AI scans och kopknappar for 100, 500 och 2000 scans.

## Google Play produkter

Skapa dessa one-time in-app products i Play Console innan release:

| Product ID | Scans | Pris |
| --- | ---: | ---: |
| `ai_scans_100` | 100 | USD 2.00 |
| `ai_scans_500` | 500 | USD 5.00 |
| `ai_scans_2000` | 2000 | USD 10.00 |

Backenden maste ha Android Publisher API-atkomst via `GOOGLE_PLAY_SERVICE_ACCOUNT_FILE` eller `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` for att verifiera kop innan credits laggs till.

## AI-backend pa Windows

Appen lagrar inte langre nagon OpenAI API-nyckel. Kor i stallet backend-tjansten pa din Windows-PC och ange datorns URL i appens settings.

```powershell
cd backend
Copy-Item .env.example .env
notepad .env
.\start-receiptory-backend.ps1
```

Testa backend fran datorn:

```powershell
Invoke-RestMethod http://localhost:8787/health
```

Installera som startup-task i Windows:

```powershell
cd backend
.\install-startup.ps1
Start-ScheduledTask -TaskName "Receiptory AI Backend"
```

Pa emulator ar standard-URL `http://10.0.2.2:8787`. Pa fysisk telefon, satt Backend URL till datorns LAN-IP, till exempel `http://192.168.1.50:8787`.

## Kom igang

Bygg debug-appen:

```powershell
.\gradlew.bat assembleDebug
```

Installera pa en ansluten Android-enhet eller emulator:

```powershell
.\gradlew.bat installDebug
```

APK:n hamnar i:

```text
app/build/outputs/apk/debug/app-debug.apk
```

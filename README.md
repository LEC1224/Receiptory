# Receiptory

En native Android-app for att fotografera kvitton, extrahera inkopsdata via en lokal AI-backend och lagra kvitton efter kategori.

## Funktioner

- Kamera som startvy med slutarknapp, retake och submit.
- Settings-vy med ljust, morkt eller systemstyrt lage samt URL till AI-backend.
- Lokal lagring av originalbild, extraherad text, varurader och totalsumma.
- Kategorivy med total spendering per kategori och filter for manad, ar eller eget datumspann.
- Kvitton visas kronologiskt med thumbnail, datum och total.
- Kvitton kan flyttas mellan kategorier och anvandaren kan skapa egna kategorier.
- AI-backenden skickar med alla befintliga kategorier och later modellen antingen valja en befintlig kategori eller foresla en ny.

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

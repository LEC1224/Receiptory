# Receiptory

En native Android-app for att fotografera kvitton, extrahera inkopsdata via en lokal AI-backend och lagra kvitton efter kategori.

## Funktioner

- Kamera som startvy med slutarknapp, retake och submit.
- Settings-vy med ljust, morkt eller systemstyrt lage och valfri egen OpenAI-nyckel for power users.
- Lokal lagring av originalbild, extraherad text, varurader och totalsumma.
- Kvitton kan sparas manuellt for att AI-skannas senare.
- AI-skanning anvander kopta Google Play scan credits som standard. Power users kan valfritt aktivera egen OpenAI-nyckel for att skanna utan att forbruka credits.
- Nya installationer far 20 inkluderade backend AI-scans innan kopta scan packs behovs.
- Kategorivy med total spendering per kategori och filter for manad, ar eller eget datumspann.
- Kvitton visas kronologiskt med thumbnail, datum och total.
- Kvitton kan flyttas mellan kategorier och anvandaren kan skapa egna kategorier.
- Storage-vyn kan skanna alla manuellt sparade, oskannade kvitton i efterhand.
- AI-backenden skickar med alla befintliga kategorier och later modellen antingen valja en befintlig kategori eller foresla en ny.
- Settings-vyn visar kvarvarande AI scans och kopknappar for 100, 500 och 2000 scans.

## Privacy, trust och Play Console

Publika dokument for Google Play:

- Privacy Policy: https://github.com/LEC1224/Receiptory/blob/master/PRIVACY_POLICY.md
- Terms: https://github.com/LEC1224/Receiptory/blob/master/TERMS.md
- Data Safety worksheet: `docs/google-play-data-safety.md`

Kort dataflode:

- Sparade kvitton, foton, kategorier, extraherad text och installningsdata lagras lokalt i appens private storage.
- Manuell kvittoinmatning stannar lokalt om anvandaren inte senare valjer AI-skanning.
- AI-skanning laddar upp kvittofoto, kategorier, AI-kategoriinstallning och installation ID till den backend-URL som ar satt i Settings.
- Den inkluderade backenden skickar kvittobilden och kategori-kontext vidare till OpenAI for extraktion.
- Om egen OpenAI-nyckel aktiveras skickar appen kvittofoto och kategori-kontext direkt till OpenAI fran enheten och anvander inte backend scan credits for sjalva extraktionen.
- Google Play purchase tokens skickas till backenden nar scan packs verifieras.
- Export skapar en zip med lokal kvittodata och foton. Lokal radering tar bort appens lokala kvitto och foto, men ar inte en fjarraderingsbegaran till backend eller OpenAI.

## Google Play produkter

Skapa dessa one-time in-app products i Play Console innan release:

| Product ID | Scans | Pris |
| --- | ---: | ---: |
| `ai_scans_100` | 100 | USD 2.00 |
| `ai_scans_500` | 500 | USD 5.00 |
| `ai_scans_2000` | 2000 | USD 10.00 |

Backenden maste ha Android Publisher API-atkomst via `GOOGLE_PLAY_SERVICE_ACCOUNT_FILE` eller `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` for att verifiera kop innan credits laggs till.

## AI-backend pa Windows

Appen anvander backend scan credits som standard. Egen OpenAI API-nyckel kan aktiveras i settings for power users som vill betala OpenAI direkt och inte forbruka backend credits.

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

Appens standardbackend ar hardkodad till `http://lecani.se:8787` och visas inte i settings.

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

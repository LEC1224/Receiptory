# Kvittordning

En native Android-app for att fotografera kvitton, extrahera inkopsdata med OpenAI och lagra kvitton efter kategori.

## Funktioner

- Kamera som startvy med slutarknapp, retake och submit.
- Settings-vy med ljust, morkt eller systemstyrt lage samt OpenAI API-nyckel och modellval.
- Lokal lagring av originalbild, extraherad text, varurader och totalsumma.
- Kategorivy med total spendering per kategori och filter for manad, ar eller eget datumspann.
- Kvitton visas kronologiskt med thumbnail, datum och total.
- Kvitton kan flyttas mellan kategorier och anvandaren kan skapa egna kategorier.
- OpenAI-anropet skickar med alla befintliga kategorier och later modellen antingen valja en befintlig kategori eller foresla en ny.

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

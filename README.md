# Receiptory

Receiptory är en Android-app för att fotografera, skanna och ordna kvitton. [Hämta APK-filen från GitHub Releases](https://github.com/LEC1224/Receiptory/releases/latest).

## Skanna och spara

- Fotografera ett kvitto och välj **Scan receipt**. Appen läser butik, datum, totalbelopp och varurader och föreslår en kategori.
- Skanningen sker på telefonen, även första gången efter installation. Ingen uppkoppling, API-nyckel, server eller skanningskredit behövs.
- Öppna **Review & edit** om något saknas eller om beloppen inte stämmer. Okända datum och belopp lämnas tomma. Bilden och den lästa texten finns kvar för kontroll.
- Du kan spara kvitton manuellt och skanna dem senare, ett i taget eller i grupp. Appen kommer ihåg när du flyttar ett kvitto till en annan kategori för samma butik.
- Använd **Settings → Local backup** för att exportera eller återställa dina kvitton och foton som en ZIP-fil.

Appen har stöd för Android 6.0 (API 23) och senare, inklusive 32- och 64-bitars ARM. OCR-modellen är inbyggd och läser latinsk skrift. Tolkningen är främst anpassad för svenska och engelska kvitton. Svårlästa foton, ovanliga layouter och andra språk kan behöva rättas manuellt.

## Bygg och testa

Bygg med JDK 17 och Android SDK 35:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

APK-filen finns i `app/build/outputs/apk/debug/app-debug.apk`. Med en emulator eller telefon ansluten testar `.\gradlew.bat :app:connectedDebugAndroidTest` även OCR och lagring. För att verifiera första skanningen offline, slå på flygplansläge innan appen installeras och startas.

Bygg en signerad APK med `.\gradlew.bat :app:assembleRelease -PreceiptorySigningProperties=<sökväg>`. Egenskapsfilen ska innehålla `storeFile` (relativ till egenskapsfilen eller absolut), `storePassword`, `keyAlias` och `keyPassword`. Förvara filen och nyckeln lokalt. Använd samma signeringsnyckel för framtida uppdateringar av GitHub-versionen.

## Integritet

- [Privacy Policy](PRIVACY_POLICY.md)
- [Terms](TERMS.md)
- [Google Play Data Safety worksheet](docs/google-play-data-safety.md)

Backenden i [backend/](backend/) hör till äldre appversioner. Version 0.2.0 och senare behöver den inte.

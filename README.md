# Deurbel – Android-app voor de Tapo D235

Eigen app voor de TP-Link Tapo D235 videodeurbel. Werkt **rechtstreeks via je thuis-wifi**, zonder Tapo-app of cloud.

## Functies
- **Melding bij aanbellen** (volledig scherm + belgeluid), met knoppen voor snel antwoord in de melding
- **Meldingen aan/uit** en **gong (chime) aan/uit** met één schakelaar
- **Live beeld** van wie er voor de deur staat, met geluid van buiten
- **Praten** via de deurbel (microfoonknop ingedrukt houden)
- **Opnames terugkijken** van de SD-kaart, **downloaden** (MP4 in Video's › Deurbel) en **verwijderen**
- **Snelle antwoorden in het Nederlands** (computerstem), zelf toe te voegen/wijzigen, of **met je eigen stem** opgenomen

## Installeren
1. Download `TapoDeurbel.apk` bij *Releases → Deurbel-app (laatste versie)* van deze repository
   (of via *Actions → Android APK → artifact*).
2. Open het bestand op je telefoon en sta "installeren uit onbekende bron" toe (Android 10 of nieuwer).
3. Open **Deurbel → Instellingen**: tik op **Zoek** (of vul het IP-adres in) en vul het wachtwoord van je
   TP-Link/Tapo-account in. Tik **Opslaan en verbinden**.
4. Tik op *Batterijbeperking uitzetten* zodat meldingen ook komen als de telefoon in slaap is.

## Goed om te weten
- De deurbel moet eenmalig met de Tapo-app op wifi gezet zijn (zo komt hij op je netwerk). Daarna is de Tapo-app niet meer nodig.
- Alles werkt lokaal: buiten je thuisnetwerk is er geen verbinding (bewust, geen cloud).
- Geef de deurbel in je router een vast IP-adres.
- Opnames vereisen een micro-SD-kaart in de deurbel. "Verwijderen" haalt een opname weg uit de app en van de telefoon;
  losse opnames op de SD-kaart wissen kan via de lokale verbinding niet (de deurbel overschrijft oude opnames zelf).
- Welke gebeurtenis een druk op de bel is verschilt per firmware. Standaard meldt de app elke gebeurtenis;
  in Instellingen kun je kiezen bij welke typen je een melding wilt.
- De gong aan/uit-knop gebruikt de ring-instelling van de deurbel voor de gekoppelde gong.

## Techniek
- `core/` – lokaal Tapo-protocol in Kotlin: inloggen (encrypt_type 3 én nieuwere SPAKE2+/TPAP-firmware),
  versleutelde API, mediapoort 8800 (live, terugpraten, SD-download). Gebaseerd op het open-source werk van
  [pytapo](https://github.com/JurajNyiri/pytapo) en [go2rtc](https://github.com/AlexxIT/go2rtc).
- `app/` – Jetpack Compose-app, Media3/ExoPlayer, voorgrondservice voor meldingen.
- Wachtwoorden worden versleuteld opgeslagen (Android Keystore); het certificaat van de deurbel wordt bij de eerste verbinding vastgelegd.
- Bouwen: `./gradlew :app:assembleRelease` (GitHub Actions doet dit automatisch bij elke push).

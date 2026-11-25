# Wear Live Updates — corecția dezactivării la expirare

8 noiembrie 2026. Corecția este în cod, cu APK debug compilat; nu a fost instalată
pe ceasul fizic în această etapă.

## Comportament corectat

Android execută `deleteIntent` și când notificarea expiră automat. Implementarea
anterioară folosea direct acțiunea de dezactivare permanentă, astfel că o pauză
BLE putea ascunde definitiv Live Updates după expirarea cardului retransmis.

Callbackul de ștergere folosește acum o acțiune separată și termenul de expirare
calculat cu `SystemClock.elapsedRealtime()`, aceeași bază de timp ca timeoutul
Android. Un callback primit la sau după acel termen nu modifică preferința de
afișare și nu inițializează inutil graful aplicației. O citire nouă poate afișa
din nou cardul. Fereastra de prospețime de 330s și eliminarea valorilor vechi
rămân în vigoare.

Fiecare termen are propria identitate PendingIntent, inclusă în URI. Astfel,
extras-urile unei notificări noi nu pot rescrie termenul dintr-un callback vechi
livrat cu întârziere. Ascunderea explicită și ștergerea cardului înainte de timeout
continuă să memoreze dezactivarea. Comenzile explicite de activare/dezactivare nu
sunt limitate de termenul notificării.

Nu au fost adăugate alarme, wakelock-uri sau servicii. Nu s-a modificat recuperarea
Bluetooth în această corecție.

O preferință deja salvată ca dezactivată nu este rescrisă automat: nu poate fi
deosebită sigur de o alegere manuală anterioară. Dacă Live Updates este încă oprit,
trebuie activat o dată după actualizare.

## Verificare

- **63 de teste unitare Wear trecute**, fără erori sau teste omise.
- **3 teste Android trecute pe emulator Wear OS 7 / API 37**, fără teste omise.
  Regresia nouă verifică timeoutul real Android printr-o notificare separată care
  folosește callbackul cardului aplicației; separarea împiedică anularea din
  coroutine să mascheze timeoutul sistemului. Verifică apoi revenirea unui card
  promovabil la o citire nouă, ignorarea callbackului vechi după recuperare și
  păstrarea dezactivării manuale la citirile următoare.
- Testele existente pentru notificări și recuperarea vârstei la trezirea ecranului
  au trecut. Testele cu citiri sintetice sunt limitate la emulator.
- `:wear:assembleDebug`, `:wear:assembleDebugAndroidTest` și `:wear:lintRelease`:
  reușite. `git diff --check`: fără erori.

Cod: `wear/src/main/kotlin/re/abbot/librecr/app/live/WearLiveUpdatesNotifier.kt`.
Regresie Android:
`wear/src/androidTest/kotlin/re/abbot/librecr/app/live/WearLiveUpdatesNotificationTest.kt`.
APK: `wear/build/outputs/apk/debug/wear-debug.apk`.
Rezultate Android temporare: `/tmp/librecr-wear-expiry-instrumentation-20261108.txt`.

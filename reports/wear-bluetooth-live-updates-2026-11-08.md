# Întrerupere BLE și Live Updates — 8 noiembrie 2026

Interval cerut: ultimele 15 minute la începutul investigației, aproximativ **11:14–11:29 CET**. Capturi pe telefon la 11:29 și pe ceas la 11:30–11:31; verificare suplimentară la 11:33. Au fost efectuate citiri de diagnostic. Nu au fost repornite serviciile aplicației, modificate setările, instalate APK-uri sau resetate jurnalele în această investigație. Reconectarea ADB la IP-ul ceasului a fost necesară după dispariția conexiunii mDNS.

## Concluzie

Sunt două probleme distincte:

1. **Întrerupere reală BLE ceas–senzor**, urmată de recuperare nereușită pe telefon. Ultima citire live înainte de incident: **11:18:53.232**. Următoarea citire live: **11:26:57.422**, după aproximativ **8m 4s**. Datele intermediare au fost recuperate ulterior ca backfill; prezența lor în grafic nu dovedește că au fost disponibile live.
2. **Expirarea cardului Live Updates dezactivează preferința permanentă de afișare**. Valorile au revenit pe ceas și sunt retransmise telefonului, dar Live Updates a rămas oprit. Afișarea nu este blocată în așteptarea unei valori Bluetooth.

## Cronologie

| Ora CET | Dovezi |
| --- | --- |
| 11:14–11:18 | Citiri live consecutive, inclusiv lifeCount 19453 la 11:18:53.232. Notificările ceasului au `PROMOTED_ONGOING`. |
| **11:19:54.857** | Controlerul Bluetooth al ceasului închide legătura cu senzorul: **`CONNECTION_TIMEOUT(0x08)`**. Nu este o simplă expirare a textului din interfață. |
| 11:19:55–11:20:15 | O nouă instanță GATT LibreCR este înregistrată și apoi eliberată fără o conexiune reușită consemnată. |
| **11:22:13** | Ceasul oprește colectorul și pornește monitorul de recuperare. Momentul este compatibil cu pragul de 3m20s de la ultima citire. Fără logul aplicației nu atribuim toate tranzițiile exclusiv automatizării. |
| **11:22:17** | Telefonul pornește colectorul BLE. |
| 11:22:52–11:23:53 | Controlerul telefonului păstrează șase eșecuri **`CONNECTION_FAILED_ESTABLISHMENT(0x3e)`**. |
| **11:23:54–11:24:17** | Telefonul stabilește o legătură BLE, apoi senzorul o închide: **`REMOTE_USER_TERMINATED_CONNECTION(0x13)`**. Această denumire HCI indică dispozitivul de la distanță, nu o acțiune a utilizatorului. Nu există o citire live nouă în istoricul păstrat pentru această conectare. |
| **11:24:24.552** | Cardul Live Updates al ceasului este eliminat cu motivul Android **19 = `REASON_TIMEOUT`**, aproximativ la expirarea ferestrei de 330s după ultima citire. Preferința `wear_live_updates.xml` a fost scrisă în minutul 11:24 și conține `enabled=false`. |
| 11:26:27–11:26:41 | Telefonul renunță la colector; ceasul îl pornește din nou. |
| **11:26:52.094** | Ceasul reface legătura BLE cu senzorul. |
| **11:26:57.422** | Prima citire live nouă: lifeCount 19461. Urmează citiri pe minut; minutele 19454–19460 sunt marcate `BACKFILL_CLINICAL` în istoricul telefonului. Timestampurile backfill sunt reconstruite și nu reprezintă momentele recepției în timp real. |
| **11:32:52.168** | Ambele dispozitive au exact aceeași citire: **123 mg/dL, lifeCount 19467**, cu același timestamp. |

## De ce lipsește Live Updates după recuperare

La 11:30 notificarea obișnuită a ceasului conține deja `→ 112 Δ +4`, iar la verificarea finală conține valoarea actualizată. Deci datele ajung în aplicația și notificarea ceasului.

Starea preferinței este însă `enabled=false`; notificarea afișează explicit acțiunea **„Activează Live Updates”**, nu are `android.shortCriticalText` și nu are `PROMOTED_ONGOING`/metadatele OngoingActivity. Canalul este activ, cu importanța 2: nu apare o blocare a notificărilor de către sistem.

Lanțul din cod explică dezactivarea:

- `WearLiveUpdatesNotifier.buildNotification()` setează `setDeleteIntent(... ACTION_UNPIN)` pentru card.
- Când colectorul local este oprit, cardul primește `setTimeoutAfter()` pentru limita de prospețime de 330s.
- Android elimină notificarea la timeout cu `sendDelete=true` și execută `deleteIntent`.
- `WearLiveUpdatesReceiver` primește `ACTION_UNPIN` și persistă `setEnabled(false)` fără să distingă expirarea automată de o alegere explicită a utilizatorului.

Evenimentul 19 și scrierea preferinței în același minut susțin acest diagnostic. **Nu există temei să atribuim această dezactivare unei apăsări manuale.** Corecția trebuie să separe expirarea automată de comanda explicită de dezactivare, păstrând eliminarea valorilor vechi. Nu s-a modificat codul în această investigație.

Revenire temporară: în aplicația ceasului, atinge valoarea și alege **„Activează Live Updates”**, ori folosește acțiunea cu același nume din notificare. Aceasta schimbă afișarea, fără a necesita resetarea Bluetooth sau o nouă împerechere a senzorului. Fără corecția din cod, expirarea unui viitor card poate repeta problema.

## Diagnosticul Bluetooth și limite

La captură, **ceasul** are conexiunea GATT activă la senzor; telefonul nu are niciun client GATT LibreCR activ și funcționează ca receptor al valorilor retransmise de ceas. Starea senzorului păstrată pe ambele dispozitive este `errorData=0`, `patchState=4`.

Timeoutul confirmă pierderea legăturii BLE, dar nu permite identificarea precisă a cauzei radio: distanță/ecranare de către corp, interferență, controler sau comportamentul senzorului. Eșecurile telefonului și deconectarea inițiată de senzor explică de ce preluarea nu a restabilit rapid datele; nu dovedesc singure o cheie criptografică invalidă. Nu se observă un restart al aplicației în intervalul examinat. Versiunile instalate sunt Wear 0.1.5 (actualizat la 10:29:24) și telefon 0.1.6 (5 noiembrie).

Logarea detaliată LibreCR era **oprită pe ambele dispozitive**. `logcat` standard al ceasului mai acoperă doar de la 11:26:26, dar bufferul `events`, istoricul GATT/ACL și istoricul citirilor păstrează dovezi din intervalul cerut. Nu putem reconstrui exact faza de autentificare eșuată pe telefon sau RSSI-ul din momentul întreruperii. Jurnalizarea viitoare, dacă este activată, nu va recupera retroactiv acele detalii.

## Surse și reproducere

Capturi temporare locale: `/tmp/librecr-bluetooth-20261108-1129/`, subdirectoarele `phone/` și `watch/`: `bluetooth.txt`, `events.txt`, `app-events.txt`, `logcat.txt`, `notifications.txt`, `services.txt`, preferințe și snapshoturi finale. Cronologie extrasă: `reading-timeline.csv`. Capturile de sesiune conțin material privat și au permisiuni locale restrictive.

Codul aplicației: `wear/src/main/kotlin/re/abbot/librecr/app/live/WearLiveUpdatesNotifier.kt`, `WearLiveUpdateContent.kt`, `wear/src/main/kotlin/re/abbot/librecr/app/GlucoseDisplayFreshness.kt`.

Comportamentul timeout/deleteIntent a fost verificat în [sursa Android 16 NotificationManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/notification/NotificationManagerService.java): `timeoutNotification` / `mNotificationTimeoutReceiver`, `cancelNotificationLocked`, `sendDeleteIntent`. Constanta `REASON_TIMEOUT=19` este confirmată și în sursele SDK Android 36 instalate local (`android/service/notification/NotificationListenerService.java:270`).

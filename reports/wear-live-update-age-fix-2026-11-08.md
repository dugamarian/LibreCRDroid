# Wear OS Live Updates: timpul rămas la 0′ după repaus

8 noiembrie 2026. Corecția este instalată pe Pixel Watch 4.

## Constatare și corecție

Calculul vârstei și cheia de deduplicare includ corect minutele scurse. Textul
`shortCriticalText` este însă static: o actualizare a aplicației trebuie să îl
înlocuiască. Așteptarea coroutinei nu asigură recalcularea imediată după repaus,
iar implementarea anterioară nu asculta trezirea ecranului sau schimbările ceasului.

Colectorul primește acum semnalele `SCREEN_ON`, `USER_PRESENT` și `TIME_SET`.
Când Live Updates este activ, ascultă și `TIME_TICK`. Aceste semnale recalculează
vârsta și reprogramează așteptarea în raport cu timestampul original al citirii,
chiar dacă datele glicemice nu s-au schimbat și așteptarea veche nu s-a terminat.
Deduplicarea citirilor rămâne înaintea acestor semnale; notificările identice
continuă să fie suprimate. Ascunderea afișării fără serviciu local anulează
abonamentele, receptorul dinamic și așteptarea. Nu s-au adăugat alarme sau wakelock-uri.

O citire nouă în fiecare minut resetează în continuare vârsta la `0′`, inclusiv
când valoarea glicemiei este identică. Corecția privește îmbătrânirea aceleiași citiri.
Nu promite actualizarea unui text static în timp ce CPU-ul este suspendat;
afișarea recuperează minutele scurse la reluare/primirea semnalelor sistemului.

## Verificare

- **63 teste unitare Wear trecute**, inclusiv trei cazuri noi: trezire cu salt al
  timpului fără citire nouă, deduplicare/anulare și trezire după expirare.
- **Două teste Android trecute pe emulatorul Wear OS**. Testul nou folosește
  trezirea reală a ecranului și un ceas controlat pentru a modela un salt de la
  10 la 135 de secunde, păstrând vechea așteptare suspendată: textul trece din
  `0′` în `2′`, următoarea așteptare este de 45 de secunde, timestampul nu se schimbă.
  Testul existent validează notificarea reală, avansul `0′` → `1′`, NA și expirarea.
- `assembleDebug`, `assembleDebugAndroidTest` și `lintRelease`: reușite.
- **Pe ceasul fizic**, după instalare: semnalele de timp și trezire sunt înregistrate;
  `shortCriticalText`, detaliul notificării și captura cadranului afișează `1′`/`1 min`.
  Timestampul original al citirii este păstrat, iar `SensorForegroundService`
  rămâne foreground. Nu s-au injectat citiri sintetice și nu s-a șters sesiunea.

APK-ul anterior este păstrat în `/tmp/librecr-wear-before-age-fix-2026-11-08.apk`;
captura reală este `/tmp/librecr-wear-age-fix-2026-11-08.png`.

Referințe: [ceasurile Android și suspendarea](https://developer.android.com/reference/android/os/SystemClock),
[conținutul chipului și prioritatea textului](https://developer.android.com/reference/android/app/Notification.Builder#setShortCriticalText(java.lang.String)),
[Live Updates pe Wear OS](https://developer.android.com/training/wearables/notifications/live-updates).

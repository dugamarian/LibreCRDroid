# Bateria Pixel Watch 4 — 8 noiembrie 2026

Captură doar prin citiri ADB la 10:34:49–10:34:54 CET, fără resetări, instalări sau schimbări de setări în această investigație.

## Interval și descărcare

Istoricul include ora 08:00. Scoaterea efectivă de la încărcat este la **08:29:20.274**, când apare `plug=none` și `-plugged`, cu bateria 100%. Statisticile de după încărcare încep la 08:29:21.537 și acoperă 2h 5m 32s. La 10:34:49 bateria este **91%**, aproximativ **4,3 puncte procentuale/oră**. A rămas afișat 100% până la 09:09:27; apoi 98% la 09:13, 97% la 09:27, 96% la 09:42, 95% la 09:51, 94% la 10:15, 93% la 10:21:37, 92% la 10:21:48, 91% la 10:30. Saltul de două puncte în 12 secunde poate include recalibrarea indicatorului; procentele nu reprezintă un debit instantaneu de energie.

## Dovezi de activitate

- CPU nesuspendat: **58m 2s (46,2%)**; ecran activ doar **1m 41s**. Uptime nu înseamnă utilizare CPU la capacitate maximă.
- Wi-Fi: **151 tranziții `4-way-handshake`**, circa 72/oră, după scoaterea de la încărcat. `IpReachabilityMonitor.wlan0`: 149 activări, **16m 15s actual partial**. Wakelock kernel `wlan_pm_wake`: 24m 27s; `wlan_wd_wake`: 11m 35s. Aceste durate se pot suprapune și nu se adună.
- În jurnalul Wi-Fi apare la 10:21:09 o asociere respinsă: `DENIED_INSUFFICIENT_BANDWIDTH`. Aceasta confirmă un incident de conectivitate, dar nu stabilește singură cauza tuturor reautentificărilor.
- Fitbit: două activări SyncWorker, aproximativ 08:29:24–08:33:21 și 09:01:22–09:11:20; wakelock agregat efectiv **13m 33s**.
- LibreCR: wakelock-uri efective totale **1m 55s**, inclusiv recuperare BLE **95,956 s / 10 activări**, handshake **16,803 s / 5 activări**, notificări **2,368 s / 128 activări**, complicații **0,052 s / 1 activare**. CPU pe UID: circa **29,1 s**. Există reconectări, dar nu se observă blocări LibreCR lungi și continue.

## Interpretare și limite

Activitatea Wi-Fi este principalul indiciu pentru consumul excesiv, cu o contribuție suplimentară vizibilă de la sincronizarea Fitbit. Seamănă cu anomalia documentată pe 24 octombrie. LibreCR contribuie prin reconectări și actualizări, însă datele nu îl indică drept principalul mecanism de menținere a CPU treaz.

Nu există o măsurare energetică fiabilă pe aplicație: secțiunea Estimated power use raportează zero, iar Discharge 65,2 mAh nu coincide proporțional cu cele 9 puncte afișate. Nu atribuim procente exacte aplicațiilor. Statisticile includ diagnosticul și instalarea corecției Live Updates efectuate anterior în această dimineață. UID shell are deja circa 153 MB trafic Wi-Fi în interval, deci ADB poate contribui semnificativ la activitatea observată; nu este o măsurare de repaus fără intervenții.

Următoarea verificare utilă este un interval de 1–2 ore cu Wi-Fi oprit și Bluetooth păstrat, fără diagnostic ADB pe durata lui, urmat de o singură captură retrospectivă. Nu s-au modificat setările în această sesiune.

Capturi brute: `/tmp/watch-battery-20261108/`: batterystats.txt, morning-history.txt, logcat.txt, power.txt, wifi.txt. Fișierele din /tmp sunt temporare.

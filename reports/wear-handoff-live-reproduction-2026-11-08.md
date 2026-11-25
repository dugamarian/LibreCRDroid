# Reproducere live: ceasul preia, apoi pierde imediat senzorul

8 noiembrie 2026, Pixel 10 Pro XL și Pixel Watch 4. Utilizatorul a plecat fără
telefon și a lăsat aplicația de pe ceas în fundal. Nu s-au injectat citiri și nu
s-au modificat sesiunea, cheile sau configurația Bluetooth pentru reproducere.

## Ce arată logurile dispozitivelor

| Ora locală | Eveniment |
| --- | --- |
| 16:28:52 | Ultima citire nouă primită de telefon și transmisă ceasului. |
| 16:29:21 | Telefonul pierde senzorul: GATT status 147, `CONNECT_TIMEOUT`. |
| 16:31:54 | Ceasul pornește singur managerul BLE din serviciul existent, după pragul de trei minute fără date. Diagnosticul arată `interactive=false`, `idle=true`, importanța procesului 125 (foreground service). |
| 16:32:52 | Conexiunea GATT a ceasului reușește, status 0. |
| 16:32:59 | Ceasul termină reconectarea cu cheia salvată și primește o citire nouă. |
| 16:33:00 | Telefonul cere imediat cedarea; ceasul salvează `autoConnect=false`. Utilizatorul confirmă că este încă departe de telefon. |
| 16:33:04 | Telefonul încearcă senzorul din nou, deși nu mai primise date de la acesta. |

Conexiunea directă telefon–ceas continua să fie marcată drept Bluetooth/nearby.
Aceasta nu dovedea existența legăturii telefon–senzor. Apelul telefonului
`onRemotePacket → reconcilePhonePriority(watchCollecting=true)` confunda prima
citire a ceasului cu revenirea telefonului și ocolea pauza de revenire a ceasului.
Prin urmare, pornirea BLE din fundal a fost demonstrată în această reproducere;
eroarea confirmată este cedarea prematură după prima citire.

## Afișarea vârstei

În timpul reproducerii, utilizatorul a observat și `2′`, deci blocarea la `0′`
nu s-a reprodus constant. Captura inițială avea un timestamp real de numai câteva
secunde și afișa corect `0′`. Nu este demonstrat că Android Studio/ADB provoacă
eroarea; conectarea la depanare și interogările pot influența repausul și trebuie
controlate într-o repetare separată.

Totuși, chipul și imaginea Ongoing Activity conțineau minute relative desenate
static. Dacă aplicația nu poate repicta, acele minute pot rămâne în urmă. Corecția
afișează ora originală `HH:mm` în cele două elemente statice; timestampul
notificării și cronometrul nativ continuă să folosească momentul citirii.

## Corecții pregătite

- O citire nouă a ceasului confirmă proprietatea acestuia și oprește bucla veche
  a telefonului; nu inițiază o preluare forțată înapoi.
- Verificările periodice păstrează un ceas cu date recente sau o revendicare
  de reconectare validă. Un ceas mort/tăcut rămâne recuperabil după termenul de
  lipsă a datelor. Revenirea cerută de ceas și controlul manual rămân disponibile.
- Evenimentele repetate de prezență nu mai resetează pauza revenirii. Numai o
  tranziție observată din absent în apropiere o anulează.
- Intenția unui fallback confirmat nu este anulată doar de repornirea telefonului.
- Interogarea nodului local pentru selecția ceasului este limitată la cinci
  secunde; interogările de prezență își păstrează limita existentă și au mesaje
  explicite pentru timeout/eroare. Deciziile monitorului sunt jurnalizate când
  diagnosticul este activ.
- Chipul și imaginea arată ora citirii, independent de repictarea minutelor.

## Verificări

- Protocol: 184 teste, telefon: 139, Wear: 69; toate trecute, fără teste omise.
- Ambele APK-uri debug și APK-ul de teste Wear au fost compilate.
- Patru teste Android pentru notificări/ceas au trecut pe emulator Wear OS 7;
  testul de standby a trecut separat, inclusiv pornirea BLE din fundal folosind
  un context care refuză orice lansare nouă de serviciu.
- Testul de standby avea o cursă în pregătire: pornea scrieri suspendabile înainte
  să salveze preferințele, iar `BOOT_COMPLETED` putea inițializa aplicația cu modul
  oprit. Preferințele sunt acum pregătite înaintea operațiilor suspendabile.
- Nu se execută teste cu citiri sintetice pe dispozitivele fizice.

Corecțiile sunt în APK-uri; reproducerea descrisă mai sus a folosit versiunea
anterioară. Instalarea pe dispozitive și repetarea fără depanare trebuie evaluate
separat, pentru a nu confunda dovada erorii cu validarea corecției.

Referințe de platformă:
[descoperirea dispozitivelor și nearby](https://developer.android.com/training/wearables/data/discover-devices),
[Doze și testarea repausului](https://developer.android.com/training/monitoring-device-state/doze-standby).

## Continuarea verificării și protecția finală

După prima corecție instalată, ceasul s-a conectat din fundal la 16:49:51 și a
primit date la 16:49:58, 16:51:57 și 16:52:53. Telefonul nu a mai preluat conexiunea
la fiecare citire. La 16:52:57, însă, ceasul însuși a cerut întoarcerea după
expirarea pauzei de trei minute, deși utilizatorul era încă departe. Acesta era
al doilea declanșator al aceleiași reveniri premature.

Protecția finală salvează `returnNeedsReappearance` când telefonul pierde senzorul
dar continuă să apară conectat direct. În această stare, ceasul nu mai cere
revenirea doar pentru că a expirat pauza. O dispariție urmată de reapariție
observată a telefonului, transferul manual sau bateria critică permit revenirea.
O eroare/expirare a interogării de prezență nu fabrică o astfel de tranziție.
Protecția este păstrată și după repornirea ceasului.

Limită explicită: dacă legătura telefon–ceas nu se întrerupe deloc, întoarcerea
fizică într-o cameră nu poate fi dedusă dintr-un nou eveniment Bluetooth. În acest
caz este păstrat ceasul care primește date; ruta manuală rămâne disponibilă.

La reluarea din seara aceleiași zile s-au păstrat modificările mai recente din
workspace pentru eticheta vârstei și alarma Live Updates. Nu s-a revenit la o
versiune mai veche a afișajului. Ambele aplicații au fost reconstruite din sursele
curente și instalate cu păstrarea datelor. Telefonul a primit citiri BLE noi la
20:23:56 și 20:24:53, iar ceasul le-a primit din fundal și a rămas în standby.

Verificarea finală de cod: 188 teste protocol, 70 Wear și 139 telefon, toate
trecute (397 în total), plus APK-uri debug compilate. Cele cinci teste Android
menționate anterior aparțin verificării de după-amiază; protecția finală are
regresii suplimentare în `PhonePriorityPresenceTest`.

Repetarea fizică a plecării și păstrarea conexiunii pe ceas dincolo de trei minute
trebuie confirmate pe versiunea finală; nu sunt demonstrate doar de instalare.

## Proba finală de menținere pe ceas — confirmată

Utilizatorul a repetat plecarea fără telefon, păstrând aplicația ceasului închisă.
Telefonul a pierdut senzorul la **20:37:21**. Prima citire live de la ceas a ajuns
la **20:40:59**. Au urmat citiri live la **20:41:53, 20:42:53, 20:43:52,
20:44:54, 20:45:53, 20:46:52, 20:47:53, 20:48:52 și 20:49:53**. Acestea au fost
mesaje live cu lifeCount în creștere, nu reluări istorice. Telefonul a confirmat
proprietatea ceasului și nu a mai cerut cedarea la prima citire sau după trei
minute. Colectarea din fundal și menținerea rutei pe ceas au fost astfel demonstrate
pe dispozitivele fizice, inclusiv cât conexiunea ADB a ceasului nu răspundea.

Întârzierea măsurată până la prima citire a fost de aproximativ **3 minute și
38 de secunde**. Această probă folosea încă expirarea liveness de trei minute.
Nu demonstrează o preluare instantanee.

## Optimizarea de preluare rapidă — instalată, latență fizică nemăsurată

După probă s-a adăugat mesajul direct `PHONE_CGM_LINK_LOST`, emis doar din calea
validată de deconectare GATT a telefonului când modul automat și colectarea locală
sunt active. Mesajul nu este salvat ca DataItem. Pe ceas, un mesaj recent, de la
telefonul și senzorul corecte, invalidează liveness din cache și trezește o
verificare a stării actuale a telefonului. Un telefon sănătos sau o revendicare
de pornire validă blochează preluarea. În lipsa mesajului se păstrează fallback-ul.

Au fost incluse două protecții suplimentare rezultate din revizie:

- Pragul lifeCount/timp al pierderii este păstrat; un pachet întârziat dinaintea
  pierderii nu poate opri recuperarea ceasului. Parcarea unei tentative locale
  cere atât o citire cu adevărat mai nouă, cât și confirmare live de pe telefon.
- Actualizarea liveness, tratarea mesajului și anularea manuală de pe ceas sunt
  serializate cu același mutex, astfel încât o verificare suspendată să nu anuleze
  ulterior alegerea manuală.

Verificare: **193 teste protocol + 139 telefon + 70 Wear = 402 teste unitare**,
toate trecute. Ambele APK-uri și sursele testelor Android Wear au fost compilate;
ultimul gard de confirmare a revenirii telefonului a trecut și o reconstruire și
rerulare a suitei Wear. `git diff --check` este curat.

Versiunea cu optimizarea a fost instalată pe ambele dispozitive, cu datele și
sesiunea păstrate. După revenirea utilizatorului, prioritatea telefonului a fost
reselectată explicit, deoarece legătura Bluetooth nu se întrerupsese. Telefonul
a primit din nou date BLE la **21:04:56**, iar ceasul a primit acea citire și a
rămas pregătit în fundal. Modul automat a fost lăsat activ.

Utilizatorul a ales să încheie verificarea în acest punct. Prin urmare, **latența
optimizării rapide nu a fost măsurată fizic**. Capturile ADB ale acestui test au
fost oprite; aplicațiile, Bluetooth și serverul ADB au fost păstrate active.

# Revenirea automată de pe ceas pe telefon — 9 noiembrie 2026

Utilizatorul a confirmat că telefonul cedează senzorul, dar ceasul nu îl predă
înapoi. Revizia codului confirmă o condiție fără ieșire: gardul
`returnNeedsReappearance` se elibera numai după o tranziție observată
`Node.isNearby=false → true`. Legătura telefon–ceas poate rămâne activă chiar
când telefonul pierde senzorul; revenirea fizică nu produce atunci acea tranziție.

Corecția înlocuiește gardul permanent cu încercări limitate:

- Ceasul continuă să colecteze până la expirarea unui termen persistent și cere
  revenirea numai dacă are o citire locală recentă și telefonul este în apropiere.
  Prima încercare urmează după trei minute, apoi intervalul crește la
  6/12/24/30 minute dacă transferurile nu reușesc. Revenirea observată prin
  Bluetooth permite o încercare mai devreme.
- Telefonul cere starea și disponibilitatea ceasului pentru recuperare, eliberează
  conexiunea cu confirmare și preia cheia actuală. Numai un pachet BLE local cu
  lifeCount mai mare decât ultima citire a ceasului confirmă transferul.
- Fereastra telefonului este fixă, de 150 secunde, inclusiv după repornire. La
  eșec, telefonul oprește BLE înainte de pregătirea și repornirea ceasului.
  Recuperarea ceasului nu revine automat într-o nouă buclă de conectare pe telefon.
- Ceasul păstrează separat un termen de 180 secunde pentru eliberare, astfel încât
  să poată relua colectarea dacă telefonul sau răspunsul dispare. O revendicare
  de pornire prelungită nu poate bloca această recuperare. Un mesaj întârziat
  după termen cere verificarea stării actuale a telefonului.
- Confirmările și callback-urile repetate nu reînnoiesc termenul și nu șterg
  intervalul de retry. Prima citire pe telefon nu șterge istoricul eșecurilor;
  sunt necesare trei minute de pachete noi continue.
- Ruta manuală, dezactivarea modului automat și excepția de baterie critică
  sunt păstrate. Dezactivarea opțiunii în timpul conectării telefonului păstrează
  receptorul ales.

Verificare finală: **225 teste protocol + 139 telefon + 70 Wear = 434 teste**, fără
eșecuri sau teste omise. Au fost compilate ambele APK-uri și APK-ul de teste
Android Wear. După revizie, suita Wear și APK-ul ceasului au fost reconstruite
pentru gardul final al mesajelor întârziate. `git diff --check` este curat.

Această verificare automată nu demonstrează încă timpul real de revenire prin
radio. Când legătura Bluetooth nu se întrerupe, apropierea fizică de senzor nu
poate fi dedusă din simpla conectivitate Data Layer; încercările sunt periodice,
iar fiecare poate produce o pauză în citiri cât timp se reconectează receptorul.

Telefonul a primit APK-ul nou prin `install -r`; procesul aplicației și serviciul
de colectare foreground au repornit. La **07:23:54 CET**, magazinul aplicației
conținea o citire nouă cu lifeCount 20658, cu `auto_connect=true` și
`watch_takeover=false`. APK-ul anterior este păstrat în `/tmp`.
Conexiunea ADB a ceasului a dispărut înaintea instalării sale; după reactivarea
depanării de către utilizator, APK-ul nou a fost instalat și pe ceas cu
`install -r`, fără deschiderea activității LibreCR Wear. APK-ul anterior al
ceasului este păstrat în `/tmp`.

Jurnalizarea diagnostică era dezactivată inițial. A fost activată temporar din
pagina Log a telefonului și confirmată pe ceas pentru testul fizic. La
**07:27:46 CET**, ceasul avea `released=true`, fără buclă BLE locală, serviciul
automat armat, iar decizia sa era `WAIT` deoarece telefonul colecta sănătos.
Capturile private ale verificării sunt în `/tmp/librecr-*-return-live-20261109.txt`.

## Verificare fizică finalizată

Utilizatorul a plecat fără telefon și a revenit la solicitare, cu aplicația
ceasului închisă și fără schimbarea manuală a rutei:

- **07:32:56 CET, telefon:** pierdere GATT, ultimul lifeCount local 20666,
  mesaj de pierdere trimis.
- **07:32:56–57, ceas:** mesaj validat, verificare live a telefonului, pornire
  BLE acceptată la aproximativ 0,5 secunde de la primirea mesajului, pe același
  ceas. Nu s-a așteptat pragul de trei minute pentru inițierea recuperării.
- **07:33:58, ceas:** prima citire BLE nouă, lifeCount 20668; telefonul confirmă
  fallback-ul și își oprește bucla locală. Ceasul continuă cu lifeCount 20669–20673.
- Prima încercare de revenire a fost refuzată de validarea prea strictă a
  termenului transmis în timp wall-clock. Măsurarea paralelă a timpului a găsit
  telefonul cu aproximativ **646 ms înaintea ceasului**. Această diferență făcea
  ca termenul de 180 secunde să pară cu puțin peste limita admisă.
- Corecția permite diferența de timp în fereastra de validare, dar limitează
  durata locală la maximum 180 secunde și persistă termenul în timpul propriu
  al ceasului. Patru regresii suplimentare acoperă diferențe mici de timp,
  limite, expirare și overflow. Protocolul și Wear au fost retestate, iar
  APK-ul ceasului a fost actualizat. Colectarea lui s-a restaurat în fundal;
  lifeCount 20674 a sosit la **07:39:59**.
- **07:40:10, ceas:** cere și acceptă predarea automată, cu ultimul lifeCount
  20674 și termen local de 180 secunde. Legătura Bluetooth a rămas `nearby=true`
  pe tot parcursul relevant: nu a fost necesară tranziția absent→prezent care
  bloca implementarea anterioară.
- **07:40:14, telefon:** conectare în curs, cu fereastra inițială de 150 secunde
  păstrată, fără prelungire prin callback-uri.
- **07:40:57, telefon:** citire BLE nouă cu lifeCount 20675, strict peste pragul
  ceasului; stare `PHONE_COLLECTING`, `phoneReturnTrial=false`.
- Ceasul primește lifeCount 20675 de la telefon și anulează termenul de
  recuperare. Verificarea preferințelor confirmă `released=true`,
  `priorityArmed=true`, `returnReclaimPending=false`, fără `returnLeaseUntilWall`.
- **07:41:53, telefon:** a doua citire BLE nouă, lifeCount 20676, confirmă
  continuarea colectării după predare.
- **07:42:53, telefon:** următoarea citire BLE, lifeCount 20677, confirmă
  stabilitatea colectării.

Orele sunt cele ale fiecărui dispozitiv; diferența măsurată dintre ele trebuie
avută în vedere la compararea evenimentelor de pe dispozitive diferite.
Testul confirmă preluarea în fundal și revenirea automată când Bluetooth rămâne
conectat. Recuperarea unei încercări de telefon fără succes este acoperită de
testele automate; nu a fost provocată separat în această probă fizică.

Capturile logcat ale testului au fost oprite. Jurnalizarea internă temporară
urmează să fie readusă la starea inițială dezactivată; telefonul era blocat când
s-a încercat accesarea comutatorului. Modul automat rămâne activ.

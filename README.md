# OBD Skaner

Aplikacja na Androida do odczytu danych z auta przez adapter ELM327 (Bluetooth Classic).

Co potrafi:
- łączy się ze sparowanym adapterem i sama wykrywa protokół auta (CAN, K-line, J1850),
- pokazuje dane na żywo: obroty, prędkość, temperatury, obciążenie, przepustnicę itd. (tylko te, które auto obsługuje),
- odczytuje kody błędów: zapisane (03), oczekujące (07) i stałe (0A), z polskimi opisami,
- pokazuje stan kontrolki „check engine”,
- kasuje błędy (04) z ostrzeżeniem,
- odczytuje VIN i napięcie akumulatora,
- zapisuje dziennik surowej komunikacji z adapterem (zakładka „Info”, przycisk „Kopiuj”),
- **Saab 9-3 (2003+) i Ople z siecią GMLAN:** skanuje pozostałe sterowniki na magistrali P-bus
  (ABS/ESP, skrzynia, CIM, MIU…) i odczytuje z nich kody błędów z typem usterki, np. `C0035-01`.

### Skan sterowników GMLAN

Ekran „Błędy” → „Skanuj sterowniki”. Aplikacja:
1. przełącza adapter w tryb surowych ramek CAN (`ATH1`, `ATCAF0`, filtry `ATCF`/`ATCM`),
2. wysyła TesterPresent (`$3E`) na adresy `0x241–0x25F` i `0x7E0–0x7E7` i zapamiętuje, kto odpowiedział,
3. z każdego znalezionego sterownika czyta błędy usługą `$A9 $81` z maską `$12`
   (bieżące + historyczne); lista przychodzi w ramkach UUDT `0x5xx`,
4. resetuje adapter (`ATZ`) i wraca do zwykłego OBD2.

Tylko odczyt – skan niczego nie zapisuje ani nie kasuje. Działa wyłącznie na magistrali P-bus
(CAN 500 kbit/s, piny 6 i 14). Moduły z I-bus (jednoprzewodowa CAN 33 kbit/s: BCM, drzwi, tył
nadwozia) wymagają adaptera z obsługą SW-CAN, np. OBDLink MX+. Skan uruchamia się bez pytania
tylko wtedy, gdy VIN wskazuje auto GM (Saab `YS3`, Opel `W0L`/`W0V`…).

## Pobranie gotowego APK

Każdy push na gałąź `main` buduje aplikację na GitHubie (zakładka **Actions**), uruchamia testy
rdzenia i wystawia plik `obd-skaner.apk` w **Releases**. Najnowsza wersja jest zawsze pod adresem
`https://github.com/<właściciel>/<repozytorium>/releases/latest/download/obd-skaner.apk`.

Przy instalacji telefon zapyta o zgodę na instalowanie aplikacji z nieznanych źródeł. Kolejne wersje
są podpisane tym samym kluczem, więc instalują się jako aktualizacja.

Klucz podpisu nie jest w repozytorium – leży w sekretach GitHub Actions
(**Settings → Secrets and variables → Actions**):

| Sekret | Zawartość |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | plik keystore zakodowany w base64 (alias klucza: `obdskaner`) |
| `SIGNING_PASSWORD` | hasło do keystore i do klucza |

Bez tych sekretów workflow nadal kompiluje aplikację i uruchamia testy, ale nie publikuje APK.

Zrób kopię zapasową pliku keystore i hasła. Bez nich nowa wersja nie zainstaluje się jako
aktualizacja – trzeba będzie odinstalować starą.

## Uruchomienie z Android Studio

1. Zainstaluj aktualne **Android Studio**.
2. `File → Open` i wskaż folder `ObdSkaner`. Poczekaj na synchronizację Gradle.
   Jeśli Studio zaproponuje aktualizację wtyczki Android Gradle, możesz się zgodzić.
3. Na telefonie włącz **Opcje programisty → Debugowanie USB** i podłącz go kablem.
4. Kliknij **Run ▶**.

Wymagania: Android 8.0 lub nowszy, adapter ELM327 na **klasyczny Bluetooth** (nie BLE).

## Pierwsze użycie w aucie

1. Wepnij adapter w gniazdo OBD (zwykle pod kierownicą) i włącz zapłon.
2. Sparuj adapter w ustawieniach Bluetooth telefonu. PIN to zwykle `1234` albo `0000`.
3. Otwórz aplikację, zezwól na dostęp do Bluetooth i wybierz adapter z listy.
4. Pierwsze połączenie może potrwać do kilkunastu sekund – adapter szuka protokołu auta.

Kasowanie błędów najlepiej robić przy włączonym zapłonie i **wyłączonym** silniku –
część aut odrzuca je przy pracującym silniku (aplikacja pokaże wtedy komunikat).

## Struktura

```
obd-core/   czysty Kotlin, bez Androida – cała logika OBD
  transport/  ObdTransport (interfejs), StreamTransport (baza dla Bluetooth/TCP)
  elm/        Elm327 (komendy AT, oczekiwanie na „>”), ResponseParser, ObdProtocol
  obd/        ObdClient (usługi OBD), Pids (wzory), DtcDecoder, DtcDescriptions
  gmlan/      GmlanScanner (skan sterowników Saab/Opel), GmlanCompatibility (sprawdzenie po VIN)
app/        aplikacja Android (Jetpack Compose)
  bluetooth/  BluetoothClassicTransport (profil SPP)
  ui/         ScannerViewModel + ekrany: urządzenia, na żywo, błędy, info
```

Rdzeń nie zależy od Androida, więc można go testować na komputerze z udawanym
adapterem (`FakeElm327`), bez auta:

```
./gradlew :obd-core:test
```

Testy obejmują m.in. auto na CAN z odpowiedziami wieloramkowymi, starsze auto na K-line
ze spacjami w odpowiedziach, zgaszony zapłon, odmowę kasowania, timeout i rozłączenie.

## Ograniczenia i pomysły na dalej

- Tylko adaptery Bluetooth Classic. WiFi wymaga nowej klasy na wzór `BluetoothClassicTransport`
  (gniazdo TCP, zwykle `192.168.0.10:35000`), a BLE – osobnej implementacji przez GATT.
- Nagłówki są wyłączone (`ATH0`), więc przy kilku sterownikach nie widać, który zwrócił dany kod.
- Baza opisów zawiera najczęstsze kody ogólne. Kody producenta (np. P1xxx) dostają tylko opis ogólny.
- Do zrobienia: wybór parametrów na ekranie, wykresy, zapis przejazdu do pliku, dane zamrożonej
  ramki (tryb 02), monitory gotowości (PID 01, bajty B–D).

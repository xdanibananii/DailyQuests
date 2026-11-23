# DailyQuests — kontekst projektu

> Ten plik jest głównym punktem startowym dla każdej nowej sesji pracy nad pluginem. Przed zmianami w kodzie przeczytaj ten plik, `TODO.md`, `CHANGELOG.md` i odpowiednie pliki źródłowe. Kod i konfiguracja są nadrzędne wobec dokumentacji, jeśli występuje rozbieżność.

## Stan projektu

- **Projekt:** DailyQuests — plugin Minecraft.
- **Repozytorium:** [xdanibananii/DailyQuests](https://github.com/xdanibananii/DailyQuests)
- **Wersja kodu:** 1.1.9.
- **Główna technologia:** Java + Paper API.
- **Wersja API:** Paper 26.3 (`26.3.build.242-stable`).
- **Wymagana Java:** 25.
- **Build:** Maven, `mvn -B clean package`.
- **Główna gałąź stabilna:** `main`.
- **Gałąź rozwojowa:** `develop`.
- **Bieżący punkt odniesienia 1.1.9:** commit `de719276df23768ba37b0c8424ba28b7038e4684` (gałąź testowa, z której utworzono `develop`).
- **Baza danych:** SQLite, plik `plugins/DailyQuests/dailyquests.db`.
- **Zależności runtime:** Paper; sterownik SQLite jest dołączany do JAR przez Maven Shade. Plugin nie wymaga Citizens.

## Jak zacząć nową sesję z ChatGPT

Wklej:
> Kontynuujemy DailyQuests. Przeczytaj `PROJECT_CONTEXT.md`, `TODO.md` i `CHANGELOG.md` z repozytorium https://github.com/xdanibananii/DailyQuests. Pracujemy na gałęzi `develop`. Przed kompilacją sprawdź aktualny stan GitHuba.

Nie zakładaj, że historia czatu jest kompletnym źródłem prawdy. Zawsze sprawdź aktualny commit i pliki w repozytorium.

## Architektura kodu

Pakiet główny: `pl.dailyquests`.

| Klasa | Odpowiedzialność |
|---|---|
| `DailyQuestsPlugin.java` | Główna klasa pluginu: inicjalizacja, SQLite, dzienne zadania, losowanie i postęp, komendy, GUI, wyzwania tygodniowe, serie, ranking, wskazówki map i obsługa eventów Bukkit/Paper. |
| `CrateSystem.java` | Gwiazdki, saldo graczy, skrzynka, losowanie i wydawanie nagród. |
| `QuestNpcSystem.java` | NPC Michaił Łomonosow i dzienne kontrakty. |
| `CrateNpcSystem.java` | NPC Strażnik Skarbca i interakcje ze skrzynką. |
| `CartographerNpcSystem.java` | NPC Krzusław Wędrowny, mapy skarbów, aktywne lokalizacje i wskazówki. |
| `FilipNpcSystem.java` | NPC Filip Sznupacz i funkcje wspólnego magazynu. |
| `SnafNpcSystem.java` | NPC Snaf i dzienna zupa/posiłek. |

Aktualnie główna logika jest mocno skupiona w `DailyQuestsPlugin.java`. Nie wykonuj dużego refaktoringu tej klasy przy okazji małej poprawki. Wydzielanie modułów planuj osobno, z zachowaniem działania pluginu i danych graczy.

## Pliki zasobów i konfiguracji

Pliki domyślne znajdują się w `src/main/resources/`; po instalacji użytkownik edytuje ich kopie w `plugins/DailyQuests/`.

- `config.yml` — strefa czasowa, liczba zadań, wygląd GUI, przełączniki Nether/End.
- `tasks.yml` — pula 264 zadań dziennych.
- `npc_quests.yml` — pula 79 kontraktów Michaiła.
- `rewards.yml` — nagrody skrzynki i ich wagi.
- `plugin.yml` — wersja, główna klasa, komenda i uprawnienia.

Domyślnie Nether i End są wyłączone w `config.yml`. Nie zmieniaj tego bez wyraźnego wymagania.

## Najważniejsze zasady funkcjonalne

- Zadania dzienne są indywidualne dla gracza i zapisywane w SQLite.
- Podstawowy zestaw ma 5 zadań; po włączeniu Netheru dochodzą 3 dodatkowe zadania.
- Zadania wymiarowe muszą respektować ustawienia Nether/End; nie mogą trafiać do puli, gdy dany wymiar jest wyłączony.
- Nagrody zadań odbiera się ręcznie w GUI.
- Przerzut pojedynczego zadania kosztuje 5 poziomów doświadczenia.
- Ukończenie całego zestawu daje 250 XP i 5 gwiazdek.
- Kontrakty NPC są indywidualne dla gracza.
- Saldo gwiazdek i postępy są przechowywane trwale w bazie.
- Przy zmianach puli zadań zachowuj kompatybilność istniejących plików YAML graczy i nie nadpisuj ich własnych wpisów.
- Nie kasuj ani nie resetuj bazy danych graczy jako ubocznego efektu aktualizacji.

## Komendy — stan potwierdzony w kodzie

### Gracz
- `/zadania`, `/dq` — panel zadań.
- `/dq weekly` — wyzwanie tygodnia.
- `/dq top` — ranking.
- `/dq gwiazdki` — saldo gwiazdek.
- `/dq skrzynka` — dostęp do skrzynki (w praktyce interakcja jest również powiązana z NPC Strażnika Skarbca).
- `/dq mapa pomoc` i `/dq mapa potwierdz` — prośba o wskazówkę i potwierdzenie zakupu.

### Administrator (`dailyquests.admin`, domyślnie OP)
- `/dq help`, `/dailyquest` — pomoc.
- `/dq roll` — ponowne losowanie zadań dla wszystkich graczy; resetuje dzisiejszy postęp.
- `/dq nether on|off`, `/dq end on|off` — przełączniki wymiarów.
- `/dq reload` — przeładowanie konfiguracji i puli zadań.
- `/dq gwiazdki dodaj|usun|ustaw <gracz> <ilość>`.
- `/dq gwiazdki sprawdz <gracz>`.
- `/dq npc quest|skarbiec|kartograf|filip|snaf postaw|usun|tp`.
- `/dq npc <typ> rename <nazwa>`.
- `/dq kartograf list`, `/dq kartograf tp <id>`, `/dq kartograf wyczysc` (czyszczenie wymaga potwierdzenia).

Przy niejasnościach zawsze sprawdź `showHelp()` i `onCommand()` w aktualnym `DailyQuestsPlugin.java`; dokumentacja może wymagać aktualizacji po zmianie komend.

## Baza danych i bezpieczeństwo danych

W kodzie główna klasa tworzy tabele m.in. `player_tasks`, `player_stats`, `weekly_tasks`, `task_history` i `placed_wood_blocks`. Przed każdą aktualizacją wykonuj kopię całego katalogu `plugins/DailyQuests/`, w szczególności `dailyquests.db`.

**Ważne do sprawdzenia przed kolejnym wydaniem:** w `DailyQuestsPlugin.onEnable()` znajduje się mechanizm znacznika `test-reset-v2.done`. Jeżeli tego pliku nie ma, kod wykonuje `DELETE FROM player_tasks` i dopiero potem tworzy znacznik. Może to wyzerować dzienne zadania przy aktualizacji istniejącej instalacji, która nie ma tego znacznika. Nie usuwaj tego mechanizmu ani nie zmieniaj go pochopnie — najpierw ustal, czy był jednorazowym resetem testowym, a następnie przygotuj bezpieczną migrację bez utraty danych.

W domyślnym `config.yml` występuje `reset-data-on-next-start: true`, ale w aktualnym `DailyQuestsPlugin.java` nie znaleziono odczytu tego klucza. Traktuj go jako prawdopodobnie nieużywaną, mylącą opcję do weryfikacji.

## Standard pracy i wydawania

1. Pracuj na `develop`, chyba że użytkownik wskaże inną gałąź.
2. Przed zmianami sprawdź `git`/GitHub: aktualny HEAD, otwarte PR-y, pliki i wersję.
3. Zmiany trzymaj w małych, opisowych commitach.
4. Nie zmieniaj zachowania funkcji niezwiązanych z zadaniem.
5. Nie usuwaj danych, tabel, konfiguracji ani gałęzi bez wyraźnej potrzeby i planu migracji.
6. Aktualizuj `CHANGELOG.md`, `TODO.md` i dokumentację, gdy zmiana tego wymaga.
7. Przed przekazaniem JAR wymagany jest udany build GitHub Actions oraz potwierdzony artefakt.
8. Nie wysyłaj użytkownikowi komunikatów postępu podczas kompilowania; odpowiedz dopiero, gdy JAR jest gotowy do pobrania.
9. Nie scalaj PR ani nie publikuj GitHub Release bez osobnej zgody użytkownika.

## Wersjonowanie

- Wersja aplikacji jest definiowana w `pom.xml` i musi zgadzać się z `src/main/resources/plugin.yml`.
- Nazwa artefaktu w GitHub Actions ma być zgodna z wersją Maven.
- Przy każdej nowej wersji zaktualizuj te miejsca oraz `CHANGELOG.md`.
- Nie używaj nazwy gałęzi jako źródła numeru wersji.

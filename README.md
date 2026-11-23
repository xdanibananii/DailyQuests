# DailyQuests

**DailyQuests 1.1.9** to plugin Minecraft dla serwerów Paper. Dodaje indywidualne zadania dzienne, kontrakty NPC, system gwiazdek i skrzynek, wyzwania tygodniowe oraz system map skarbów.

Plugin korzysta z Paper API i SQLite. Nie wymaga Citizens ani innych pluginów zewnętrznych.

## Wymagania

- Paper zgodny z API 26.3.
- Java 25.
- SQLite jest dołączone do pliku JAR.

Nie zakładaj zgodności ze starszymi wersjami Paper/Minecraft bez osobnego testu.

## Instalacja i aktualizacja

1. Zatrzymaj serwer.
2. Zrób kopię całego katalogu `plugins/DailyQuests/`, szczególnie `dailyquests.db`.
3. Umieść `DailyQuests-1.1.9.jar` w katalogu `plugins`.
4. Uruchom serwer i sprawdź konsolę pod kątem błędów.

Nie usuwaj bazy `dailyquests.db), jeśli chcesz zachować postępy i dane graczy. Nie aktualizuj działającego serwera bez kopii zapasowej.

## Funkcje

- 5 indywidualnych zadań dziennych dla każdego gracza.
- Po włączeniu Netheru dochodzą 3 dodatkowe zadania.
- Pula 264 zadań obejmująca różne aktywności Minecrafta.
- Zadania Netheru i Endu sterowane niezależnymi przełącznikami.
- Nagrody XP i przedmioty odbierane ręcznie w GUI.
- Bonus za ukończenie całego zestawu: 250 XP i 5 gwiazdek.
- Historia przydzielonych zadań ograniczająca powtórki.
- Wyzwania tygodniowe, serie ukończeń i ranking.
- Gwiazdki i skrzynka z animacją losowania nagród.
- Kontrakty Michaiła Łomonosowa — pula 79 kontraktów.
- NPC Strażnik Skarbca, Krzusław Wędrowny, Filip Sznupacz i Snaf.
- Mapy skarbów, wskazówki do skrzyń i funkcje NPC.
- Konfiguracja zadań, kontraktów i nagród w YAML.

## Komendy gracza

| Komenda / akcja | Opis |
|---|---|
| `/zadania`, `/dq` | Otwiera panel codziennych zadań. |
| `/dq weekly` | Wyświetla wyzwanie tygodnia. |
| `/dq top` | Wyświetla ranking graczy. |
| `/dq gwiazdki` | Sprawdza saldo gwiazdek. |
| `/dq mapa pomoc` | Prosi Krzusława o wskazówkę do aktywnej mapy. |
| `/dq mapa potwierdz` | Potwierdza zakup wskazówki za 5 poziomów XP. |
| Kliknięcie NPC | Otwiera funkcje danego NPC. |

W GUI można odebrać ukończone nagrody. Przerzut pojedynczego zadania kosztuje 5 poziomów doświadczenia.

## Komendy administratora

Wymagane uprawnienie: `dailyquests.admin` (domyślnie OP).

| Komenda | Opis |
|---|---|
| `/dq help` lub `/dailyquest` | Wyświetla pomoc. |
| `/dq roll` | Losuje zadania ponownie wszystkim graczom i zeruje dzisiejszy postęp. |
| `/dq nether on|off` | Włącza/wyłącza dodatkowe zadania Netheru. |
| `/dq end on|off` | Włącza/wyłącza zadania z Endu. |
| `/dq reload` | Przeładowuje konfigurację i pulę zadań. |
| `/dq gwiazdki dodaj <gracz> <ilość>` | Dodaje gwiazdki. |
| `/dq gwiazdki usun <gracz> <ilość>` | Odejmuje gwiazdki. |
| `/dq gwiazdki ustaw <gracz> <ilość>` | Ustawia saldo gwiazdek. |
| `/dq gwiazdki sprawdz <gracz>` | Sprawdza saldo gracza. |
| `/dq npc <quest|skarbiec|kartograf|filip|snaf> <postaw|usun|tp>` | Zarządza NPC. |
| `/dq npc <typ> rename <nazwa>` | Zmienia nazwę NPC. |
| `/dq kartograf list` | Wyświetla aktywne mapy. |
| `/dq kartograf tp <id>` | Teleportuje do wskazanej mapy. |
| `/dq kartograf wyczysc` | Czyści aktywne wpisy map (wymaga potwierdzenia). |

## Konfiguracja

Pliki znajdują się w `plugins/DailyQuests/`.

### `config.yml`

- `timezone` — strefa czasowa resetu, domyślnie `Europe/Warsaw`.
- `daily-tasks` — podstawowa liczba zadań.
- `gui-title`, `gui-size` — ustawienia GUI.
- `dimensions.nether-enabled` — zadania Netheru (domyślnie wyłączone).
- `dimensions.end-enabled` — zadania Endu (domyślnie wyłączone).

### `tasks.yml`

Pula zadań dziennych. Każde zadanie ma unikalny identyfikator i pola takie jak:

- `name`, `description` — nazwa i opis.
- `type` — typ aktywności, np. `BREAK`, `KILL`, `CRAFT`, `FISH`, `WALK`, `SWIM`, `EAT`, `ENCHANT`, `BREED`, `TRADE`, `ANVIL`.
- `target`, `amount` — cel i wymagany postęp.
- `reward-xp`, `reward-item`, `reward-item-amount` — nagroda.
- `difficulty`, `dimension` — poziom trudności i opcjonalny wymiar.

Po zmianach użyj `/dq reload`. Zachowuj unikalne identyfikatory. Nie usuwaj istniejących wpisów graczy bez uzgodnienia.

### `npc_quests.yml`

Pula kontraktów Michaiła. Pola obejmują `name`, `description`, `item`, `amount`, `reward-stars` i `reward-xp`.

### `rewards.yml`

Nagrody skrzynki. Wpisy definiują m.in. `tier`, `weight`, `material`, `amount`, `name`, `lore` i opcjonalne zaklęcia. Wagi są względne.

## Dane i kopie zapasowe

- `dailyquests.db` — baza SQLite z postępami, statystykami, gwiazdkami i innymi danymi graczy.
- `tasks.yml`, `npc_quests.yml`, `rewards.yml` — konfiguracje zawartości.
- Pliki stanu NPC/map są przechowywane w katalogu pluginu.

Przed aktualizacją wykonaj kopię całego katalogu `plugins/DailyQuests/`. Nie edytuj bazy SQLite przy uruchomionym serwerze.

## Dokumentacja dla osób rozwijających plugin

- [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md) — aktualny stan, architektura i zasady projektu.
- [TODO.md](TODO.md) — backlog i notatki audytowe.
- [CHANGELOG.md](CHANGELOG.md) — historia wersji.
- [CONTRIBUTING.md](CONTRIBUTING.md) — zasady pracy i kompilacji.

Przy rozpoczynaniu nowej sesji deweloperskiej zacznij od `PROJECT_CONTEXT.md`.

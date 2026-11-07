# DailyQuests

**DailyQuests 1.0.0** to samodzielny plugin do Paper, który dodaje indywidualne codzienne zadania, kontrakty u Mistrza Zleceń, system gwiazdek i skrzynek z nagrodami.

Plugin opiera się na **Paper API** i SQLite. Nie wymaga Citizens ani żadnego innego dodatkowego pluginu.

## Wymagania

- Serwer **Paper** zgodny z API **26.3**.
- **Java 25**.
- Brak wymaganych pluginów zewnętrznych.
- SQLite jest dołączone do pliku JAR.

> Projekt jest budowany pod Paper 26.3 i Java 25. Nie należy zakładać zgodności ze starszymi wersjami Paper/Minecraft.

## Instalacja

1. Zatrzymaj serwer.
2. Pobierz `DailyQuests-1.0.0.jar` z artefaktów GitHub Actions.
3. Umieść plik JAR w katalogu `plugins`.
4. Uruchom serwer.
5. Plugin utworzy katalog `plugins/DailyQuests/` z plikami konfiguracyjnymi i bazą danych.

Po aktualizacji zalecana jest kopia zapasowa całego katalogu `plugins/DailyQuests/`. Nie usuwaj pliku `dailyquests.db`, jeśli chcesz zachować postępy, gwiazdki i kontrakty graczy.

## Funkcje

- 5 indywidualnych zadań dziennych dla każdego gracza.
- Zadania z różnych aktywności: kopanie, walka, crafting, łowienie, chodzenie, pływanie, jedzenie, zaklinanie, rozmnażanie zwierząt, handel i kowadło.
- Postęp zadań zapisywany w SQLite.
- Nagrody XP i przedmioty, odbierane ręcznie w GUI.
- Bonus za ukończenie wszystkich 5 zadań: **250 XP i 5 gwiazdek**.
- Historia zadań ograniczająca szybkie powtórki.
- Wyzwanie tygodniowe, serie ukończeń i ranking.
- Gwiazdki i skrzynka z animowaną ruletką nagród.
- Mistrz Zleceń – Villager z indywidualnym kontraktem dziennym dla gracza.
- Konfiguracja puli zadań, kontraktów i nagród w plikach YAML.
- Opcjonalne zadania z Netheru i Endu.

## Komendy gracza

| Komenda | Opis |
|---|---|
| `/zadania` lub `/dq` | Otwiera panel codziennych zadań. |
| `/dq weekly` | Wyświetla wyzwanie tygodnia. |
| `/dq top` | Wyświetla ranking graczy. |
| `/dq skrzynka` | Otwiera skrzynkę za 20 gwiazdek. |
| `/dq gwiazdki` | Sprawdza własne saldo gwiazdek. |
| Kliknięcie Mistrza Zleceń | Otwiera dialog i dzienny kontrakt NPC. |

W panelu zadań można odebrać ukończone nagrody. Przerzut jednego zadania jest dostępny z poziomu GUI i kosztuje **5 poziomów doświadczenia**.

## Komendy administratora

Wszystkie poniższe komendy wymagają uprawnienia `dailyquests.admin` (domyślnie operator serwera).

| Komenda | Opis |
|---|---|
| `/dq help` | Wyświetla pomoc. |
| `/dq roll` | Losuje ponownie dzienne zadania wszystkim graczom; zeruje dzisiejszy postęp. |
| `/dq nether on` / `/dq nether off` | Włącza lub wyłącza zadania z Netheru i losuje zadania ponownie. |
| `/dq end on` / `/dq end off` | Włącza lub wyłącza zadania z Endu i losuje zadania ponownie. |
| `/dq reload` | Przeładowuje główną konfigurację i pulę zadań. |
| `/dq npc postaw` | Stawia Mistrza Zleceń w miejscu administratora. |
| `/dq npc usun` | Usuwa Mistrza Zleceń. |
| `/dq npc tp` | Teleportuje administratora do NPC. |
| `/dq gwiazdki dodaj <gracz> <ilość>` | Dodaje graczowi gwiazdki. |
| `/dq gwiazdki usun <gracz> <ilość>` | Odejmuje graczowi gwiazdki. |
| `/dq gwiazdki ustaw <gracz> <ilość>` | Ustawia saldo gwiazdek. |
| `/dq gwiazdki sprawdz <gracz>` | Sprawdza saldo wskazanego gracza. |

## Konfiguracja

Pliki znajdują się w `plugins/DailyQuests/`.

### `config.yml`

- `timezone` – strefa czasowa resetu, domyślnie `Europe/Warsaw`.
- `dimensions.nether-enabled` – czy losować zadania z Netheru.
- `dimensions.end-enabled` – czy losować zadania z Endu.

### `tasks.yml`

Pula zadań dziennych. Każde zadanie ma własny identyfikator oraz pola:

- `name` – nazwa wyświetlana (obsługuje kolory `&`).
- `description` – opis.
- `type` – typ zadania, np. `BREAK`, `KILL`, `CRAFT`, `FISH`, `WALK`, `SWIM`, `EAT`, `ENCHANT`, `BREED`, `TRADE`, `ANVIL`.
- `target` – materiał/typ celu lub `ANY`.
- `amount` – wymagany postęp.
- `reward-xp` – nagroda doświadczenia.
- `reward-item` i `reward-item-amount` – opcjonalna nagroda przedmiotowa.
- `difficulty` – oznaczenie poziomu trudności.
- `dimension` – opcjonalnie `OVERWORLD`, `NETHER` lub `THE_END`.

Przykład:

```yaml
tasks:
  collect_emeralds:
    name: "&aSzmaragdowy interes"
    description: "&7Zdobądź 8 szmaragdów"
    type: BREAK
    target: EMERALD_ORE
    amount: 8
    difficulty: MEDIUM
    reward-xp: 200
    reward-item: EMERALD
    reward-item-amount: 2
```

Po zmianach w `tasks.yml` użyj `/dq reload`. Zachowuj unikalne identyfikatory zadań. Zadania z Netheru i Endu będą losowane dopiero po włączeniu odpowiedniego wymiaru.

### `npc_quests.yml`

Pula kontraktów Mistrza Zleceń. Każdy kontrakt określa:

- `name` i `description` – nazwę i opis.
- `item` – wymagany materiał.
- `amount` – wymaganą liczbę przedmiotów.
- `reward-stars` – liczbę gwiazdek.
- `reward-xp` – nagrodę XP.

Kontrakt jest indywidualny dla gracza i odnawia się wraz z nowym dniem według ustawionej strefy czasowej.

### `rewards.yml`

Lista nagród skrzynki. Każda pozycja posiada m.in. `tier`, `weight`, `material`, `amount`, `name`, `lore` i opcjonalne `enchants`. Wagi są względne – większa wartość oznacza większą szansę wylosowania nagrody.

## Dane i kopie zapasowe

- `dailyquests.db` – baza SQLite: zadania, postęp, statystyki, saldo gwiazdek i kontrakty.
- `quest_npc.yml` – zapis lokalizacji i identyfikatora Mistrza Zleceń.
- `tasks.yml`, `npc_quests.yml`, `rewards.yml` – konfiguracje zawartości.

Przed aktualizacją lub ręczną edycją plików wykonaj kopię całego katalogu `plugins/DailyQuests/`. Nie edytuj bazy SQLite przy uruchomionym serwerze.

## Uprawnienia

| Uprawnienie | Domyślnie | Opis |
|---|---|---|
| `dailyquests.admin` | `op` | Dostęp do komend administracyjnych. |

## Wydanie

**DailyQuests 1.0.0** – oficjalna linia wydania 1.0. Aktualizacje poprawkowe w ramach tej linii zachowują numer wersji pluginu.


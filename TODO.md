# DailyQuests — TODO i notatki audytowe

Lista jest żywym backlogiem. Po wykonaniu zadania aktualizuj status i dopisz wersję/commit.

## Priorytet 0 — bezpieczeństwo danych

- [ ] **Sprawdzić mechanizm `test-reset-v2.done`.** W `DailyQuestsPlugin.onEnable()` brak znacznika powoduje `DELETE FROM player_tasks`. Ustalić, czy obecne instalacje mogą utracić postęp przy aktualizacji.
- [ ] Przygotować bezpieczną migrację/zmianę tego mechanizmu, jeśli okaże się testowy. Nie kasować danych istniejących graczy.
- [ ] Zweryfikować i usunąć lub poprawnie obsłużyć nieużywany wpis `reset-data-on-next-start: true` w domyślnym `config.yml`.

## Priorytet 1 — stabilność i testy

- [ ] Dodać testy jednostkowe dla losowania zadań, filtrów wymiarów, nagród i migracji SQLite.
- [ ] Przygotować checklistę testów na serwerze Paper 26.3: nowa instalacja, aktualizacja ze starszej wersji, restart, reset doby, Nether/End, GUI, NPC i nagrody.
- [ ] Zweryfikować zachowanie wszystkich NPC po restarcie i po zmianie nazwy/pozycji.
- [ ] Zweryfikować, czy README i help komend są zgodne z aktualnym kodem.

## Priorytet 2 — utrzymanie kodu

- [ ] Rozważyć stopniowe wydzielanie logiki z `DailyQuestsPlugin.java` do osobnych serwisów (zadania, tygodniówki, GUI, baza danych, komendy). Każdy etap osobno i z buildem.
- [ ] Dodać warstwę dostępu do SQLite / migracje schematu zamiast rozbudowywać SQL bezpośrednio w głównej klasie.
- [ ] Dodać walidację YAML przy starcie i czytelne komunikaty o błędnych wpisach.
- [ ] Ujednolicić nazewnictwo i formatowanie kodu bez masowych zmian niezwiązanych z funkcjonalnością.

## Notatki z audytu 1.1.9

- [x] Build Maven/GitHub Actions dla commita `de719276df23768ba37b0c8424ba28b7038e4684` zakończony sukcesem.
- [x] Artefakt `DailyQuests-1.1.9` został wygenerowany.
- [x] Potwierdzono 264 wpisy puli dziennych zadań w `tasks.yml`.
- [x] Potwierdzono 79 kontraktów w `npc_quests.yml` (starsza dokumentacja README mówiła o 59).
- [x] Nether i End domyślnie wyłączone w `config.yml`.
- [ ] Nie przeprowadzono jeszcze testów funkcjonalnych na żywym serwerze Minecraft; udany build potwierdza kompilację, nie poprawność wszystkich mechanik.

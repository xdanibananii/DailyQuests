# Praca nad DailyQuests

## Środowisko

- Java 25.
- Maven.
- Paper API 26.3.
- IDE z obsługą Maven (np. IntelliJ IDEA).

## Kompilacja lokalna

```bash
mvn -B clean package
```

Gotowy plik powinien znajdować się w:

```
target/DailyQuests-<wersja>.jar
```

## Gałęzie

- `main` — stabilna linia; nie commitujemy tu bezpośrednio.
- `develop` — integracja bieżących prac i kandydatów do kolejnego wydania.
- `feature/<krótki-opis>` — większa nowa funkcja.
- `fix/<krótki-opis>` — poprawka błędu.
- `release/<wersja>` — opcjonalna gałąź przygotowania wydania.

Nie usuwaj historycznych gałęzi automatycznie. Przed porządkowaniem sprawdź, czy zawierają unikalne commity lub otwarte PR-y.

## Zasady zmian

1. Najpierw odtwórz problem i wskaż pliki, które go dotyczą.
2. Wprowadzaj możliwie mały zakres zmian.
3. Zachowuj kompatybilność SQLite i plików YAML.
4. Nie resetuj danych graczy i nie zmieniaj domyślnych ustawień wymiarów bez uzgodnienia.
5. Przy zmianie wersji zsynchronizuj `pom.xml`, `plugin.yml`, workflow i `CHANGELOG.md`.
6. Aktualizuj `PROJECT_CONTEXT.md` tylko wtedy, gdy zmienia się architektura, zasady lub istotny stan projektu.
7. Po zmianach uruchom build i sprawdź artefakt.
8. Opis commita powinien mówić, co faktycznie zmieniono.

## Pull Request

PR powinien zawierać:
- cel zmiany,
- najważniejsze modyfikacje,
- ryzyko dla danych/konfiguracji,
- wynik builda,
- ręczne testy, które wykonano i których nie wykonano.

Nie scalaj PR ani nie twórz publicznego wydania bez zgody właściciela projektu.

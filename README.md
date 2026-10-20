# Drive Monitor

Aplikacja na Androida (13+), która sama zaczyna nagrywać jazdę po połączeniu telefonu
z Bluetooth samochodu, zapisuje ślad GPX z prędkością i ograniczeniem w każdym punkcie,
a po rozłączeniu kończy trasę i liczy statystyki bezpieczeństwa.

## Budowanie

- **GitHub Actions:** każdy push do `main` buduje APK (`.github/workflows/build.yml`).
  Gotowy plik: zakładka **Actions** → ostatni przebieg → **Artifacts** → `DriveMonitor-debug`.
- **Android Studio:** *File → Open* → folder projektu → *Run*. Z wiersza poleceń: `gradlew assembleDebug`.

APK jest podpisany stałym kluczem `app/debug.keystore`, więc nowe wersje instalują się
jako aktualizacja i trasy zostają w telefonie. Klucz jest jawny – trzymaj repozytorium jako prywatne.

## Historia wersji

- **1.2** – „Popraw ▾” otwiera duże znaki ograniczeń (20–140 km/h, w tym 100 i 140) w siatce 3×3.
- **1.1** – „Popraw ▾”: poprawka zmienia znak natychmiast (także na postoju); notatki do OSM
  w kolejce z prawdziwym wynikiem wysyłki (nr notatki albo powód odrzucenia), ponawianie
  bez zasięgu; Ustawienia → Moje zgłoszenia (status, link do notatki, ponów, usuń poprawkę).
- **1.0** – żywa mapa trwającej jazdy na ekranie głównym; rozwijany przycisk „Popraw ▾”
  (20/30/40/50/70/90/120 km/h) – poprawka limitu zapisywana dla całej drogi OSM i opcjonalnie
  wysyłana jako anonimowa notatka do OpenStreetMap; ekran **Pomiary** (⏱): g-metr
  (hamowanie, przyspieszenie, zakręty, autokalibracja ułożenia telefonu), automatyczne 0–100
  i 100–150 km/h, Vmax sesji z resetem, własne odcinki pomiarowe z najlepszymi czasami.
- **0.9** – ekran główny sam wykrywa start i koniec jazdy; karta „Ta jazda” na żywo:
  prędkość, znak ograniczenia w prawym górnym rogu (ze znaku OSM / wg typu drogi), czas,
  dystans, km w limicie / ponad / bez limitu, średnia i maks. prędkość; stabilizacja
  ograniczenia (bez skakania na skrzyżowaniach); po jeździe – podsumowanie ostatniej jazdy;
  nowy ekran **Statystyki** (7 dni / 30 dni / rok / wszystko, km dziennie/miesięcznie, rekordy).
- **0.8** – natywna mapa MapLibre Android zamiast mapy w przeglądarce (na części telefonów
  WebGL w WebView rysował czarny ekran): trasa, auto, kamera 3D z pochyleniem i obrotem wokół
  auta, profil, odtwarzanie; podgląd na ekranie głównym jako zdjęcie mapy; mapa offline przez
  regiony offline MapLibre. Relief terenu 3D niedostępny w natywnym MapLibre – profil wysokości zostaje.
- **0.7** – biblioteka mapy wbudowana w APK (pobierana przy budowaniu), pamięć mapy w telefonie
  (styl, kafelki, teren), pobieranie mapy offline (okolica tras / 25 km) z instrukcją
  w ustawieniach, podgląd ostatniej trasy na prawdziwej mapie na ekranie głównym.
- **0.6** – naprawa ekranu trasy (błąd rysowania przed nadaniem rozmiaru blokował mapę),
  podgląd jazdy na żywo (prędkość, znak ograniczenia, czas, km w limicie / ponad),
  statystyki w kilometrach w limicie i ponad limitem z paskiem kategorii, czas w h/min/s,
  trasa rysowana po drogach (dopasowanie do OSM) i bez „gwiazdek” z postojów,
  postoje nie są zapisywane do GPX co sekundę.
- **0.5** – nowy ekran główny: karta statusu, kafelki statystyk, ostatnia trasa z dużym
  podglądem (dotknięcie otwiera pełną mapę z odtwarzaniem), lista tras z miniaturami;
  uprawnienia i ustawienia w menu ⚙; usuwanie pojedynczej trasy (menu ⋮) i wszystkich tras.
- **0.4** – ekran trasy działa zawsze: dane trasy przekazywane jako plik (bez mostka JS),
  widok uproszczony z odtwarzaniem i profilem, gdy mapa z internetu jest niedostępna,
  komunikat o stanie mapy.
- **0.3** – naprawa uszkodzonych GPX (także tych zapisanych wcześniej), eksport zawsze
  poprawnego pliku, odzyskiwanie przerwanych tras ze statystykami, zapasowy serwer mapy
  i komunikaty błędów zamiast pustego ekranu, ikona aplikacji, Gradle wrapper.
- **0.2** – limity prędkości pobierane automatycznie, wybór auta z listy sparowanych,
  zwolnienie z optymalizacji baterii, stały klucz podpisu.
- **0.1** – pierwsza wersja.

## Jak to działa

```
Bluetooth auta łączy się ──► CarPresenceService (Companion Device Manager)
                         └─► BluetoothReceiver (zapasowo, ACL_CONNECTED)
                                     │
                                     ▼
                         TripRecorderService (usługa pierwszoplanowa)
                           GPS co 1 s ─► SpeedLimitProvider (OSM offline)
                                     ├─► GpxWriter  → trips/*.gpx
                                     └─► TripStats  → trips/*.json
Bluetooth rozłączony ──► 60 s tolerancji ──► zapis i wyłączenie
```

- **Companion Device Manager** (opcjonalnie) to systemowe powiązanie telefonu z zestawem Bluetooth auta.
  Dzięki niemu Android sam budzi aplikację (nawet gdy jest zamknięta) i pozwala
  wystartować usługę z GPS w tle, co od Androida 12 jest normalnie zablokowane.
- **60 s tolerancji** przy rozłączeniu – radio często zrywa połączenie przy odpalaniu silnika.
- Trasy krótsze niż 300 m są odrzucane (np. sam postój z włączonym radiem).

## Pierwsze uruchomienie

1. Sparuj telefon z autem w ustawieniach Bluetooth (jeśli jeszcze nie jest).
2. W aplikacji otwórz ⚙ Ustawienia i przejdź 4 kroki: uprawnienia, lokalizacja „Zawsze”, wybór auta z listy
   sparowanych urządzeń, zwolnienie z optymalizacji baterii.
3. Na Samsungu dodatkowo: Ustawienia → Aplikacje → Drive Monitor → Bateria → **Bez ograniczeń**,
   inaczej system może zamknąć nagrywanie w trakcie jazdy.

## Ograniczenia prędkości

Aplikacja sama pobiera limity z OpenStreetMap (Overpass API) dla okolicy, w której jedziesz
(kafelki ok. 3 × 2 km, bieżący i 1,5 km przed autem), i zapisuje je w telefonie.
Raz przejechany obszar działa potem bez internetu; dane odświeżają się po 90 dniach.

### Opcjonalnie: baza offline dla całego kraju

Przydaje się, gdy często jeździsz bez zasięgu. Zbuduj ją na komputerze i w aplikacji
wybierz **Importuj bazę offline**:

```bash
pip install osmium
wget https://download.geofabrik.de/europe/poland-latest.osm.pbf
python3 tools/build_speed_db.py poland-latest.osm.pbf speedlimits.sqlite
```

Dla całej Polski skrypt potrzebuje kilku GB RAM i kilkunastu minut. Dla testów wystarczy
wyciąg województwa. Gdy droga nie ma tagu `maxspeed`, aplikacja przyjmuje domyślny limit
dla typu drogi (w GPX widać to jako `limitSrc=default:…`).

## Ekran trasy

Dotknij trasy na liście, żeby otworzyć mapę:

- linia pokolorowana według przekroczeń (zielony → żółty → pomarańczowy → czerwony → fiolet),
- profil wysokości z paskiem przekroczeń pod spodem; przesuń po nim palcem, żeby przewinąć trasę,
- **▶** animacja przejazdu ×10…×300: kamera jedzie za autem w 3D, z rzeźbą terenu
  (przewyższenie ×1,5) i cieniowaniem; przycisk **3D** przełącza na widok płaski,
- w trakcie animacji: 1 palec w bok obraca kamerę wokół auta, w górę/dół zmienia nachylenie,
  2 palce przybliżają; kamera dalej jedzie za autem, **⟲ Za autem** wraca do widoku zza auta.
  Na pauzie mapa działa normalnie (przesuwanie, obrót i nachylenie dwoma palcami),
- HUD z prędkością, znakiem ograniczenia i wysokością n.p.m.,
- **Pobierz GPX** zapisuje plik w `Pobrane/DriveMonitor`, **Udostępnij** otwiera systemowe okno udostępniania.

Podkład mapy wymaga internetu; bez niego trasa, profil i odtwarzanie działają w widoku uproszczonym. Używa MapLibre GL JS, kafelków OpenFreeMap (OSM) i terenu
z AWS Terrain Tiles – wszystko darmowe, bez kluczy API.

Wysokość: na Androidzie 14+ zapisywana jest wysokość n.p.m. (wbudowany model geoidy).
Na starszych to surowa wysokość GPS nad elipsoidą, w Polsce o ok. 30–40 m większa.

## Mapa offline

Ustawienia → **Mapa offline**: „Pobierz okolicę moich tras” albo „Pobierz 25 km wokół mnie”.
Pobierana jest mapa do zbliżenia 14, ikony, czcionki z polskimi znakami i teren 3D do zbliżenia 12
(okolica 25 km to zwykle 50–150 MB). Wszystko, co obejrzysz na mapie, też zostaje w telefonie.

## Gdzie są dane

`Android/data/pl.drivemonitor/files/trips/` – pliki `.gpx` (otwierają się w każdym
programie do map) i `.json` ze statystykami trasy.

## Znane ograniczenia i dalsze kroki

- Dopasowanie do drogi jest uproszczone (najbliższy odcinek + kierunek). Na wiaduktach
  i drogach serwisowych obok autostrady może się mylić. Następny krok: map matching
  GraphHopper na całym śladzie po zakończeniu trasy.
- Limity ze znaków zmiennych, stref czasowych i robót drogowych nie są w OSM.
- Ostre hamowania liczone z prędkości GPS (próg −3,5 m/s²); dokładniej byłoby
  z akcelerometru.
- Animacji nie da się jeszcze wyeksportować do pliku wideo.

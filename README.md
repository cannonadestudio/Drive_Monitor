# Drive Monitor

Aplikacja na Androida (13+), która sama zaczyna nagrywać jazdę po połączeniu telefonu
z Bluetooth samochodu, zapisuje ślad GPX z prędkością i ograniczeniem w każdym punkcie,
a po rozłączeniu kończy trasę i liczy statystyki bezpieczeństwa.

## Budowanie

Każdy push do `main` buduje APK w GitHub Actions (`.github/workflows/build.yml`).
Gotowy plik: zakładka **Actions** → ostatni przebieg → **Artifacts** → `DriveMonitor-debug`.

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

- **Companion Device Manager** to systemowe powiązanie telefonu z zestawem Bluetooth auta.
  Dzięki niemu Android sam budzi aplikację (nawet gdy jest zamknięta) i pozwala
  wystartować usługę z GPS w tle, co od Androida 12 jest normalnie zablokowane.
- **60 s tolerancji** przy rozłączeniu – radio często zrywa połączenie przy odpalaniu silnika.
- Trasy krótsze niż 300 m są odrzucane (np. sam postój z włączonym radiem).

## Pierwsze uruchomienie

1. Sparuj telefon z autem w ustawieniach Bluetooth (jeśli jeszcze nie jest).
2. W aplikacji przejdź kroki 1–3: uprawnienia, lokalizacja „Zawsze”, wybór auta.
3. Ustawienia telefonu → Aplikacje → Drive Monitor → Bateria → **Bez ograniczeń**.
   Na Xiaomi/Huawei/Samsungu to konieczne, inaczej system może ubić usługę.

## Baza ograniczeń prędkości

```bash
pip install osmium
wget https://download.geofabrik.de/europe/poland-latest.osm.pbf
python3 tools/build_speed_db.py poland-latest.osm.pbf speedlimits.sqlite
adb push speedlimits.sqlite /sdcard/Android/data/pl.drivemonitor/files/
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

Mapa wymaga internetu. Używa MapLibre GL JS, kafelków OpenFreeMap (OSM) i terenu
z AWS Terrain Tiles – wszystko darmowe, bez kluczy API.

Wysokość: na Androidzie 14+ zapisywana jest wysokość n.p.m. (wbudowany model geoidy).
Na starszych to surowa wysokość GPS nad elipsoidą, w Polsce o ok. 30–40 m większa.

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

> Ranija verzija aplikacije. Ove upute i rezultati ne pripadaju završnoj short-v3 seriji. Putanju lokalnog JDK-a prilagodite svom računaru.

# Vodič za pokretanje preloaded benchmark testova

Ovaj projekat je dodatni benchmark za poređenje Apache Cassandre i ScyllaDB nad unaprijed popunjenom bazom. 
Za razliku od osnovnog benchmarka, početni skup od 1.000.000 zapisa ovdje se učitava samo jednom, 
a zatim se koristi u svim narednim mjerenjima.

Seedovanje baze služi samo kao priprema i njegovo trajanje se ne računa kao rezultat testa.

## 1. Šta je potrebno

Prije pokretanja treba imati:

- Windows i PowerShell
- Docker Desktop
- Java JDK 21
- Maven Wrapper, koji je već u projektu (`mvnw.cmd`)
- generisani dataset od 50 miliona zapisa
- Docker Compose konfiguracije za Cassandru i ScyllaDB

Očekivani raspored direktorija je:

```text
C:\cassandra-scylla-benchmark\
├── benchmark-app-preloaded-v1\
├── data-generator\
│   └── output\
│       └── telemetry-50m\
├── cassandra\
│   ├── docker-compose.yml
│   └── docker-compose.scale-1.yml
└── scylladb\
    ├── docker-compose.yml
    └── docker-compose.scale-1.yml
```

Ako se raspakovani projekat zove drugačije, to nije problem. Važno je samo da putanje do dataseta i Docker Compose 
datoteka odgovaraju putanjama koje koriste konfiguracije i skripte.

## 2. Raspakivanje projekta

Raspakovati `benchmark-app-preloaded-v1.zip`, a zatim otvoriti PowerShell u korijenu projekta:

```powershell
cd C:\cassandra-scylla-benchmark\Ranija_mjerenja_i_grafikoni\benchmark-app-preloaded-v1
```

Sve naredne komande u ovom vodiču pokreću se iz tog direktorija.

## 3. Provjera Jave i projekta

Skripte trenutno očekuju JDK na ovoj lokaciji:

```text
C:\Users\HP\.jdks\temurin-21.0.12
```

Provjera verzija:

```powershell
java -version
.\mvnw.cmd -version
```

Prije prvog testa korisno je provjeriti da se projekat ispravno kompajlira:

```powershell
.\mvnw.cmd test
```

Projekt iz dostavljenog ZIP-a prolazi ovu provjeru: test se završava porukom `BUILD SUCCESS`. Mockito upozorenje 
koje se može pojaviti nije greška i ne prekida test.

Ako se projekat pokreće na drugom računaru ili je JDK instaliran na drugoj lokaciji, potrebno je promijeniti 
vrijednost `$javaHome` u skripti `scripts\run-preloaded-test.ps1`.

## 4. Dataset

Preloaded profili koriste prvih 1.000.000 zapisa iz deterministički generisanog dataseta od 50 miliona zapisa sa seedom 42.

Očekivana lokacija je:

```text
C:\cassandra-scylla-benchmark\data-generator\output\telemetry-50m
```

Ako dataset već postoji, ne treba ga ponovo generisati.

Ako ne postoji, iz direktorija `data-generator` pokrenuti generator koji je korišten i za osnovni benchmark:

```powershell
python generate_dataset.py --records 50000000 --chunk-size 1000000 --devices 10000 --seed 42
```

Prije benchmarka treba provjeriti da su CSV datoteke zaista kreirane u direktoriju `output\telemetry-50m`.

## 5. Kako je preloaded test organizovan

Za svaki test koriste se tri tabele:

- `telemetry_baseline` sadrži unaprijed učitanih 1.000.000 zapisa i ne briše se prije svakog mjerenja;
- `telemetry_workload` koristi se za write, mixed i sustained workload i prazni se prije svakog warm-upa i mjerenja;
- `preloaded_baseline_registry` čuva oznaku da je baseline uspješno pripremljen i sprečava slučajno ponovno seedovanje.

Prilikom prvog pokretanja određenog profila aplikacija će pripremiti baseline. Kod narednih pokretanja isti baseline 
se ponovo koristi.

Ako je seedovanje prekinuto, marker se neće upisati. Profil se tada može ponovo pokrenuti: 
isti deterministički zapisi će se sigurno ponovo upisati.

## 6. Dostupni profili

| Profil | Baza | Topologija | Keyspace | RF | Konzistentnost |
|---|---|---:|---|---:|---|
| `preloaded-cassandra-1m` | Cassandra | 3 čvora | `preloaded_benchmark` | 3 | `LOCAL_QUORUM` |
| `preloaded-scylla-1m` | ScyllaDB | 3 čvora | `preloaded_benchmark` | 3 | `LOCAL_QUORUM` |
| `preloaded-cassandra-1node-1m` | Cassandra | 1 čvor | `preloaded_benchmark_1node_v2` | 1 | `LOCAL_QUORUM` |
| `preloaded-scylla-1node-1m` | ScyllaDB | 1 čvor | `preloaded_benchmark_1node_v2` | 1 | `LOCAL_QUORUM` |

Svako pokretanje radi:

- sequential write: 10.000 operacija
- concurrent write: 20.000 operacija
- četiri read testa po 10.000 operacija
- tri mixed testa po 10.000 operacija
- sustained workload: 60 sekundi pri 500 operacija u sekundi
- jedan warm-up i tri mjerena prolaza
- concurrency 16
- pauzu od 60 sekundi između mjerenih prolaza

Driver timeout je 30 sekundi, a test se prekida ako mjerene operacije nisu uspješne.

## 7. Praćenje resursa

Praćenje CPU-a i memorije najbolje je pokrenuti u posebnom PowerShell prozoru prije benchmarka:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\monitor-preloaded-resources.ps1 -DurationHours 14
```

Trajanje se može promijeniti, naprimjer:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\monitor-preloaded-resources.ps1 -DurationHours 4
```

Monitor prati standardne i scale-1 kontejnere, a podatke upisuje u:

```text
results\resource-usage-v1.csv
```

Nemojte zatvoriti ovaj prozor dok benchmark traje. Ako će test sigurno trajati kraće, 
može se zadati i kraće vrijeme praćenja.

## 8. Preporučeno pokretanje standardnog testa sa tri čvora

Ovo je glavno poređenje: Cassandra sa tri čvora, a zatim ScyllaDB sa tri čvora.

### 8.1. Pokretanje Cassandre

Prvo provjeriti da je Docker Desktop pokrenut. Zatim zaustaviti ScyllaDB, pokrenuti Cassandru i sačekati 
da sva tri čvora budu spremna:

```powershell
docker compose -f ..\scylladb\docker-compose.yml down
docker compose -f ..\cassandra\docker-compose.yml up -d
docker exec cassandra-node1 nodetool status
```

U izlazu komande `nodetool status` sva tri čvora trebaju imati status `UN`.

### 8.2. Pokretanje nadzorne skripte

Kada je Cassandra spremna, pokrenuti:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-preloaded-pair.ps1
```

Ova skripta:

1. pokreće Cassandra preloaded test;
2. provjerava da li je pokušaj validno završen;
3. pravi sažetak potrošnje resursa;
4. zaustavlja Cassandra klaster;
5. pokreće ScyllaDB klaster sa tri čvora;
6. čeka da sva tri ScyllaDB čvora budu spremna;
7. pokreće ScyllaDB preloaded test;
8. pravi sažetak resursa za ScyllaDB.

Podrazumijevano su dozvoljena najviše tri pokušaja po bazi, uz deset minuta pauze između ponavljanja. 
Vrijednosti se mogu promijeniti:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-preloaded-pair.ps1 -MaxAttempts 2 -CooldownMinutes 5
```

Važno: ova skripta sama ne pokreće početni Cassandra klaster. Cassandra mora već biti pokrenuta i zdrava. 
Ako pronađe aktivan Cassandra pokušaj, može ga preuzeti i nastaviti pratiti umjesto da pokrene novi.

## 9. Direktno pokretanje pojedinačnih profila

Ako nije potrebno automatsko prebacivanje s jedne baze na drugu, profili se mogu pokretati pojedinačno.

Standardna Cassandra sa tri čvora:

```powershell
.\scripts\run-preloaded-test.ps1 -Database cassandra -Topology 1m
```

Standardna ScyllaDB sa tri čvora:

```powershell
.\scripts\run-preloaded-test.ps1 -Database scylla -Topology 1m
```

Jednočvorna Cassandra:

```powershell
.\scripts\run-preloaded-test.ps1 -Database cassandra -Topology 1node-1m
```

Jednočvorna ScyllaDB:

```powershell
.\scripts\run-preloaded-test.ps1 -Database scylla -Topology 1node-1m
```

Prije direktnog pokretanja uvijek treba ručno pokrenuti odgovarajući klaster i provjeriti njegovo stanje.

## 10. Pokretanje jednočvornog H3 testa

Jednočvorni test je dodatni, eksplorativni test skaliranja. Za njega moraju postojati ove datoteke:

```text
cassandra\docker-compose.scale-1.yml
scylladb\docker-compose.scale-1.yml
```

Pokretanje:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-preloaded-scalability-pair.ps1
```

Ova skripta sama:

- zaustavlja standardne klastere sa tri čvora;
- pokreće jednočvornu Cassandru;
- čeka da tačno jedan čvor bude spreman;
- pokreće Cassandra test i pravi sažetak;
- zaustavlja Cassandru i pokreće jednočvornu ScyllaDB;
- pokreće ScyllaDB test i pravi sažetak.

Rezultate ovog testa ne treba predstavljati kao čisto mjerenje uticaja broja čvorova, jer se istovremeno 
mijenjaju topologija i replication factor: standardni test koristi tri čvora i RF=3, a ovaj jedan čvor i RF=1.

## 11. Praćenje testa dok traje

Najvažniji događaji mogu se pratiti u novom PowerShell prozoru:

```powershell
Get-Content .\results\experiment-events-v1.csv -Wait -Tail 20
```

Stanje pripreme baselinea:

```powershell
Get-Content .\results\preloaded-state-v1.csv -Wait -Tail 20
```

Konzolni logovi pojedinačnih pokretanja nalaze se u direktoriju `results`, a trenutni nazivi počinju sa:

```text
today_preloaded_cassandra_console_
today_preloaded_scylla_console_
```

Pokušaj se smatra validnim samo ako je u evidenciji označen kao `COMPLETED` i nema neuspjelih mjerenih operacija.

## 12. Rezultati i sažetak resursa

Najvažnije izlazne datoteke su:

- `results\experiment-metadata-v2.csv`
- `results\experiment-events-v1.csv`
- `results\experiment-protocol-v1.csv`
- `results\benchmark-results-v2.csv`
- `results\benchmark-failures-v2.csv`, ako je bilo grešaka
- `results\preloaded-state-v1.csv`
- `results\resource-usage-v1.csv`
- `results\resource-summaries\...`

Ako se test pokreće ručno, sažetak resursa može se generisati naknadno pomoću ID-a eksperimenta:

```powershell
.\scripts\generate-resource-summary.ps1 -ExperimentId "OVDJE_UNIJETI_EXPERIMENT_ID"
```

Za više eksperimenata:

```powershell
.\scripts\generate-resource-summary.ps1 -ExperimentId "ID_CASSANDRA","ID_SCYLLA"
```

## 13. Pokretanje web aplikacije za pregled rezultata

Web aplikacija služi samo za pregled već sačuvanih rezultata. 
U ovom režimu ne pokreće novi benchmark i ne spaja se na bazu.

Pokretanje iz PowerShella:

```powershell
.\mvnw.cmd "-Dspring-boot.run.profiles=ui" spring-boot:run
```

Nakon pokretanja otvoriti:

```text
http://127.0.0.1:8080
```

Pokretanje iz IntelliJ IDEA:

1. otvoriti projekat;
2. pronaći klasu `BenchmarkAppApplication`;
3. napraviti Spring Boot konfiguraciju;
4. kao aktivni profil ili program argument postaviti `--spring.profiles.active=ui`;
5. kao working directory postaviti korijen raspakovanog preloaded projekta;
6. pokrenuti aplikaciju i otvoriti `http://127.0.0.1:8080`.

Ako je port 8080 zauzet, može se zadati drugi port:

```powershell
.\mvnw.cmd "-Dspring-boot.run.profiles=ui" "-Dspring-boot.run.arguments=--server.port=8081" spring-boot:run
```

Tada se aplikacija otvara na `http://127.0.0.1:8081`.

## 14. Ponovno seedovanje baselinea

U profilima je trenutno postavljeno:

```properties
benchmark.preloaded.force-reseed=false
```

To je ispravna vrijednost za normalan rad. Ne treba je mijenjati između ponavljanja jer je smisao testa da obje baze 
koriste već pripremljen baseline.

Vrijednost `true` koristiti samo kada baseline za taj profil namjerno treba obrisati i ponovo napraviti. Ova opcija čisti samo preloaded baseline i registry unutar odgovarajućeg keyspacea, ali ipak je treba koristiti pažljivo.

## 15. Zaustavljanje okruženja

Nakon završetka svih testova mogu se zaustaviti standardni klasteri:

```powershell
docker compose -f ..\cassandra\docker-compose.yml down
docker compose -f ..\scylladb\docker-compose.yml down
```

I jednočvorni klasteri:

```powershell
docker compose -f ..\cassandra\docker-compose.scale-1.yml down
docker compose -f ..\scylladb\docker-compose.scale-1.yml down
```

Nemojte dodavati opciju `-v` ako želite sačuvati Docker volumene i podatke.

## Najkraći redoslijed pokretanja

Ako je sve već pripremljeno, cijeli standardni test svodi se na sljedeće:

```powershell
cd C:\cassandra-scylla-benchmark\Ranija_mjerenja_i_grafikoni\benchmark-app-preloaded-v1

# U prvom PowerShell prozoru
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\monitor-preloaded-resources.ps1 -DurationHours 14

# U drugom PowerShell prozoru
docker compose -f ..\scylladb\docker-compose.yml down
docker compose -f ..\cassandra\docker-compose.yml up -d
docker exec cassandra-node1 nodetool status
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-preloaded-pair.ps1
```

Kada se pojave tri Cassandra čvora sa statusom `UN`, pokreće se posljednja komanda. Nakon toga supervisor vodi test do kraja i automatski prelazi na ScyllaDB.

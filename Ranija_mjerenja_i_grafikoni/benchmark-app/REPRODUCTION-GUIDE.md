> Ranija verzija aplikacije. Ove upute i rezultati ne pripadaju završnoj short-v3 seriji. Putanju lokalnog JDK-a prilagodite svom računaru.

#  Vodič za ponovno pokretanje testova iz projekta `C:\cassandra-scylla-benchmark`.

Testovi su se prvo pokretali ručno iz IntelliJ-a. Završne serije pokretane su
PowerShell skriptama koje automatski izvrše upareni Cassandra i ScyllaDB test.

## 1. Šta treba instalirati

Korišteni su:

- Windows 11;
- Docker Desktop sa WSL 2 backendom;
- Eclipse Temurin JDK 21.0.12;
- Python 3.10 ili noviji;
- IntelliJ IDEA;
- internet pri prvom pokretanju, da Docker i Maven preuzmu potrebne pakete.

Maven se ne instalira posebno. Projekat ima Maven Wrapper (`mvnw.cmd`).
Poželjno je imati najmanje 32 GB RAM-a, 8 logičkih jezgri i dovoljno slobodnog
prostora za dataset i Docker volume.

Prvo se pokrene Docker Desktop i sačeka da bude spreman. Zatim se u
PowerShellu provjeri:

```powershell
java -version
python --version
docker --version
docker compose version
docker info
```

## 2. Raspored foldera

Folderi trebaju ostati kakvi i jesu, to jest ovaj raspored:

```text
C:\cassandra-scylla-benchmark\
├── benchmark-app\
├── cassandra\
│   └── docker-compose.yml
├── scylladb\
│   └── docker-compose.yml
└── data-generator\
    ├── generate_dataset.py
    └── output\telemetry-50m\
```

Ovaj raspored je bitan jer profili koriste relativnu putanju
`../data-generator/output/telemetry-50m`.

ZIP `benchmark-app.zip` sadrži Java aplikaciju, profile, Maven Wrapper, skripte
i izlazne CSV datoteke. Docker compose datoteke i generator podataka nisu u
tom ZIP-u; nalaze se u susjednim folderima prikazanim iznad.

## 3. Java 21

Na računaru na kojem su testovi rađeni JDK je bio ovdje:

```text
C:\Users\HP\.jdks\temurin-21.0.12
```

Za PowerShell:

```powershell
$javaHome = 'C:\Users\HP\.jdks\temurin-21.0.12'
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"
java -version
```

Ovo se ponavlja u svakom novom PowerShell prozoru iz kojeg se pokreće Java
aplikacija. Ako je JDK na drugom mjestu, putanju treba prilagoditi.

U IntelliJ-u se u `File → Project Structure → Project` kao SDK postavlja
Temurin 21. Maven runner također treba koristiti isti JDK.

## 4. Generisanje dataseta

Dataset se generiše jednom. Seed `42` omogućava da se ponovo dobije isti skup.

```powershell
Set-Location 'C:\cassandra-scylla-benchmark\data-generator'

python .\generate_dataset.py `
  --records 50000000 `
  --chunk-size 1000000 `
  --devices 10000 `
  --seed 42
```

U `output\telemetry-50m` trebaju postojati datoteke `chunk-000.csv` do
`chunk-049.csv` i `manifest.json`. U manifestu se provjeri:

```json
{
  "total_records": 50000000,
  "chunk_count": 50,
  "records_per_chunk": 1000000,
  "device_count": 10000,
  "seed": 42
}
```

Ne generiše se poseban dataset za 1M, 5M i 10M. Profili čitaju isti 50M skup,
ali uzimaju samo zadani broj zapisa.

## 5. Provjera aplikacije

Prije benchmarka:

```powershell
Set-Location 'C:\cassandra-scylla-benchmark\Ranija_mjerenja_i_grafikoni\benchmark-app'

$javaHome = 'C:\Users\HP\.jdks\temurin-21.0.12'
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"

.\mvnw.cmd test
```

Ako ova provjera ne prođe, benchmark se ne pokreće dok se greška ne riješi.

## 6. Profili

| Veličina | Cassandra profil | ScyllaDB profil |
|---|---|---|
| 1M | `experiment-cassandra-1m` | `experiment-scylla-1m` |
| 5M | `experiment-cassandra-5m` | `experiment-scylla-5m` |
| 10M | `experiment-cassandra-10m` | `experiment-scylla-10m` |


## 7. Ručno pokretanje iz IntelliJ-a

U IntelliJ-u se otvori:

```text
C:\cassandra-scylla-benchmark\Ranija_mjerenja_i_grafikoni\benchmark-app
```

Zatim `Run → Edit Configurations… → + → Spring Boot`. Ako nema opcije Spring
Boot, može se izabrati `Application`.

Za Cassandra 1M:

```text
Name: Cassandra - 1M
Main class: ba.unze.master.benchmarkapp.BenchmarkAppApplication
Use classpath of module: benchmark-app
JRE: Temurin 21
Working directory: C:\cassandra-scylla-benchmark\Ranija_mjerenja_i_grafikoni\benchmark-app
Program arguments: --spring.profiles.active=experiment-cassandra-1m
```

Klikne se `Apply`, pa `Run`.

Za ScyllaDB se kopira ista konfiguracija i promijene naziv i argument:

```text
Name: Scylla - 1M
Program arguments: --spring.profiles.active=experiment-scylla-1m
```

Za 5M ili 10M mijenja se samo profil.

## 8. Ručno pokretanje iz PowerShella

Primjer za Cassandra 1M:

```powershell
Set-Location 'C:\cassandra-scylla-benchmark\Ranija_mjerenja_i_grafikoni\benchmark-app'

$javaHome = 'C:\Users\HP\.jdks\temurin-21.0.12'
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"

.\mvnw.cmd "-Dspring-boot.run.profiles=experiment-cassandra-1m" spring-boot:run
```

Za ScyllaDB 1M:

```powershell
.\mvnw.cmd "-Dspring-boot.run.profiles=experiment-scylla-1m" spring-boot:run
```

Za 5M i 10M mijenja se samo naziv profila.

## 9. Pokretanje Cassandra klastera

Cassandra i ScyllaDB ne smiju raditi istovremeno jer koriste iste portove.

```powershell
Set-Location 'C:\cassandra-scylla-benchmark'

docker compose -f .\scylladb\docker-compose.yml down
docker compose -f .\cassandra\docker-compose.yml up -d
docker exec cassandra-node1 nodetool status
```

U statusu se moraju pojaviti tri reda s oznakom `UN` (`Up/Normal`). Test se ne
pokreće dok sva tri čvora nisu spremna.

## 10. Ručni prelazak na ScyllaDB

Ako se profili pokreću ručno, nakon Cassandre:

```powershell
Set-Location 'C:\cassandra-scylla-benchmark'

docker compose -f .\cassandra\docker-compose.yml down
docker compose -f .\scylladb\docker-compose.yml up -d
docker exec scylla-node1 nodetool status
```

Ponovo se čeka da sva tri čvora budu `UN`, a zatim se pokreće odgovarajući
ScyllaDB profil. Ako se koristi supervisor iz sljedećeg poglavlja, on sam radi
ovaj prelazak.

## 11. Kako su pokretani završni parovi

Za završne serije korištena je glavna skripta
`scripts\run-benchmark-pair.ps1`.

Ona:

1. pokrene Cassandra profil zadane veličine;
2. provjeri da je nastao novi `STARTED` događaj;
3. nakon završetka traži status `COMPLETED`;
4. neuspjeli pokušaj po potrebi ponovi najviše tri puta, uz pauzu od 5-10 minuta;
5. nakon uspješne Cassandre ugasi njene kontejnere bez brisanja volumea;
6. podigne ScyllaDB i čeka tri `UN` čvora;
7. pokrene isti profil za Scyllu, također uz najviše tri pokušaja;
8. cijeli tok zapisuje u logove.

Prije pokretanja ove skripte Cassandra mora već biti podignuta i imati tri
`UN` čvora.

Za 1M par:

```powershell
Set-Location 'C:\cassandra-scylla-benchmark\Ranija_mjerenja_i_grafikoni\benchmark-app'

$javaHome = 'C:\Users\HP\.jdks\temurin-21.0.12'
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\run-benchmark-pair.ps1 `
  -DatasetSizeMillion 1
```

Za 5M i 10M ista je komanda, samo se zadnji broj promijeni u `5` ili `10`.

Prije svakog novog para ponovo se ugasi Scylla, podigne Cassandra i sačekaju
tri `UN` čvora.

Skripta pravi logove kao što su:

```text
results\benchmark_cassandra_1M.log
results\benchmark_scylla_1M.log
results\benchmark_cassandra_5M.log
results\benchmark_scylla_5M.log
```

## 12. Praćenje resursa

Prije benchmarka u posebnom PowerShell prozoru potrebmo je pokrenuti monitor na sljedeći način:

```powershell
Set-Location 'C:\cassandra-scylla-benchmark\Ranija_mjerenja_i_grafikoni\benchmark-app'

Start-Process powershell.exe `
  -ArgumentList @(
    '-NoProfile',
    '-ExecutionPolicy','Bypass',
    '-File',(Resolve-Path '.\scripts\monitor-resources.ps1'),
    '-DurationHours','20'
  ) `
  -WindowStyle Hidden
```

Trajanje se postavlja duže od očekivanog testa. Za duge serije korišteno je i
48 sati. Skripta svakih 60 sekundi bilježi Windows CPU/RAM te CPU, memoriju,
mrežni i diskovni I/O Docker čvorova u:

```text
results\resource-usage-v1.csv
```

Monitor ne šalje upite bazi i ne pokreće benchmark.

## 13. TXT sažetak resursa

Nakon testa:

```powershell
Set-Location 'C:\cassandra-scylla-benchmark\Ranija_mjerenja_i_grafikoni\benchmark-app'

.\scripts\generate-resource-summary.ps1 `
  -ExperimentId 'EXP-OVDJE-UPISATI-STVARNI-ID'
```

Za oba ID-a odjednom:

```powershell
.\scripts\generate-resource-summary.ps1 `
  -ExperimentId 'EXP-CASSANDRA-ID','EXP-SCYLLA-ID'
```

Sažeci se spremaju u `results\resource-summaries`.

## 14. Kako pratiti test

Najlakše je pratiti prozor iz kojeg je test pokrenut. Dodatno se mogu otvoriti
posebni PowerShell prozori:

```powershell
Set-Location 'C:\cassandra-scylla-benchmark\Ranija_mjerenja_i_grafikoni\benchmark-app'
Get-Content .\results\benchmark-execution.log -Tail 20 -Wait
```

```powershell
Get-Content .\results\experiment-events-v1.csv -Tail 10 -Wait
```

```powershell
Get-Content .\results\benchmark-results-v2.csv -Tail 5 -Wait
```

Novi rezultat se upisuje nakon završenog pojedinačnog prolaza. Ako neko vrijeme
nema novog reda, moguće je da još traje veliki preload ili workload.

Cijela serija je završena tek kada konzola ispiše
`ALL BENCHMARK EXPERIMENTS FINISHED` i lifecycle CSV za taj ID sadrži
`COMPLETED`.

Ručno zaustavljanje radi se s `Ctrl+C` ili klikom na  `Stop` u
IntelliJ-u. Takav pokušaj se bilježi kao `ABORTED`.

## 15. Čemu služe skripte

### `run-benchmark-pair.ps1`

Glavna skripta za jedan Cassandra–ScyllaDB par. Prima veličinu 1, 5, 10, 25
ili 50 i sama radi prelazak na Scyllu. Za ponovno pokretanje jednog finalnog
para koristi se ova skripta.

### `monitor-resources.ps1`

Svakih 60 sekundi snima CPU, RAM, mrežu i disk. Pokreće se odvojeno prije
benchmarka.

### `generate-resource-summary.ps1`

Od sirovih resource zapisa pravi TXT sažetak za navedene ID-eve.

### `run-complete-benchmark-suite.ps1`

Korištena je da nakon aktivnog 1M para automatski nastavi s 5M, 10M, 25M i
50M. Te veličine su hardkodirane u skripti. Zato je ne treba pokretati naslijepo
ako se žele samo 1M, 5M i 10M. Sigurnije je tri puta pokrenuti glavni
supervisor, s brojevima 1, 5 i 10.

## 16. Redoslijed za ponovno pokretanje cijelog skupa

Za 1M, zatim 5M i na kraju 10M:

1. Pokrenuti Docker Desktop.
2. Pokrenuti resource monitor dovoljno dugo da pokrije cijeli par.
3. Ugasiti Scyllu i podići Cassandra klaster.
4. Provjeriti da Cassandra ima tri `UN` čvora.
5. Pokrenuti `run-benchmark-pair.ps1` s veličinom 1, 5 ili 10.
6. Ne gasiti računar, Docker ni terminal dok obje baze ne završe.
7. Provjeriti da oba eksperimenta imaju `COMPLETED`.
8. Napraviti resource summary za oba ID-a.
9. Prije sljedeće veličine ponovo podići Cassandru i provjeriti čvorove.

Svako novo pokretanje dobija novi `experiment_id` i dopisuje nove redove u
postojeće CSV datoteke.

## 17. Gašenje

Nakon testova:

```powershell
docker compose -f 'C:\cassandra-scylla-benchmark\cassandra\docker-compose.yml' down
docker compose -f 'C:\cassandra-scylla-benchmark\scylladb\docker-compose.yml' down
```

Ovo ne briše volume. Opcija `down -v` briše podatke iz Docker volumea i ne
treba se koristiti osim kada se namjerno želi potpuno prazna baza.

## 18. Najkraća verzija

Ako je sve već instalirano i dataset postoji:

```powershell
Set-Location 'C:\cassandra-scylla-benchmark'
docker compose -f .\scylladb\docker-compose.yml down
docker compose -f .\cassandra\docker-compose.yml up -d
docker exec cassandra-node1 nodetool status
```

Kada su tri Cassandra čvora `UN`:

```powershell
Set-Location 'C:\cassandra-scylla-benchmark\Ranija_mjerenja_i_grafikoni\benchmark-app'

$javaHome = 'C:\Users\HP\.jdks\temurin-21.0.12'
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\run-benchmark-pair.ps1 `
  -DatasetSizeMillion 1
```

Broj `1` se zamijeni brojem `5` ili `10`. Supervisor sam izvrši Cassandru,
prebaci klaster na Scyllu i pokrene odgovarajući ScyllaDB test.

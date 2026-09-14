# Apache Cassandra i ScyllaDB — research-v3

Paket sadrži Java benchmark aplikaciju, Docker konfiguracije, skripte i rezultate završnog testiranja 
`short-v3`. Za pregled rezultata nije potrebno pokretati Docker.

## Sadržaj paketa

- `benchmark-app-research-v3` — Java kod, testovi, Maven postavke i izvršni JAR.
- `compose` — konfiguracije oba sistema sa jednim, dva i tri čvora.
- `plans/short-v3.json` — završni plan od 204 testa.
- `results/short-v3` — rezultati izvršenih testova.
- `scripts` — priprema, izvođenje i analiza.
- `maven-settings.xml` — Maven postavke.

## Pet fajlova po završenom testu

| Fajl | Sadržaj |
|---|---|
| result.json | Propusnost, latencije, percentili, uspješnost i konfiguracija testa. |
| result.csv | Tabelarni rezultat za pregled u Excelu. |
| resources.jsonl | Vremenski uzorci CPU-a i memorije, s oznakama faze. |
| effective-config.json | Efektivne postavke i ulaz za aplikaciju. |
| baseline.json | Podaci o pripremljenom početnom skupu i uzorku za čitanje. |

## Pregled i analiza

Iz ovog foldera, uz Python 3.10 ili noviji:

```powershell
python .\scripts\analyze-results.py .\results\short-v3
```

Skripta pokreće analizu u folderu `Analiza_research-v3_short_v3`. 
Potrebne biblioteke instaliraju se pomoću 
`python -m pip install -r ..\Analiza_research-v3_short_v3\reprodukcija\requirements.txt`. 
CSV/JSON sažeci i devet PNG grafikona generišu se u taj folder iz sirovih podataka smještenih u `results` folderu. 

## Novo izvođenje

Potrebni su Docker Desktop s WSL 2, JDK 21 i dataset iz `data-generator/output/telemetry-50m`.
Za short-v3 koriste se manifest i prvih deset CSV chunk fajlova. 

```powershell
.\scripts\build.ps1
$dataset = 'C:\cassandra-scylla-benchmark\data-generator\output\telemetry-50m'
wsl -d docker-desktop -u root -- sysctl -w fs.aio-max-nr=1048576
.\scripts\preflight.ps1 -DatasetPath $dataset -Nodes 3
```

Putanju je potrebno prilagoditi. 
Za novo izvođenje kopirati plan pod novim nazivom, promijeniti njegovo polje `name` i 
proslijediti tu datoteku skripti `scripts/run-short.ps1` parametrima `-Plan` i `-DatasetPath`. 
Skripta upravlja klasterima i preskače završene testove te bez promjene naziva plana ne bi se pokrenulo testiranje.

## Primjer novog izvođenja

Primjer pripreme zasebnog plana za nova mjerenja, iz foldera `research-v3`:

Potrebno je prvo generisati podatke, ukoliko nisu već generisani:

```powershell
cd C:\cassandra-scylla-benchmark\data-generator
python .\generate_dataset.py

cd ..\research-v3
```

Zatim:

```powershell
$noviPlan = '.\plans\short-novo.json'
if (Test-Path $noviPlan) { throw 'Plan vec postoji; odaberite drugo ime.' }
$plan = Get-Content .\plans\short-v3.json -Raw | ConvertFrom-Json
$plan.name = 'short-novo'
$plan | ConvertTo-Json -Depth 30 | Set-Content $noviPlan -Encoding UTF8
$dataset = 'C:\cassandra-scylla-benchmark\data-generator\output\telemetry-50m'
.\scripts\start-short.ps1 -Plan $noviPlan -DatasetPath $dataset
```

Ova komanda pokreće cijeli novi plan. 
Koristi već izgrađeni JAR i prethodno pripremljeni dataset. 
Novi rezultati čuvaju se u `results/short-novo`.

Tokom izvođenja koristi se `run.lock` radi sprečavanja paralelnog pokretanja dvije serije. 
Aplikacija privremeno koristi `phase.txt` i `result.json.tmp`. 
Pri neuspješnom pokretanju mogu nastati `startup-error.txt`, `startup-logs.txt` ili `aborted.json`; 
oni služe za evidentiranje prekida i razloga prekida.

Na nivou nove serije ostaju `plan.json`, `campaign-inputs.json` i `docker-info.json` 
jer opisuju plan i mjerenja. 
Maven pri izgradnji stvara standardne fajlove u `target`; oni nisu dodatni rezultati eksperimenata.
# Telemetry Dataset Generator

Python generator za kreiranje sintetičkih telemetrijskih podataka koji se koriste za testiranje i poređenje performansi Apache Cassandra i ScyllaDB.
Generator priprema podatke prije pokretanja benchmark testova, pri čemu se isti dataset koristi za obje baze.

## Dataset

Glavni dataset sadrži 50.000.000 zapisa, raspoređenih u 50 CSV datoteka po 1.000.000 zapisa.
Podaci se generišu za 10.000 uređaja, a svaki zapis sadrži:

* `device_id` – identifikator uređaja
* `event_time` – vrijeme događaja
* `id` – jedinstveni identifikator zapisa
* `metric_type` – vrsta mjerenja
* `value` – vrijednost mjerenja

Vrste mjerenja su:

`temperature`, `humidity`, `pressure`, `voltage` i `light`.

Dataset se može koristiti u različitim veličinama:

| Veličina | Broj chunkova |
| -------- | ------------- |
| 1M       | 1             |
| 5M       | 5             |
| 10M      | 10            |
| 25M      | 25            |
| 50M      | 50            |

## Struktura

output/
└── telemetry-50m/
    ├── chunk-000.csv
    ├── chunk-001.csv
    ├── ...
    ├── chunk-049.csv
    └── manifest.json

`manifest.json` sadrži osnovne informacije o generisanom datasetu, uključujući broj zapisa, uređaja, chunkova i korišteni seed.

## Pokretanje

Potrebno je imati Python 3.10 ili noviji.

Generator koristi samo standardne Python biblioteke, tako da nisu potrebni dodatni paketi.

Pokreće se u terminalu, komandom:

`python generate_dataset.py`

Za brzu provjeru generatora može se koristiti manji dataset:

`python generate_dataset.py --records 100000 --chunk-size 10000 --devices 10000 --seed 42 --output output/proba`

## Konfiguracija

Zadana konfiguracija glavnog dataseta:

Records     : 50,000,000
Chunks      : 50
Chunk size  : 1,000,000
Devices     : 10,000
Seed        : 42
Format      : CSV

## Povezanost sa benchmark aplikacijom

Generator je odvojen od Spring Boot benchmark aplikacije. Njegova uloga je samo priprema podataka.

Data Generator
      ↓
  Dataset
      ↓
Benchmark Application
      ↓
Cassandra / ScyllaDB

Generisanje dataseta nije uključeno u vrijeme mjerenja performansi baza podataka.
# Apache Cassandra i ScyllaDB folder

## Organizacija

| Folder | Namjena                                                                                                                                         |
|---|-------------------------------------------------------------------------------------------------------------------------------------------------|
| `research-v3` | Završni benchmark i 204 završena rezultata, po 102 za svaku bazu                                                                                |
| `Analiza_research-v3_short_v3` | CSV tabele, devet PNG grafikona, Markdown dokumentacija i jedna skripta za analizu                                                              |
| `data-generator` | Generator sintetičkih podataka, bez generisanih podataka                                                                                        |
| `Ranija_mjerenja_i_grafikoni` | Ranije aplikacije, njihovi rezultati, generator ranijih grafikona i Docker konfiguracije za ranije verzije aplikacija (`cassandra`, `scylladb`) |

Folder data-generator/output namjerno je prazan radi veličine dataseta; dataset se po potrebi generiše pokretanjem skripte generate_dataset.py.

Pilotne verzije, ranije aplikacije i njihovi rezultati premješteni su u folder `Ranija_mjerenja_i_grafikoni` radi preglednosti. Zbog toga njihove stare putanje ne odgovaraju novoj strukturi projekta.
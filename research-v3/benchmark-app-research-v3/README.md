# Benchmark aplikacija

Java 21 CLI aplikacija za poređenje Apache Cassandre i ScyllaDB-a. 
Koristi Apache Cassandra Java Driver 4.19.3 i HdrHistogram 2.2.2.

## Sadržaj

- `src/main/java` — kod aplikacije, povezivanje s klasterom i izvršavanje mjerenja.
- `pom.xml` — biblioteke i postavke izgradnje.
- `.mvn`, `mvnw` i `mvnw.cmd` — Maven Wrapper za izgradnju aplikacije.
- `target/benchmark-research.jar` — aplikacija spremna za pokretanje.

## Izgradnja i pokretanje

Iz glavnog foldera `research-v3`, u PowerShellu pokrenuti:

```powershell
.\scripts\build.ps1
```

Ova komanda pronalazi Java 21, pokreće testove i gradi JAR.

Izvođenje eksperimenata vodi skripta `scripts/run-short.ps1` iz glavnog foldera. 
Ona priprema klaster, konfiguraciju i ulazne podatke. 
Potpune upute i eksperimentalna matrica nalaze se u glavnom `README.md`.
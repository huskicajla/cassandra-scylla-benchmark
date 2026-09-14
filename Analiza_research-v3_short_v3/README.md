# Analiza rezultata short-v3

Ovaj folder sadrži analizu završne serije poređenja Apache Cassandre i ScyllaDB-a. 
Obuhvaćena su 204 testa: 
102 za svaku bazu, odnosno 68 konfiguracija sa po tri ponavljanja. 
Ranija pilotna mjerenja nisu uključena.

## Pokretanje

Potrebni su Python 3.10 ili noviji, NumPy i Matplotlib. 
Docker, Java i pokretanje baza nisu potrebni. 
U PowerShell terminalu otvorite ovaj folder:

```powershell
cd C:\cassandra-scylla-benchmark\Analiza_research-v3_short_v3
python -m pip install -r .\reprodukcija\requirements.txt
python .\reprodukcija\analyze_short.py
```

Putanju treba navesti samo ako se promijeni raspored foldera. 
Plan se čita iz njegovog `plan.json`. 
Analiza čita postojeće rezultate i ne mijenja ih. 

Ponovno pokretanje obnavlja CSV, JSON i PNG rezultate u ovom folderu.

Ako se folderi nalaze na drugom mjestu, navedite putanje:

```powershell
python .\reprodukcija\analyze_short.py --results "D:\projekat\research-v3\results\short-v3" --output "D:\projekat\nova-analiza"
```

Novi izlazni folder dobija nove izračune i grafikone na osnovu rezultata testova. 

## Postupak analize

1. Uparuje ID svakog testa iz plana sa jednim `result.json` fajlom. 
Pokušaji bez završenog rezultata evidentiraju se odvojeno.
2. Provjerava konfiguraciju, status, broj operacija, trajanje mjerenja, 
početni broj potvrđenih redova, throughput i evidentirane greške.
3. Iz `resources.jsonl` uzima samo uzorke označene kao mjerenje, 
unutar početka i kraja mjernog intervala. Sabira CPU i memoriju čvorova, 
zatim računa prosjek uzoraka za svaki test.
4. Grupira tri ponavljanja iste konfiguracije. 
Računa prosjek, devijaciju, minimum, maksimum i koeficijent varijacije propusnosti.
5. Poredi ScyllaDB i Cassandru samo pri odgovarajućim konfiguracijama. 
Računa relativno skaliranje, H4 pokazatelj Q i opisne bootstrap intervale.
6. Pravi devet PNG grafikona.

Skripta je namijenjena kompletnoj short-v3 matrici sa tri ponavljanja i 204 testa. 

## Sadržaj izlaznih fajlova

| Fajl | Sadržaj |
|---|---|
| `testovi.csv` | Jedan red po završenom testu, konfiguracija, latencije, throughput, greške i prosječni resursi |
| `grupe.csv` | Sažeci tri ponavljanja iste konfiguracije |
| `poredjenja.csv` | Omjeri grupnih prosjeka ScyllaDB/Cassandra |
| `skaliranje.csv` | Promjena throughput-a i latencije u odnosu na jedan čvor |
| `h4_Q.csv` | Q za 1M i 10M, opisni intervali i omjeri po ponavljanju |
| `omjeri_opisni_intervali.csv` | Opisni bootstrap intervali throughput omjera |
| `resursi_uzorci.csv` | Pojedinačni CPU/RAM uzorci korišteni u računanju |
| `nezavrseni_pokusaji.csv` | Pokušaji bez završenog rezultata, izuzeti iz sažetaka |
| `audit.json` | Broj testova, provjere ulaza, pokrivenost resursnim uzorcima i grupe sa većom varijabilnošću |
| `audit_problemi.csv` | Uočeni problemi; prazan fajl znači da ih u tim provjerama nema |
| `manifest.csv` | SHA-256 kontrolne sume pet sačuvanih fajlova po testu |

## Grafikoni

Šest slika `h1_h2_*` prikazuje throughput, prosječnu latenciju, P95, P99, CPU i 
memoriju prema concurrency vrijednosti. Linije povezuju grupne prosjeke, 
a tačke prikazuju pojedinačna ponavljanja.

`h3_skaliranje.png` prikazuje odnos throughput-a klastera sa N čvorova prema jednom čvoru.
`h4_profili.png` poredi throughput i odvojene read/write P99 omjere između profila. 
`h4_ponavljanja.png` prikazuje pojedinačne rezultate mješovitih testova i njihove prosjeke.
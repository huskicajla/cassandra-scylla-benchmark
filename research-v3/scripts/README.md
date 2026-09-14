# Skripte za završno testiranje

Skripte se pokreću iz foldera `research-v3`, u Windows PowerShellu 5.1 ili PowerShellu 7.

| Skripta | Namjena |
|---|---|
| `build.ps1` | Pronalazi JDK 21, izvršava Java testove i gradi JAR. |
| `preflight.ps1` | Provjerava Docker, resurse, druge kontejnere i dataset. |
| `new-plan.ps1` | Priprema dodatne eksperimentalne matrice. |
| `run-short.ps1` | Izvršava odabrani plan uz ponovno korištenje spremnog klastera i preskakanje završenih testova. |
| `run-plan.ps1` | Alternativno izvođenje plana s upravljanjem klasterom po testu. |
| `start-short.ps1` | Podesi Docker AIO i poziva run-short; zahtijeva eksplicitnu putanju plana. |
| `stop-research.ps1` | Zaustavlja istraživačke klastere bez brisanja volumena. |
| `analyze-results.py` | Poziva zajedničku analizu iz susjednog foldera Analiza_research-v3_short_v3. |

Za analizu postojećih rezultata:

```powershell
python -m pip install -r ..\Analiza_research-v3_short_v3\reprodukcija\requirements.txt
python .\scripts\analyze-results.py .\results\short-v3
```

Rezultati analize i grafikoni nalaze se u folderu Analiza_research-v3_short_v3.

Završen test se sastoji od pet fajlova: result.json, result.csv, effective-config.json, 
baseline.json i resources.jsonl. 
Histogrami ostaju u memoriji za računanje percentila. 


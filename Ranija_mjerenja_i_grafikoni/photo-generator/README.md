# Photo generator

Ovaj projekt generiše dokumentacijske slike iz finalnih V2 CSV rezultata. 

## Jednokratna priprema

pip install -r requirements.txt

## Generisanje slika

python generate_report_figures.py

Skripta čita `results/` i `results_preloaded/`, a upisuje sedam PNG slika plus `figure-manifest.csv` u odvojeni folder `photos/`. Ulazni CSV folderi se ne mijenjaju. Prvih pet slika pokriva glavnu V2 seriju; posljednje dvije pripadaju zasebnom preloaded profilu. 

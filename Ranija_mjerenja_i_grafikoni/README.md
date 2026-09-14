# Ranija mjerenja i grafikoni

Ovdje su sačuvane prethodne verzije benchmark aplikacije i njihovi rezultati:

- `benchmark-app` — ranija Spring Boot aplikacija i rezultati.
- `benchmark-app-preloaded-v1` — verzija za testove s unaprijed pripremljenim podacima, napravljena na osnovu `benchmark-app`.
- `photo-generator` — sedam PNG grafikona iz ranijih CSV rezultata. 

Ove aplikacije nisu korištene za završni research-v3 ciklus od 204 testa. Njihovi parametri, protokoli i formati razlikuju se od research-v3. Sačuvani su radi praćenja razvoja istraživanja; njihovi podaci ne ulaze u završnu analizu.

Upute su unutar foldera za svaku pojedinačnu aplikaciju. Pokretanje se izvodi iz foldera odabrane aplikacije. Putanje do dataseta usklađene su sa sadašnjim rasporedom foldera. Prije novog izvođenja na drugom računaru treba ih prilagoditi.

Raniji generator slika koristi lokalne kopije CSV podataka u svojim folderima `results` i `results_preloaded`. Ne čita short-v3 JSON rezultate i generiše samo PNG slike i njihov CSV spisak.
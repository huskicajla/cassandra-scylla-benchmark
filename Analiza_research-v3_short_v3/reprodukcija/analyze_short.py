import json,csv,hashlib,re,statistics as st
from pathlib import Path
from collections import defaultdict
from datetime import datetime
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import argparse,itertools
parser=argparse.ArgumentParser(description='Analiza short-v3 rezultata i PNG grafikoni; ne pokrece benchmark.')
parser.add_argument('--results',type=Path,default=Path(__file__).resolve().parents[2]/'research-v3/results/short-v3')
parser.add_argument('--output',type=Path,default=Path(__file__).resolve().parents[1])
args=parser.parse_args()
campaign=args.results.resolve()
OUT=args.output.resolve()
if OUT == campaign or campaign in OUT.parents:
 parser.error('Izlaz analize mora biti izvan izvornih rezultata.')
OUT.mkdir(parents=True,exist_ok=True)
(OUT/'grafikoni').mkdir(exist_ok=True)
def read(p): return json.loads(p.read_text(encoding='utf-8-sig'))
def dt(x): return datetime.fromisoformat(x.replace('Z','+00:00'))
def mean(a): return st.mean(a) if a else None
def unit(s):
 m=re.match(r'([\d.]+)\s*([A-Za-z]+)',s.strip()); scales={'B':1,'kB':1e3,'MB':1e6,'GB':1e9,'TB':1e12,'KiB':1024,'MiB':1024**2,'GiB':1024**3}
 return float(m[1])*scales[m[2]] if m else None
def export(name,rows):
 if not rows:
  (OUT/(name+'.csv')).write_text('',encoding='utf-8-sig');return
 keys=list(dict.fromkeys(k for r in rows for k in r))
 with (OUT/(name+'.csv')).open('w',encoding='utf-8-sig',newline='') as f:
  w=csv.DictWriter(f,keys);w.writeheader();w.writerows(rows)
plan=read(campaign/'plan.json')
issues=[]; rows=[]; incomplete=[];manifest=[]; samples=[]
for cell in plan['cells']:
 files=list((campaign/cell['id']).glob('attempt-*/result.json'))
 if len(files)!=1:issues.append({'id':cell['id'],'issue':f'result_count={len(files)}'})
 for attempt in (campaign/cell['id']).glob('attempt-*'):
  if not (attempt/'result.json').exists():incomplete.append({'id':cell['id'],'attempt':str(attempt),'phase':(attempt/'phase.txt').read_text(errors='replace').strip() if (attempt/'phase.txt').exists() else ''})
 if len(files)!=1:continue
 p=files[0];v=read(p);c=v['config'];start,end=dt(v['startedAt']),dt(v['finishedAt'])
 flags=[]
 for k,val in cell.items():
  if c.get(k)!=val:flags.append('config:'+k)
 if v['status']!='COMPLETED':flags.append('status:'+v['status'])
 if v['total']['failed'] or v['total']['validationFailures']:flags.append('errors')
 if v['attempted']<cell['minimumOperations'] or v['elapsedSeconds']<cell['measurementSeconds']:flags.append('minimum')
 if v['baselineRowsAcknowledged']!=cell['datasetSize']:flags.append('baseline')
 if v['total']['successful']!=v['read']['successful']+v['write']['successful']:flags.append('counts')
 if abs(v['throughput']-v['total']['successful']/v['elapsedSeconds'])>0.01:flags.append('throughput')
 if v['peakInFlight']>c['concurrency']:flags.append('concurrency')
 r={k:c[k] for k in ['id','suite','database','datasetSize','nodes','rf','cl','concurrency','profile','operation','writePercent','repetition','seed']}
 r.update({k:v.get(k) for k in ['startedAt','finishedAt','elapsedSeconds','throughput','attempted','actualWriteFraction','initialRows','acknowledgedFinalRows','baselineSampleSha256','peakInFlight']})
 for section in ['total','read','write']:
  r.update({section+'_'+k:v[section].get(k) for k in ['successful','failed','validationFailures','meanMs','p50Ms','p95Ms','p99Ms','maxMs']})
 measurements=[]; malformed=0
 resource=p.parent/'resources.jsonl'
 if resource.exists():
  for line in resource.read_text(encoding='utf-8-sig').splitlines():
   try:s=json.loads(line)
   except:malformed+=1;continue
   if s.get('phase')=='MEASUREMENT' and start<=dt(s['at'])<=end:
    cs=s.get('containers',[])
    if len(cs)!=c['nodes']:flags.append('resource_node_count');continue
    try:
     sample={'id':c['id'],'at':s['at'],'cpu_percent_sum':sum(float(x['CPUPerc'].strip('%')) for x in cs),'memory_gib_sum':sum(unit(x['MemUsage'].split('/')[0]) for x in cs)/1024**3,'client_memory_gib':s['clientWorkingSetBytes']/1024**3,'client_cpu_seconds':s['clientCpuSeconds']}
     measurements.append(sample);samples.append(sample)
    except (KeyError,TypeError,ValueError):flags.append('resource_parse')
 measurements.sort(key=lambda x:x['at'])
 r['resource_samples']=len(measurements)
 for src,dst in [('cpu_percent_sum','cpu_percent_sum_mean'),('memory_gib_sum','memory_gib_sum_mean'),('client_memory_gib','client_memory_gib_mean')]:
  r[dst]=mean([s[src] for s in measurements])
 if len(measurements)>1:
  span=(dt(measurements[-1]['at'])-dt(measurements[0]['at'])).total_seconds()
  r['resource_span_fraction']=span/v['elapsedSeconds']
  r['resource_max_gap_seconds']=max((dt(b['at'])-dt(a['at'])).total_seconds() for a,b in zip(measurements,measurements[1:]))
  r['client_cpu_percent']=100*(measurements[-1]['client_cpu_seconds']-measurements[0]['client_cpu_seconds'])/span
  r['cpu_core_seconds_per_1000_ops']=r['cpu_percent_sum_mean']/100*1000/r['throughput']
 else:flags.append('resources_missing')
 if malformed:flags.append('malformed_resources:'+str(malformed))
 effective=read(p.parent/'effective-config.json')
 if any(effective.get(k)!=c.get(k) for k in cell):flags.append('effective_config')
 r['audit_flags']=';'.join(sorted(set(flags))) or 'OK';r['source']=str(p)
 rows.append(r)
 for f in flags:issues.append({'id':c['id'],'issue':f})
 for name in ['result.json','result.csv','effective-config.json','baseline.json','resources.jsonl']:
  fp=p.parent/name
  if fp.exists():manifest.append({'path':str(fp),'sha256':hashlib.sha256(fp.read_bytes()).hexdigest(),'bytes':fp.stat().st_size})
if issues:
 export('audit_problemi',issues)
 (OUT/'audit.json').write_text(json.dumps({'planned':len(plan['cells']),'completed_unique':len(rows),'issues':issues},indent=2),encoding='utf-8')
 raise SystemExit('Provjera ulaza nije prosla. Pogledajte audit.json; analiza nije dovrsena.')
groups=defaultdict(list)
keyfields=['suite','datasetSize','nodes','rf','cl','concurrency','profile','database']
for r in rows:groups[tuple(r[k] for k in keyfields)].append(r)
metrics=['throughput','total_meanMs','total_p95Ms','total_p99Ms','read_meanMs','read_p95Ms','read_p99Ms','write_meanMs','write_p95Ms','write_p99Ms','cpu_percent_sum_mean','memory_gib_sum_mean','client_cpu_percent','cpu_core_seconds_per_1000_ops']
if any(len(v)!=3 for v in groups.values()):raise SystemExit('Ova analiza zahtijeva tri ponavljanja po konfiguraciji.')
summary=[]
for key,vals in sorted(groups.items()):
 s=dict(zip(keyfields,key));s['n']=len(vals)
 for metric in metrics:
  a=[v[metric] for v in vals if v.get(metric) is not None]
  s[metric]=mean(a)
  s[metric+'_sd']=st.stdev(a) if len(a)>1 else None
  s[metric+'_min']=min(a) if a else None;s[metric+'_max']=max(a) if a else None
 s['throughput_cv_pct']=100*s['throughput_sd']/s['throughput']
 summary.append(s)
lookup={tuple(s[k] for k in keyfields):s for s in summary}
comparisons=[]
for k,vals in sorted(groups.items()):
 if k[-1]!='scylla':continue
 other=groups[k[:-1]+('cassandra',)];s=dict(zip(keyfields[:-1],k[:-1]))
 for metric in metrics:
  a=[r[metric] for r in vals if r.get(metric) is not None];b=[r[metric] for r in other if r.get(metric) is not None]
  if a and b and mean(b):s[metric+'_S_C']=mean(a)/mean(b)
 comparisons.append(s)
scaling=[]
for s in summary:
 if s['suite']!='B_H3':continue
 key=tuple(s[k] for k in keyfields);base=lookup[(key[0],key[1],1,*key[3:])]
 scaling.append({**{k:s[k] for k in keyfields},'speedup':s['throughput']/base['throughput'],'efficiency':s['throughput']/base['throughput']/s['nodes'],'latency_N_1':s['total_meanMs']/base['total_meanMs']})
for name,data in [('testovi',rows),('grupe',summary),('poredjenja',comparisons),('skaliranje',scaling),('resursi_uzorci',samples),('audit_problemi',issues),('nezavrseni_pokusaji',incomplete),('manifest',manifest)]:export(name,data)
audit={'planned':len(plan['cells']),'completed_unique':len(rows),'groups':len(summary),'issues':issues,'incomplete_attempts':len(incomplete),'min_resource_samples':min(r['resource_samples'] for r in rows),'min_resource_span_fraction':min(r.get('resource_span_fraction',0) for r in rows),'max_resource_gap':max(r.get('resource_max_gap_seconds',0) for r in rows),'measurement_seconds_range':[min(r['elapsedSeconds'] for r in rows),max(r['elapsedSeconds'] for r in rows)],'high_cv_groups':[s for s in summary if s['throughput_cv_pct']>15]}
(OUT/'audit.json').write_text(json.dumps(audit,indent=2),encoding='utf-8')
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':10})
colors={'cassandra':'#335e9c','scylla':'#d16c32'}
for metric,label,slug in [('throughput','Propusnost (operacija/s)','propusnost'),('total_meanMs','Prosječna latencija (ms)','latencija'),('total_p95Ms','P95 latencija (ms)','p95'),('total_p99Ms','P99 latencija (ms)','p99'),('cpu_percent_sum_mean','Zbir CPU po čvorovima (%)','cpu'),('memory_gib_sum_mean','Zbir memorije kontejnera (GiB)','memorija')]:
 fig,axes=plt.subplots(2,2,figsize=(11,7),layout='constrained')
 for ax,(d,op) in zip(axes.flat,[(d,op) for d in [1000000,10000000] for op in ['CONCURRENT_WRITE','READ_SINGLE']]):
  for db in colors:
   ss=sorted([s for s in summary if s['suite']=='A_H1_H2' and s['datasetSize']==d and s['profile']==op and s['database']==db],key=lambda s:s['concurrency'])
   ax.plot([s['concurrency'] for s in ss],[s[metric] for s in ss],'-o',label=db,color=colors[db])
   rr=[r for r in rows if r['suite']=='A_H1_H2' and r['datasetSize']==d and r['profile']==op and r['database']==db]
   ax.scatter([r['concurrency'] for r in rr],[r[metric] for r in rr],s=18,alpha=.4,color=colors[db])
  ax.set(xscale='log',xticks=[1,8,32,128],xticklabels=['1','8','32','128'],xlabel='Concurrency',ylabel=label,title=f'{d//1000000}M • {op}')
  ax.grid(alpha=.2);ax.legend(fontsize=8)
 fig.suptitle(label+' — linije: prosjeci; tačke: 3 ponavljanja')
 fig.savefig(OUT/'grafikoni'/f'h1_h2_{slug}.png',dpi=180);plt.close(fig)
fig,axes=plt.subplots(2,2,figsize=(11,7),layout='constrained')
for ax,(c,op) in zip(axes.flat,[(c,op) for c in [8,32] for op in ['CONCURRENT_WRITE','READ_SINGLE']]):
 for db in colors:
  ss=sorted([s for s in scaling if s['concurrency']==c and s['profile']==op and s['database']==db],key=lambda s:s['nodes'])
  ax.plot([s['nodes'] for s in ss],[s['speedup'] for s in ss],'-o',label=db,color=colors[db])
 ax.axhline(1,color='gray',ls='--');ax.set(xticks=[1,2,3],xlabel='Broj čvorova',ylabel='T(N) / T(1)',title=f'1M • C={c} • {op}');ax.grid(alpha=.2);ax.legend()
fig.suptitle('H3 — RF=1; svi čvorovi dijele jedan računar')
fig.savefig(OUT/'grafikoni/h3_skaliranje.png',dpi=180);plt.close(fig)
fig,axes=plt.subplots(1,3,figsize=(13,4),layout='constrained')
profiles=['READ_HEAVY','BALANCED','WRITE_HEAVY']
for ax,(metric,label) in zip(axes,[('throughput_S_C','Propusnost S/C'),('read_p99Ms_S_C','Read P99 S/C'),('write_p99Ms_S_C','Write P99 S/C')]):
 for d in [1000000,10000000]:
  ss={s['profile']:s for s in comparisons if s['suite']=='C_H4' and s['datasetSize']==d}
  ax.plot([20,50,80],[ss[p][metric] for p in profiles],'-o',label=f'{d//1000000}M')
 ax.axhline(1,color='gray',ls='--');ax.set(xticks=[20,50,80],xlabel='Udio upisa (%)',ylabel=label);ax.grid(alpha=.2);ax.legend()
fig.suptitle('H4 — C=32, N=3, RF=3; omjeri prosjeka tri ponavljanja')
fig.savefig(OUT/'grafikoni/h4_profili.png',dpi=180);plt.close(fig)
fingerprints=defaultdict(set)
for r in rows:fingerprints[(r['datasetSize'],r['repetition'])].add(r['baselineSampleSha256'])
audit['sample_hash_mismatch_groups']=[str(k) for k,v in fingerprints.items() if len(v)!=1]
audit['audit_scope']='Plan, result.json, effective-config.json i resources.jsonl; detaljni snimci kontejnera i histogrami nisu potrebni za ovu analizu.'
def bootmeans(values):return np.array([st.mean(a) for a in itertools.product(values,repeat=len(values))])
unc=[]
for s in comparisons:
 k=tuple(s[x] for x in keyfields[:-1]);z={x:[r['throughput'] for r in groups[k+(x,)]] for x in ['scylla','cassandra']}
 a,b=bootmeans(z['scylla']),bootmeans(z['cassandra']);dist=(a[:,None]/b).flatten()
 unc.append({**{x:s[x] for x in keyfields[:-1]},'ratio':s['throughput_S_C'],'bootstrap_p025':float(np.quantile(dist,.025)),'bootstrap_p975':float(np.quantile(dist,.975))})
export('omjeri_opisni_intervali',unc)
qrows=[]
for d in [1000000,10000000]:
 ss={s['profile']:s for s in comparisons if s['suite']=='C_H4' and s['datasetSize']==d}
 boot={}
 for prof in ['WRITE_HEAVY','READ_HEAVY']:
  a=bootmeans([r['throughput'] for r in rows if r['suite']=='C_H4' and r['datasetSize']==d and r['profile']==prof and r['database']=='scylla'])
  b=bootmeans([r['throughput'] for r in rows if r['suite']=='C_H4' and r['datasetSize']==d and r['profile']==prof and r['database']=='cassandra'])
  boot[prof]=(a[:,None]/b).flatten()
 dist=(boot['WRITE_HEAVY'][:,None]/boot['READ_HEAVY']).flatten()
 q={'datasetSize':d,'Q':ss['WRITE_HEAVY']['throughput_S_C']/ss['READ_HEAVY']['throughput_S_C'],'bootstrap_p025':float(np.quantile(dist,.025)),'bootstrap_p975':float(np.quantile(dist,.975))}
 for rep in [1,2,3]:
  rr={(r['database'],r['profile']):r['throughput'] for r in rows if r['suite']=='C_H4' and r['datasetSize']==d and r['repetition']==rep}
  q['seed_Q_'+str(rep)]=(rr['scylla','WRITE_HEAVY']/rr['cassandra','WRITE_HEAVY'])/(rr['scylla','READ_HEAVY']/rr['cassandra','READ_HEAVY'])
 qrows.append(q)
export('h4_Q',qrows)
(OUT/'audit.json').write_text(json.dumps(audit,indent=2),encoding='utf-8')
fig,axes=plt.subplots(1,2,figsize=(11,4),layout='constrained')
ps=['READ_HEAVY','BALANCED','WRITE_HEAVY'];colors={'cassandra':'#335e9c','scylla':'#d16c32'}
for ax,D in zip(axes,[1000000,10000000]):
 for db,offset in [('cassandra',-.06),('scylla',.06)]:
  rr=[r for r in rows if r['suite']=='C_H4' and r['datasetSize']==D and r['database']==db]
  ax.scatter([ps.index(r['profile'])+offset for r in rr],[r['throughput'] for r in rr],color=colors[db],alpha=.65,label=db)
  ss={r['profile']:r for r in summary if r['suite']=='C_H4' and r['datasetSize']==D and r['database']==db}
  ax.plot([i+offset for i in range(3)],[ss[p]['throughput'] for p in ps],color=colors[db])
 ax.set(xticks=[0,1,2],xticklabels=['20% write','50% write','80% write'],ylabel='Propusnost (operacija/s)',title=f'{D//1000000}M • N3 / RF3 / C32');ax.grid(alpha=.2);ax.legend()
fig.suptitle('H4 — pojedinačna ponavljanja i grupni prosjeci')
fig.savefig(OUT/'grafikoni/h4_ponavljanja.png',dpi=180);plt.close(fig)
print(f"Analiza zavrsena: {len(rows)} testova, {len(summary)} grupa, 9 PNG grafikona.")
print(f"Nezavrseni pokusaji (izuzeti): {len(incomplete)}. Izlaz: {OUT}")
if audit['sample_hash_mismatch_groups']:raise SystemExit('Razlicite kontrolne sume uzoraka: pogledajte audit.json.')

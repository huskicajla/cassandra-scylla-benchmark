param(
    [ValidateSet('Pilot','Full','Extended','Saturation')][string]$Mode='Full',
    [string]$Name='full-v3',
    [int[]]$ExtraConcurrency=@(256,512)
)
. "$PSScriptRoot/common.ps1"
if ($Name -notmatch '^[a-z0-9-]+$') { throw 'Name: koristite mala slova, brojeve i crtice.' }
$plans=Join-Path $ResearchRoot 'plans'; New-Item -ItemType Directory -Force $plans | Out-Null
$target=Join-Path $plans "$Name.json"
if(Test-Path $target) { throw 'Plan vec postoji. Koristite drugo ime; postojeci plan se ne mijenja.' }
$cells=New-Object 'System.Collections.Generic.List[object]'
function Add-Cells([string]$Suite,[int[]]$Sizes,[int[]]$NodeCounts,[int[]]$Cs,[string[]]$Ops,[int]$Rf,[int]$Repeats) {
    foreach($d in $Sizes) {foreach($n in $NodeCounts) {foreach($c in $Cs) {foreach($op in $Ops) {foreach($rep in 1..$Repeats) {
        $profile=$op; $operation=$op; $percent=0
        if($op -eq 'CONCURRENT_WRITE') {$percent=100}
        if($op -in @('WRITE_HEAVY','BALANCED','READ_HEAVY')) {
            $operation='MIXED_WORKLOAD';$percent=@{WRITE_HEAVY=80;BALANCED=50;READ_HEAVY=20}[$op]
        }
        $order=if($rep % 2 -eq 1) {@('cassandra','scylla')} else {@('scylla','cassandra')}
        foreach($db in $order) {
            $id="$Suite-$($d/1000000)m-n$n-c$c-$profile-r$rep-$db".ToLowerInvariant()
            $cells.Add([ordered]@{id=$id;suite=$Suite;database=$db;nodes=$n;rf=$Rf;cl='LOCAL_QUORUM';datasetSize=$d;concurrency=$c;operation=$operation;profile=$profile;writePercent=$percent;repetition=$rep;seed=(20260906+$rep);warmupSeconds=60;measurementSeconds=180;minimumOperations=100000;maxOperations=20000000;maxRunSeconds=3600;settleSeconds=30;sampleSize=100000})
        }
    }}}}}
}
if($Mode -eq 'Pilot') {
    Add-Cells 'PILOT' @(1000000) @(3) @(8,32) @('CONCURRENT_WRITE','READ_SINGLE','WRITE_HEAVY','READ_TIME_RANGE') 3 1
    foreach($cell in $cells) {$cell.warmupSeconds=10;$cell.measurementSeconds=20;$cell.minimumOperations=1000;$cell.settleSeconds=10}
} elseif($Mode -eq 'Saturation') {
    Add-Cells 'A_EXTENSION' @(1000000,5000000,10000000) @(3) $ExtraConcurrency @('CONCURRENT_WRITE','READ_SINGLE') 3 5
} else {
    Add-Cells 'A_H1_H2' @(1000000,5000000,10000000) @(3) @(1,2,4,8,16,32,64,128) @('CONCURRENT_WRITE','READ_SINGLE') 3 5
    Add-Cells 'B_H3' @(1000000,10000000) @(1,2,3) @(8,16,32,64) @('CONCURRENT_WRITE','READ_SINGLE') 1 5
    Add-Cells 'C_H4' @(1000000,5000000,10000000) @(3) @(8,32,128) @('WRITE_HEAVY','BALANCED','READ_HEAVY') 3 5
    if($Mode -eq 'Extended') {Add-Cells 'D_READ' @(1000000,10000000) @(3) @(8,32,128) @('READ_LATEST','READ_PARTITION_WINDOW','READ_TIME_RANGE') 3 5}
}
# Reproducible random block order; preserve paired database order within each block.
$rng=New-Object Random 20260906
$blocks=New-Object 'System.Collections.Generic.List[object]'
for($i=0;$i -lt $cells.Count;$i+=2) {$blocks.Add(@($cells[$i],$cells[$i+1]))}
for($i=$blocks.Count-1;$i -gt 0;$i--) {$j=$rng.Next($i+1);$tmp=$blocks[$i];$blocks[$i]=$blocks[$j];$blocks[$j]=$tmp}
$ordered=@(foreach($block in $blocks) {foreach($cell in $block) {$cell}})
Write-JsonFile ([ordered]@{protocolVersion='3.0.0';name=$Name;mode=$Mode;createdAt=[DateTime]::UtcNow.ToString('O');notes='Time-based closed-loop measurements. Restore identical initial dataset for every independent run. Preserve errors and aborts. Pilot results excluded.';cells=$ordered}) $target
Write-Host "$($cells.Count) mjerenja zapisano u $target"
Write-Host 'Plan jos nije pokrenut. Full: A=480, B=480, C=270; dodatni D=180.'

param(
    [Parameter(Mandatory=$true)][string]$Plan,
    [Parameter(Mandatory=$true)][string]$DatasetPath,
    [string]$Suite='',
    [string]$CellId='',
    [int]$MaxCells=0
)
. "$PSScriptRoot/common.ps1"
$Plan=(Resolve-Path -LiteralPath $Plan).Path
$DatasetPath=(Resolve-Path -LiteralPath $DatasetPath).Path
$spec=Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json
if($spec.protocolVersion -ne '3.0.0' -or $spec.name -notmatch '^[a-z0-9-]+$') {throw 'Nepoznat plan.'}
$selected=@($spec.cells | Where-Object {(-not $Suite -or $_.suite -eq $Suite) -and (-not $CellId -or $_.id -eq $CellId)})
if($selected.Count -eq 0) {throw 'Nema trazenih konfiguracija.'}
$largest=($selected | Measure-Object nodes -Maximum).Maximum
& "$PSScriptRoot/preflight.ps1" -DatasetPath $DatasetPath -Nodes $largest
$java=Find-Java21
$jar=Join-Path $ResearchRoot 'benchmark-app-research-v3/target/benchmark-research.jar'
if(-not (Test-Path -LiteralPath $jar)) {throw 'Prvo pokrenite scripts/build.ps1.'}
$results=Join-Path $ResearchRoot "results/$($spec.name)"
New-Item -ItemType Directory -Force $results | Out-Null
$lock=$null
try {$lock=[IO.File]::Open((Join-Path $ResearchRoot 'run.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)}
catch {throw 'Druga research skripta vec radi. Pokrecite samo jednu seriju istovremeno.'}
$process=$null
try {
    $fingerprints=[ordered]@{plan=(Get-FileHash -LiteralPath $Plan -Algorithm SHA256).Hash;jar=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash;files=@()}
    $needed=[int][math]::Ceiling(($spec.cells | Measure-Object datasetSize -Maximum).Maximum/1000000)
    foreach($i in 0..($needed-1)) {
        $file=Join-Path $DatasetPath ('chunk-{0:D3}.csv' -f $i)
        $fingerprints.files+=@{name=[IO.Path]::GetFileName($file);sha256=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash}
    }
    foreach($file in Get-ChildItem (Join-Path $ResearchRoot 'compose') -Filter '*.yml' | Sort-Object Name) {
        $fingerprints.files+=@{name=$file.Name;sha256=(Get-FileHash $file.FullName -Algorithm SHA256).Hash}
    }
    $identity=Join-Path $results 'campaign-inputs.json'
    if(Test-Path $identity) {
        $old=Get-Content $identity -Raw | ConvertFrom-Json
        if($old.plan -ne $fingerprints.plan -or $old.jar -ne $fingerprints.jar) {throw 'Plan ili JAR je promijenjen. Napravite novu kampanju; ne spajajte protokole.'}
        foreach($file in $fingerprints.files) {
            $match=@($old.files | Where-Object {$_.name -eq $file.name})
            if($match.Count -ne 1 -or $match[0].sha256 -ne $file.sha256) {throw "Promijenjen dataset/compose: $($file.name). Koristite novu kampanju."}
        }
    } else {Write-JsonFile $fingerprints $identity}
    [IO.File]::WriteAllText((Join-Path $results 'docker-info.json'),(Invoke-DockerChecked @('info','--format','{{json .}}')))
    Copy-Item -LiteralPath $Plan -Destination (Join-Path $results 'plan.json') -Force
    $done=0
    foreach($cell in $selected) {
        # Planovi koriste podvlaku za grupisanje hipoteze (npr. a_h1_h2-...).
        # Dozvoli je uz postojeće mala slova, cifre i crtice.
        if($cell.id -notmatch '^[a-z0-9_-]+$') {throw 'Neispravan cell id.'}
        $cellRoot=Join-Path $results $cell.id
        New-Item -ItemType Directory -Force $cellRoot | Out-Null
        $completed=@(Get-ChildItem -LiteralPath $cellRoot -Directory | Where-Object {Test-Path (Join-Path $_.FullName 'result.json')})
        if($completed.Count -gt 0) {Write-Host "Vec zavrseno: $($cell.id)";continue}
        if($MaxCells -gt 0 -and $done -ge $MaxCells) {break}
        Assert-NoOtherContainers
        Stop-ResearchClusters
        $compose=Get-Compose $cell.database $cell.nodes
        Write-Host "Pokrecem $($cell.id)"
        $attempt=Join-Path $cellRoot ('attempt-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ'))
        New-Item -ItemType Directory -Force $attempt | Out-Null
        try {$status=Start-ResearchNodes $compose $cell.nodes}
        catch {
            $_ | Out-String | Set-Content (Join-Path $attempt 'startup-error.txt')
            try {Invoke-DockerChecked @('compose','-f',$compose,'logs','--no-color','--tail','300') | Set-Content (Join-Path $attempt 'startup-logs.txt')} catch {}
            throw
        }
        $ids=(Invoke-DockerChecked @('compose','-f',$compose,'ps','-q')) -split '\s+'
        $settings=[ordered]@{}
        $cell.PSObject.Properties | ForEach-Object {$settings[$_.Name]=$_.Value}
        $settings.output=$attempt;$settings.datasetPath=$DatasetPath
        $settings.keyspace=('research_v3_'+$spec.name.Replace('-','_')+'_'+$cell.suite.ToLowerInvariant()+'_n'+$cell.nodes+'_rf'+$cell.rf)
        if($settings.keyspace.Length -gt 48) {throw 'Ime kampanje je predugo za keyspace; koristite krace ime.'}
        $network=if($cell.database -eq 'cassandra'){40+[int]$cell.nodes}else{50+[int]$cell.nodes}
        $settings.networkPrefix="172.28.$network."
        $configPath=Join-Path $attempt 'effective-config.json';Write-JsonFile $settings $configPath
        $argLine='-Xms1g -Xmx2g -jar "'+$jar+'" "'+$configPath+'"'
        $process=Start-Process -FilePath $java -ArgumentList $argLine -WorkingDirectory $ResearchRoot -WindowStyle Hidden -PassThru
        $processHandle=$process.Handle
        $deadline=[DateTime]::UtcNow.AddHours(4)
        while(-not $process.HasExited) {
            if([DateTime]::UtcNow -gt $deadline) {throw 'Konfiguracija je prekoracila 4 sata; prekida se i cuva kao nezavrsena.'}
            $phaseFile=Join-Path $attempt 'phase.txt'
            $phase=if(Test-Path $phaseFile){Get-Content $phaseFile -Raw}else{'STARTUP'}
            $stats=Invoke-DockerChecked (@('stats','--no-stream','--format','{{json .}}')+$ids)
            $process.Refresh()
            if(-not $process.HasExited) {
                $observation=[ordered]@{at=[DateTime]::UtcNow.ToString('O');phase=$phase.Trim();clientCpuSeconds=$process.TotalProcessorTime.TotalSeconds;clientWorkingSetBytes=$process.WorkingSet64;containers=@($stats -split "`n" | Where-Object {$_} | ForEach-Object {$_ | ConvertFrom-Json})}
                [IO.File]::AppendAllText((Join-Path $attempt 'resources.jsonl'),($observation | ConvertTo-Json -Depth 8 -Compress)+[Environment]::NewLine,(New-Object Text.UTF8Encoding($false)))
            }
            Start-Sleep -Seconds 5
        }
        $process.WaitForExit();$code=$process.ExitCode;$process=$null
        Clear-ResearchSnapshots $compose $cell.database $cell.nodes $settings.keyspace
        $resultFile=Join-Path $attempt 'result.json'
        if(($null -ne $code -and $code -ne 0) -or -not (Test-Path $resultFile)) {
            throw "Prekinuto: $($cell.id). Pregledajte $attempt. Ista komanda ce nastaviti i sacuvati ovaj pokusaj."
        }
        $result=Get-Content $resultFile -Raw | ConvertFrom-Json
        $csvRow=[ordered]@{id=$cell.id;database=$cell.database;datasetSize=$cell.datasetSize;nodes=$cell.nodes;rf=$cell.rf;cl=$cell.cl;concurrency=$cell.concurrency;operation=$cell.operation;profile=$cell.profile;repetition=$cell.repetition;status=$result.status;throughput=$result.throughput;elapsedSeconds=$result.elapsedSeconds}
        foreach($kind in @('total','read','write')) {foreach($metric in @('successful','failed','meanMs','p50Ms','p95Ms','p99Ms','maxMs')) {$csvRow["${kind}_${metric}"]=$result.$kind.$metric}}
        [pscustomobject]$csvRow | Export-Csv -LiteralPath (Join-Path $attempt 'result.csv') -NoTypeInformation -Encoding UTF8
        if($result.status -eq 'INVALID_DATA') {throw "Provjera sadrzaja podataka nije prosla. Sacuvan rezultat: $resultFile. Ne koristiti ga kao dokaz performansi."}
        Write-Host "$($result.status): $([math]::Round($result.throughput,1)) ops/s; P99=$($result.total.p99Ms) ms; greske=$($result.total.failed)"
        $done++
        $null=Invoke-DockerChecked @('compose','-f',$compose,'stop')
    }
} finally {
    if($process -and -not $process.HasExited) {Stop-Process -Id $process.Id -Force}
    # Temporary phase marker is needed while sampling CPU/RAM, not after the run.
    if($attempt) {
        $phaseMarker=Join-Path $attempt 'phase.txt'
        if(Test-Path -LiteralPath $phaseMarker) {Remove-Item -LiteralPath $phaseMarker -Force}
    }
    try {Stop-ResearchClusters} catch {Write-Warning 'Research kontejneri nisu svi zaustavljeni; pokrenite stop-research.ps1.'}
    if($lock){$lock.Dispose()}
}
Write-Host 'Odabrani dio plana je zavrsen. Analizirajte rezultate; dovrsen plan ne znaci automatski potvrdjene hipoteze.'

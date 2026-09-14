$ErrorActionPreference = 'Stop'
$ResearchRoot = Split-Path -Parent $PSScriptRoot

function Find-Java21 {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME/bin/java.exe")) {
        $candidates = @("$env:JAVA_HOME/bin/java.exe")
    } else { $candidates = @() }
    $candidates += @(Get-ChildItem "$env:USERPROFILE/.jdks" -Directory -ErrorAction SilentlyContinue | ForEach-Object { Join-Path $_.FullName 'bin/java.exe' })
    $command = Get-Command java -ErrorAction SilentlyContinue
    if ($command) { $candidates += $command.Source }
    foreach ($candidate in $candidates) {
        if (-not (Test-Path -LiteralPath $candidate)) { continue }
        $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        $version = (& $candidate -version 2>&1 | Out-String)
        $ErrorActionPreference = $old
        if ($version -match 'version "21[\."]') { return (Resolve-Path $candidate).Path }
    }
    throw 'JDK 21 nije pronadjen. Postavite JAVA_HOME na Temurin JDK 21.'
}

function Invoke-DockerChecked([string[]]$Parts) {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $lines = & docker @Parts 2>&1; $code = $LASTEXITCODE } finally { $ErrorActionPreference = $old }
    if ($code -ne 0) { throw "Docker nije uspio: $($Parts -join ' ')`n$($lines | Out-String)" }
    return ($lines | Out-String).Trim()
}

function Write-JsonFile($Value, [string]$Path) {
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 30), (New-Object Text.UTF8Encoding($false)))
}

function Get-Compose([string]$Database,[int]$Nodes) {
    return (Join-Path $ResearchRoot "compose/$Database-$Nodes.yml")
}

function Assert-NoOtherContainers {
    $ids = Invoke-DockerChecked @('ps','-q')
    if (-not $ids) { return }
    $foreign = @()
    foreach ($id in ($ids -split '\s+')) {
        $item = @(Invoke-DockerChecked @('inspect',$id) | ConvertFrom-Json)[0]
        if ($item.Config.Labels.'research.owner' -ne 'cassandra-scylla-v3') { $foreign += $item.Name }
    }
    if ($foreign.Count -gt 0) {
        throw "Za izolovano mjerenje prvo rucno zaustavite druge kontejnere: $($foreign -join ', '). Skripta ih ne zaustavlja."
    }
}

function Stop-ResearchClusters {
    foreach ($db in @('cassandra','scylla')) { foreach ($n in 1..3) {
        $file = Get-Compose $db $n
        $null = Invoke-DockerChecked @('compose','-f',$file,'stop')
    } }
}

function Clear-ResearchSnapshots([string]$Compose,[string]$Database,[int]$Nodes,[string]$Keyspace) {
    if($Keyspace -notmatch '^research_v3_[a-z0-9_]+$') {throw 'Snapshot cleanup odbijen za keyspace izvan research_v3.'}
    for($i=1;$i -le $Nodes;$i++) {
        $parts=@('compose','-f',$Compose,'exec','-T',"node$i",'nodetool','clearsnapshot')
        if($Database -eq 'cassandra') {$parts+=@('--all')}
        $parts+=@($Keyspace)
        $null=Invoke-DockerChecked $parts
    }
}

function Start-ResearchNodes([string]$Compose,[int]$Nodes) {
    $existing=@((Invoke-DockerChecked @('compose','-f',$Compose,'ps','-a','-q')) -split '\s+' | Where-Object {$_})
    if($existing.Count -eq $Nodes) {
        # Existing Scylla Raft members need a quorum to restart; start them together.
        $null=Invoke-DockerChecked @('compose','-f',$Compose,'up','-d')
        return (Wait-ResearchNodes $Compose $Nodes)
    }
    for($n=1;$n -le $Nodes;$n++) {
        Write-Host "Pokrecem node$n redom; cekam UN i CQL."
        $null=Invoke-DockerChecked @('compose','-f',$Compose,'up','-d','--no-deps',"node$n")
        $ready=$false
        for($poll=0;$poll -lt 80;$poll++) {
            Assert-NodeProcess $Compose "node$n"
            $state=Invoke-DockerChecked @('compose','-f',$Compose,'ps','-a','--format','json',"node$n")
            if($state -match '"State"\s*:\s*"(exited|dead)"') {throw "node$n je prestao raditi: $state"}
            try {
                $status=Invoke-DockerChecked @('compose','-f',$Compose,'exec','-T','node1','nodetool','status')
                $binary=Invoke-DockerChecked @('compose','-f',$Compose,'exec','-T',"node$n",'nodetool','statusbinary')
                if(([regex]::Matches($status,'(?m)^UN\s+')).Count -eq $n -and $binary.Trim() -eq 'running') {$ready=$true;break}
            } catch { if($poll % 4 -eq 0) {Write-Host "node$n jos nije spreman: $_"} }
            Start-Sleep -Seconds 15
        }
        if(-not $ready) {throw "node$n nije spreman nakon 80 provjera."}
    }
    $status=Wait-ResearchNodes $Compose $Nodes
    return $status
}

function Assert-NodeProcess([string]$Compose,[string]$Node) {
    $logs=Invoke-DockerChecked @('compose','-f',$Compose,'logs','--since','2m','--no-color','--tail','80',$Node)
    if($logs -match 'Could not initialize seastar|entered FATAL state|Bootstrap Token collision|OutOfMemoryError') {
        throw "Fatalna greska na ${Node}: $logs"
    }
}

function Wait-ResearchNodes([string]$Compose,[int]$Nodes) {
    for ($i=0;$i -lt 80;$i++) {
        foreach($n in 1..$Nodes) {Assert-NodeProcess $Compose "node$n"}
        try {
            $status=Invoke-DockerChecked @('compose','-f',$Compose,'exec','-T','node1','nodetool','status')
            $up=([regex]::Matches($status,'(?m)^UN\s+')).Count
            $known=([regex]::Matches($status,'(?m)^[UD][NLJM]\s+')).Count
            if ($up -eq $Nodes -and $known -eq $Nodes) {
                $allReady=$true
                foreach($n in 1..$Nodes) {
                    $binary=Invoke-DockerChecked @('compose','-f',$Compose,'exec','-T',"node$n",'nodetool','statusbinary')
                    if($binary.Trim() -ne 'running') {$allReady=$false}
                }
                if($allReady){return $status}
            }
        } catch { if ($i % 4 -eq 0) { Write-Host 'Cekam spremnost klastera...' } }
        Start-Sleep -Seconds 15
    }
    throw 'Klaster nije dostigao ocekivani broj UN cvorova u 20 minuta.'
}

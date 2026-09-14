param(
    [ValidateRange(1, 5)]
    [int]$MaxAttempts = 3,
    [ValidateRange(1, 30)]
    [int]$CooldownMinutes = 10
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$resultsRoot = Join-Path $projectRoot 'results'
$eventsFile = Join-Path $resultsRoot 'experiment-events-v1.csv'
$logFile = Join-Path $resultsRoot 'PRELOADED-SUPERVISOR-LOG.txt'
$runnerScript = Join-Path $PSScriptRoot 'run-preloaded-test.ps1'
$summaryScript = Join-Path $PSScriptRoot 'generate-resource-summary.ps1'
$cassandraCompose = 'C:\cassandra-scylla-benchmark\cassandra\docker-compose.yml'
$scyllaCompose = 'C:\cassandra-scylla-benchmark\scylladb\docker-compose.yml'

function Log([string]$message) {
    Add-Content -LiteralPath $logFile -Encoding utf8 -Value (
            '[{0:O}] {1}' -f [DateTime]::UtcNow, $message)
}

function Get-ActiveBenchmarkProcess {
    return @(Get-CimInstance Win32_Process | Where-Object {
        $_.CommandLine -like '*run-preloaded-test.ps1*' -or
        ($_.CommandLine -like '*spring-boot:run*' -and
            $_.CommandLine -like '*benchmark-app-preloaded-v1*')
    })
}

function Get-LatestEvent([string]$database, [string]$series) {
    if (-not (Test-Path -LiteralPath $eventsFile)) { return $null }
    return @(Import-Csv -LiteralPath $eventsFile | Where-Object {
        $_.database -eq $database -and $_.test_series -eq $series
    } | Select-Object -Last 1) | Select-Object -First 1
}

function Start-Profile([string]$database) {
    Log "Starting $database profile."
    Start-Process -FilePath powershell.exe -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $runnerScript,
        '-Database', $database, '-Topology', '1m'
    ) -WorkingDirectory $projectRoot -WindowStyle Hidden | Out-Null
    Start-Sleep -Seconds 5
}

function Wait-ForProfile([string]$database, [string]$series, [bool]$adoptActive) {
    $attempt = 0
    if ($adoptActive -and (Get-ActiveBenchmarkProcess).Count -gt 0) {
        $attempt = 1
        Log "Adopting already-running $database attempt."
    }
    while ($attempt -lt $MaxAttempts) {
        if ((Get-ActiveBenchmarkProcess).Count -eq 0) {
            $attempt++
            Start-Profile $database
        }
        while ((Get-ActiveBenchmarkProcess).Count -gt 0) {
            Start-Sleep -Seconds 60
        }
        $event = Get-LatestEvent $database $series
        if ($null -ne $event -and $event.event_type -eq 'COMPLETED') {
            Log "$database completed cleanly: $($event.experiment_id)."
            return $event.experiment_id
        }
        $detail = if ($null -eq $event) { 'no completed experiment event' } else { $event.detail }
        Log "$database attempt $attempt did not complete: $detail"
        if ($attempt -ge $MaxAttempts) { break }
        Log "Cooling down for $CooldownMinutes minute(s) before retry."
        Start-Sleep -Seconds ($CooldownMinutes * 60)
    }
    throw "$database exhausted $MaxAttempts attempt(s) without a valid COMPLETED experiment."
}

function Wait-ForScylla {
    for ($attempt = 1; $attempt -le 60; $attempt++) {
        $status = (& docker exec scylla-node1 nodetool status 2>&1 | Out-String)
        $upNodes = ([regex]::Matches($status, '(?m)^UN\s+')).Count
        if ($LASTEXITCODE -eq 0 -and $upNodes -ge 3) {
            Log 'Three ScyllaDB nodes are UN.'
            return
        }
        Log "ScyllaDB readiness $attempt/60: $upNodes UN nodes."
        Start-Sleep -Seconds 30
    }
    throw 'ScyllaDB did not reach three healthy nodes within 30 minutes.'
}

try {
    Log 'Preloaded paired supervisor started.'
    $cassandraId = Wait-ForProfile 'cassandra' 'PRELOADED_1M_CASSANDRA_RAPID_V2' $true
    & $summaryScript -ExperimentId $cassandraId

    Log 'Cassandra validly completed; stopping Cassandra containers (volumes remain intact).'
    & docker compose -f $cassandraCompose down
    if ($LASTEXITCODE -ne 0) { throw "Cassandra stop returned $LASTEXITCODE." }

    Log 'Starting matching ScyllaDB three-node cluster.'
    & docker compose -f $scyllaCompose up -d
    if ($LASTEXITCODE -ne 0) { throw "ScyllaDB start returned $LASTEXITCODE." }
    Wait-ForScylla

    $scyllaId = Wait-ForProfile 'scylla' 'PRELOADED_1M_SCYLLA_RAPID_V2' $false
    & $summaryScript -ExperimentId $scyllaId
    Log "Paired preloaded study completed: Cassandra=$cassandraId; ScyllaDB=$scyllaId."
} catch {
    Log ('SUPERVISOR FAILED: ' + $_.Exception.Message)
    exit 1
}

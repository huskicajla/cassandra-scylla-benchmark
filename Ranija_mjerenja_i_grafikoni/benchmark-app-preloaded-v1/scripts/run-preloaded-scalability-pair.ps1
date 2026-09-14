param(
    [ValidateRange(1, 3)]
    [int]$MaxAttempts = 3,
    [ValidateRange(1, 30)]
    [int]$CooldownMinutes = 10
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$resultsRoot = Join-Path $projectRoot 'results'
$eventsFile = Join-Path $resultsRoot 'experiment-events-v1.csv'
$logFile = Join-Path $resultsRoot 'PRELOADED-H3-SUPERVISOR-LOG.txt'
$runnerScript = Join-Path $PSScriptRoot 'run-preloaded-test.ps1'
$summaryScript = Join-Path $PSScriptRoot 'generate-resource-summary.ps1'
$cassandraThreeNodeCompose = 'C:\cassandra-scylla-benchmark\cassandra\docker-compose.yml'
$scyllaThreeNodeCompose = 'C:\cassandra-scylla-benchmark\scylladb\docker-compose.yml'
$cassandraScaleOneCompose = 'C:\cassandra-scylla-benchmark\cassandra\docker-compose.scale-1.yml'
$scyllaScaleOneCompose = 'C:\cassandra-scylla-benchmark\scylladb\docker-compose.scale-1.yml'

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

function Wait-ForUpNode([string]$container, [string]$label) {
    for ($attempt = 1; $attempt -le 60; $attempt++) {
        $savedErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $status = (& docker exec $container nodetool status 2>&1 | Out-String)
            $nodeExitCode = $LASTEXITCODE
        } catch {
            $status = $_.Exception.Message
            $nodeExitCode = 1
        } finally {
            $ErrorActionPreference = $savedErrorActionPreference
        }
        $upNodes = ([regex]::Matches($status, '(?m)^UN\s+')).Count
        $knownNodes = ([regex]::Matches($status, '(?m)^(?:UN|DN|UL|DL|UM|DM|UJ|DJ)\s+')).Count
        if ($nodeExitCode -eq 0 -and $upNodes -eq 1 -and $knownNodes -eq 1) {
            Log "$label isolated one-node topology is ready (exactly one UP node reported)."
            return
        }
        Log "$label one-node readiness $attempt/60: $upNodes UP node(s), $knownNodes known node(s)."
        Start-Sleep -Seconds 30
    }
    throw "$label did not reach an UP node within 30 minutes."
}

function Start-Profile([string]$database) {
    Log "Starting $database one-node profile."
    Start-Process -FilePath powershell.exe -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $runnerScript,
        '-Database', $database, '-Topology', '1node-1m'
    ) -WorkingDirectory $projectRoot -WindowStyle Hidden | Out-Null
    Start-Sleep -Seconds 5
}

function Run-Profile([string]$database, [string]$series) {
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        if ((Get-ActiveBenchmarkProcess).Count -gt 0) {
            throw 'A benchmark process was already running before the H3 profile started.'
        }
        Start-Profile $database
        while ((Get-ActiveBenchmarkProcess).Count -gt 0) {
            Start-Sleep -Seconds 60
        }
        $event = Get-LatestEvent $database $series
        if ($null -ne $event -and $event.event_type -eq 'COMPLETED') {
            Log "$database one-node experiment completed: $($event.experiment_id)."
            return $event.experiment_id
        }
        $detail = if ($null -eq $event) { 'no lifecycle result found' } else { $event.detail }
        Log "$database one-node attempt $attempt/$MaxAttempts invalid: $detail"
        if ($attempt -lt $MaxAttempts) {
            Log "Cooling down for $CooldownMinutes minute(s) before retry."
            Start-Sleep -Seconds ($CooldownMinutes * 60)
        }
    }
    throw "$database exhausted $MaxAttempts H3 attempts without a valid result."
}

try {
    Log 'H3 one-node paired supervisor started.'

    Log 'Stopping regular three-node Cassandra and ScyllaDB containers; all volumes are retained.'
    & docker compose -f $cassandraThreeNodeCompose down
    if ($LASTEXITCODE -ne 0) { throw "Regular Cassandra stop returned $LASTEXITCODE." }
    & docker compose -f $scyllaThreeNodeCompose down
    if ($LASTEXITCODE -ne 0) { throw "Regular ScyllaDB stop returned $LASTEXITCODE." }

    Log 'Starting the isolated Cassandra scale-1 node.'
    & docker compose -f $cassandraScaleOneCompose up -d cassandra-scale1-node1
    if ($LASTEXITCODE -ne 0) { throw "Cassandra scale-1 start returned $LASTEXITCODE." }
    Wait-ForUpNode 'cassandra-scale1-node1' 'Cassandra'
    $cassandraId = Run-Profile 'cassandra' 'PRELOADED_1M_CASSANDRA_1NODE_SCALABILITY_V2'
    & $summaryScript -ExperimentId $cassandraId

    Log 'Stopping the isolated Cassandra scale-1 node; its volume is retained.'
    & docker compose -f $cassandraScaleOneCompose down
    if ($LASTEXITCODE -ne 0) { throw "Cassandra scale-1 stop returned $LASTEXITCODE." }

    Log 'Starting the isolated ScyllaDB scale-1 node.'
    & docker compose -f $scyllaScaleOneCompose up -d scylla-scale1-node1
    if ($LASTEXITCODE -ne 0) { throw "ScyllaDB scale-1 start returned $LASTEXITCODE." }
    Wait-ForUpNode 'scylla-scale1-node1' 'ScyllaDB'
    $scyllaId = Run-Profile 'scylla' 'PRELOADED_1M_SCYLLA_1NODE_SCALABILITY_V2'
    & $summaryScript -ExperimentId $scyllaId

    Log "H3 pair completed: Cassandra=$cassandraId; ScyllaDB=$scyllaId."
} catch {
    Log ('H3 SUPERVISOR FAILED: ' + $_.Exception.Message)
    exit 1
}

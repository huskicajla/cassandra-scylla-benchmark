param(
    [ValidateRange(1, 3)]
    [int]$MaxAttempts = 2,
    [ValidateRange(1, 30)]
    [int]$CooldownMinutes = 10
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$resultsRoot = Join-Path $projectRoot 'results'
$eventsFile = Join-Path $resultsRoot 'experiment-events-v1.csv'
$logFile = Join-Path $resultsRoot 'H3-TEST-LOG.txt'
$runnerScript = Join-Path $PSScriptRoot 'run-h3-test.ps1'
$summaryScript = Join-Path $PSScriptRoot 'generate-resource-summary.ps1'
$cassandraOneNode = 'C:\cassandra-scylla-benchmark\cassandra\docker-compose.scale-1.yml'
$cassandraThreeNodes = 'C:\cassandra-scylla-benchmark\cassandra\docker-compose.yml'
$scyllaOneNode = 'C:\cassandra-scylla-benchmark\scylladb\docker-compose.scale-1.yml'
$scyllaThreeNodes = 'C:\cassandra-scylla-benchmark\scylladb\docker-compose.yml'
$env:COMPOSE_IGNORE_ORPHANS = 'true'

function Log([string]$message) {
    $line = '[{0:O}] {1}' -f [DateTime]::UtcNow, $message
    Add-Content -LiteralPath $logFile -Encoding utf8 -Value $line
    Write-Host $line
}

function Invoke-Docker([string[]]$commandParts, [string]$failureMessage) {
    $savedErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $dockerOutput = & docker @commandParts 2>&1
        $dockerExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorActionPreference
    }
    $dockerOutput | ForEach-Object { Write-Host $_ }
    if ($dockerExitCode -ne 0) {
        throw "$failureMessage Docker exit code: $dockerExitCode."
    }
}

function Stop-AllClusters {
    Invoke-Docker @('compose', '-f', $cassandraOneNode, 'down') 'Stopping one-node Cassandra failed.'
    Invoke-Docker @('compose', '-f', $scyllaOneNode, 'down') 'Stopping one-node ScyllaDB failed.'
    Invoke-Docker @('compose', '-f', $cassandraThreeNodes, 'down') 'Stopping three-node Cassandra failed.'
    Invoke-Docker @('compose', '-f', $scyllaThreeNodes, 'down') 'Stopping three-node ScyllaDB failed.'
}

function Wait-ForNodes([string]$container, [string]$label, [int]$expectedNodes) {
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
        Log "$label readiness $attempt/60: $upNodes UP, $knownNodes known."
        if ($nodeExitCode -eq 0 -and $upNodes -eq $expectedNodes -and $knownNodes -eq $expectedNodes) {
            return
        }
        Start-Sleep -Seconds 30
    }
    throw "$label did not reach exactly $expectedNodes healthy node(s) within 30 minutes."
}

function Get-LatestEvent([string]$database, [string]$series, [datetime]$notBefore) {
    if (-not (Test-Path -LiteralPath $eventsFile)) {
        return $null
    }
    return @(Import-Csv -LiteralPath $eventsFile | Where-Object {
        $_.database -eq $database -and
        $_.test_series -eq $series -and
        [DateTime]::Parse($_.event_at_utc).ToUniversalTime() -ge $notBefore
    } | Select-Object -Last 1) | Select-Object -First 1
}

function Run-Profile([string]$database, [int]$nodeCount, [string]$series) {
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        $startedAt = [DateTime]::UtcNow.AddSeconds(-5)
        Log "Starting $database with $nodeCount node(s), attempt $attempt/$MaxAttempts."
        $process = Start-Process -FilePath powershell.exe -ArgumentList @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $runnerScript,
            '-Database', $database, '-NodeCount', $nodeCount
        ) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru
        $process.WaitForExit()

        $event = Get-LatestEvent $database $series $startedAt
        $valid = $process.ExitCode -eq 0 -and
            $null -ne $event -and
            $event.event_type -eq 'COMPLETED' -and
            $event.cluster_node_count -eq $nodeCount.ToString() -and
            $event.replication_factor -eq '1'

        if ($valid) {
            Log "$database with $nodeCount node(s) completed: $($event.experiment_id)."
            & $summaryScript -ExperimentId $event.experiment_id |
                    ForEach-Object { Write-Host $_ }
            return $event.experiment_id
        }

        $detail = if ($null -eq $event) {
            "runner exit code $($process.ExitCode), no matching lifecycle event"
        } else {
            "runner exit code $($process.ExitCode), event=$($event.event_type), nodes=$($event.cluster_node_count), RF=$($event.replication_factor), detail=$($event.detail)"
        }
        Log "$database with $nodeCount node(s) was invalid: $detail"
        if ($attempt -lt $MaxAttempts) {
            Log "Cooling down for $CooldownMinutes minute(s)."
            Start-Sleep -Seconds ($CooldownMinutes * 60)
        }
    }
    throw "$database with $nodeCount node(s) exhausted $MaxAttempts attempt(s)."
}

function Run-Configuration(
    [string]$database,
    [int]$nodeCount,
    [string]$composeFile,
    [string]$service,
    [string]$container,
    [string]$series
) {
    Log "Preparing $database with $nodeCount node(s)."
    $upCommand = @('compose', '-f', $composeFile, 'up', '-d')
    if (-not [string]::IsNullOrWhiteSpace($service)) {
        $upCommand += $service
    }
    Invoke-Docker $upCommand "Starting $database with $nodeCount node(s) failed."
    Wait-ForNodes $container "$database $nodeCount-node" $nodeCount
    $experimentId = Run-Profile $database $nodeCount $series
    Invoke-Docker @('compose', '-f', $composeFile, 'down') "Stopping $database with $nodeCount node(s) failed."
    return $experimentId
}

try {
    Log 'Complete controlled H3 test started.'
    Stop-AllClusters

    $cassandraOneId = Run-Configuration 'cassandra' 1 $cassandraOneNode 'cassandra-scale1-node1' 'cassandra-scale1-node1' 'H3_CASSANDRA_1_NODE_RF1_V1'
    $scyllaOneId = Run-Configuration 'scylla' 1 $scyllaOneNode 'scylla-scale1-node1' 'scylla-scale1-node1' 'H3_SCYLLA_1_NODE_RF1_V1'
    $cassandraThreeId = Run-Configuration 'cassandra' 3 $cassandraThreeNodes '' 'cassandra-node1' 'H3_CASSANDRA_3_NODES_RF1_V1'
    $scyllaThreeId = Run-Configuration 'scylla' 3 $scyllaThreeNodes '' 'scylla-node1' 'H3_SCYLLA_3_NODES_RF1_V1'

    Log "Complete controlled H3 test finished: Cassandra1=$cassandraOneId; Scylla1=$scyllaOneId; Cassandra3=$cassandraThreeId; Scylla3=$scyllaThreeId."
} catch {
    Log ('H3 TEST FAILED: ' + $_.Exception.Message)
    try {
        Stop-AllClusters
    } catch {
        Log ('Cluster cleanup also failed: ' + $_.Exception.Message)
    }
    exit 1
}

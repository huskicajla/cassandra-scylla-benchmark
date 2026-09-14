param(
    [ValidateSet(1, 5, 10)]
    [int[]]$DatasetSizesMillion = @(1, 5, 10)
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$resultsRoot = Join-Path $projectRoot 'results'
$eventsFile = Join-Path $resultsRoot 'experiment-events-v1.csv'
$logFile = Join-Path $resultsRoot 'benchmark-execution.log'
$pairRunner = Join-Path $PSScriptRoot 'run-benchmark-pair.ps1'
$resourceSummary = Join-Path $PSScriptRoot 'generate-resource-summary.ps1'
$cassandraCompose = 'C:\cassandra-scylla-benchmark\cassandra\docker-compose.yml'
$scyllaCompose = 'C:\cassandra-scylla-benchmark\scylladb\docker-compose.yml'
$javaHome = 'C:\Users\HP\.jdks\temurin-21.0.12'

function Write-RunLog {
    param([string]$Message)
    Add-Content -LiteralPath $logFile -Encoding utf8 -Value (
        '[{0:O}] {1}' -f [DateTime]::UtcNow, $Message
    )
}

function Wait-ForThreeCassandraNodes {
    for ($attempt = 1; $attempt -le 60; $attempt++) {
        try {
            $status = (& docker exec cassandra-node1 nodetool status 2>&1 | Out-String)
            $exitCode = $LASTEXITCODE
        } catch {
            $status = $_.Exception.Message
            $exitCode = 1
        }

        $upNodes = ([regex]::Matches($status, '(?m)^UN\s+')).Count
        if ($exitCode -eq 0 -and $upNodes -ge 3) {
            Write-RunLog 'Cassandra cluster is ready with all three nodes UN.'
            return
        }

        Write-RunLog "Cassandra is not ready (attempt $attempt/60; UN nodes: $upNodes)."
        Start-Sleep -Seconds 30
    }

    throw 'Cassandra did not reach three UN nodes within 30 minutes.'
}

function Get-CompletedExperimentIds {
    param([DateTime]$NotBefore, [string]$DatasetLabel)

    $expectedSeries = @(
        "FINAL_${DatasetLabel}_CASSANDRA_OPTIMIZED_V2",
        "FINAL_${DatasetLabel}_SCYLLA_OPTIMIZED_V2"
    )

    return @(Import-Csv -LiteralPath $eventsFile |
        Where-Object {
            $_.event_type -eq 'COMPLETED' -and
            $_.test_series -in $expectedSeries -and
            [DateTime]::Parse($_.event_at_utc).ToUniversalTime() -ge $NotBefore
        } |
        Group-Object experiment_id |
        ForEach-Object { $_.Name })
}

try {
    $env:JAVA_HOME = $javaHome
    $env:Path = "$javaHome\bin;$env:Path"
    New-Item -ItemType Directory -Path $resultsRoot -Force | Out-Null

    Write-RunLog "Complete benchmark suite started for dataset sizes: $($DatasetSizesMillion -join ', ')M."

    foreach ($size in $DatasetSizesMillion) {
        $label = "${size}M"
        Write-RunLog "Preparing the $label Cassandra-ScyllaDB pair."

        & docker compose -f $scyllaCompose down
        if ($LASTEXITCODE -ne 0) {
            throw "ScyllaDB shutdown failed before the $label pair."
        }

        & docker compose -f $cassandraCompose up -d
        if ($LASTEXITCODE -ne 0) {
            throw "Cassandra startup failed before the $label pair."
        }

        Wait-ForThreeCassandraNodes
        $pairStartedAt = [DateTime]::UtcNow

        & powershell.exe -NoProfile -ExecutionPolicy Bypass `
            -File $pairRunner `
            -DatasetSizeMillion $size

        if ($LASTEXITCODE -ne 0) {
            throw "$label benchmark pair failed with exit code $LASTEXITCODE. The suite will not continue."
        }

        $completedIds = Get-CompletedExperimentIds $pairStartedAt $label
        if ($completedIds.Count -ne 2) {
            throw "$label pair finished without exactly two new COMPLETED experiment IDs."
        }

        & $resourceSummary -ExperimentId $completedIds
        Write-RunLog "$label pair completed and both resource summaries were generated."
    }

    Write-RunLog 'Complete benchmark suite finished successfully for 1M, 5M and 10M.'
} catch {
    Write-RunLog ('COMPLETE BENCHMARK SUITE FAILED: ' + $_.Exception.Message)
    exit 1
}

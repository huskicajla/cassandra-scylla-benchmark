param(
    [ValidateSet(1, 5, 10)]
    [int]$DatasetSizeMillion = 1
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$resultsRoot = Join-Path $projectRoot 'results'
$eventsFile = Join-Path $resultsRoot 'experiment-events-v1.csv'
$logFile = Join-Path $resultsRoot 'benchmark-execution.log'
$datasetLabel = "${DatasetSizeMillion}M"
$cassandraConsoleLog = Join-Path $resultsRoot "benchmark_cassandra_${datasetLabel}.log"
$scyllaConsoleLog = Join-Path $resultsRoot "benchmark_scylla_${datasetLabel}.log"
$cassandraProfile = "experiment-cassandra-$($DatasetSizeMillion)m"
$scyllaProfile = "experiment-scylla-$($DatasetSizeMillion)m"
$cassandraTestSeries = "FINAL_${datasetLabel}_CASSANDRA_OPTIMIZED_V2"
$scyllaTestSeries = "FINAL_${datasetLabel}_SCYLLA_OPTIMIZED_V2"
$cassandraCompose = 'C:\cassandra-scylla-benchmark\cassandra\docker-compose.yml'
$scyllaCompose = 'C:\cassandra-scylla-benchmark\scylladb\docker-compose.yml'
$javaHome = 'C:\Users\HP\.jdks\temurin-21.0.12'
$maxAttempts = 3
$cooldownSeconds = 600

function Write-RunLog {
    param([string]$Message)
    $line = '[{0:O}] {1}' -f [DateTime]::UtcNow, $Message
    Add-Content -LiteralPath $logFile -Value $line -Encoding utf8
}

function Get-ExperimentIds {
    if (-not (Test-Path -LiteralPath $eventsFile)) {
        return @()
    }
    return @(Import-Csv -LiteralPath $eventsFile |
            ForEach-Object { $_.experiment_id })
}

function Get-NewLifecycleEvent {
    param([string[]]$ExistingIds, [string]$Database, [string]$TestSeries)

    $deadline = [DateTime]::UtcNow.AddMinutes(2)
    while ([DateTime]::UtcNow -lt $deadline) {
        $events = @(Import-Csv -LiteralPath $eventsFile)
        $newEvents = $events |
                Where-Object {
                    $_.database -eq $Database -and
                    $_.test_series -eq $TestSeries -and
                    $_.experiment_id -notin $ExistingIds
                }
        $started = $newEvents | Where-Object { $_.event_type -eq 'STARTED' } |
                Select-Object -Last 1
        if ($null -ne $started) {
            return $started
        }
        Start-Sleep -Seconds 5
    }
    return $null
}

function Get-LatestEventForExperiment {
    param([string]$ExperimentId)
    return @(Import-Csv -LiteralPath $eventsFile |
            Where-Object { $_.experiment_id -eq $ExperimentId }) |
            Select-Object -Last 1
}

function Wait-ForCooldown {
    param([int]$Seconds)
    $remaining = $Seconds
    while ($remaining -gt 0) {
        $slice = [Math]::Min(60, $remaining)
        Start-Sleep -Seconds $slice
        $remaining -= $slice
    }
}

function Start-ProfileAttempt {
    param(
        [string]$Database,
        [string]$Profile,
        [string]$TestSeries,
        [string]$ConsoleLog
    )

    for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
        $existingIds = Get-ExperimentIds
        Write-RunLog "$Database attempt $attempt/$maxAttempts starting with profile $Profile."

        Push-Location $projectRoot
        try {
            & .\mvnw.cmd "-Dspring-boot.run.profiles=$Profile" spring-boot:run 2>&1 |
                    Tee-Object -FilePath $ConsoleLog -Append
            $exitCode = $LASTEXITCODE
        } finally {
            Pop-Location
        }

        $started = Get-NewLifecycleEvent $existingIds $Database $TestSeries
        if ($null -eq $started) {
            throw "$Database attempt $attempt did not create a STARTED audit event."
        }

        $finalEvent = Get-LatestEventForExperiment $started.experiment_id
        $status = if ($null -eq $finalEvent) { 'UNKNOWN' } else { $finalEvent.event_type }
        Write-RunLog "$Database attempt $attempt ended with process exit $exitCode and audit status $status for $($started.experiment_id)."

        if ($exitCode -eq 0 -and $status -eq 'COMPLETED') {
            return $true
        }

        if ($finalEvent.detail -match 'Application stopped before experiment completion') {
            Write-RunLog "$Database attempt was manually stopped. Automatic retry is disabled."
            return $false
        }

        if ($attempt -lt $maxAttempts) {
            Write-RunLog "$Database attempt failed; waiting $cooldownSeconds seconds for cluster recovery before retry."
            Wait-ForCooldown $cooldownSeconds
        }
    }

    Write-RunLog "$Database exhausted $maxAttempts attempts without a valid COMPLETED experiment."
    return $false
}

try {
    $env:JAVA_HOME = $javaHome
    $env:Path = "$javaHome\bin;$env:Path"
    Write-RunLog "Today supervisor started for ${datasetLabel}: strict quality gate enabled, maximum retries per database: $maxAttempts."

    $cassandraSucceeded = Start-ProfileAttempt `
            'cassandra' `
            $cassandraProfile `
            $cassandraTestSeries `
            $cassandraConsoleLog

    if (-not $cassandraSucceeded) {
        Write-RunLog 'Cassandra did not complete cleanly. ScyllaDB will not run because a fair paired comparison is unavailable.'
        exit 2
    }

    Write-RunLog 'Cassandra completed cleanly. Stopping only Cassandra Compose services; volumes are retained.'
    & docker compose -f $cassandraCompose down
    if ($LASTEXITCODE -ne 0) {
        throw "Cassandra Compose stop failed with exit code $LASTEXITCODE."
    }

    Write-RunLog 'Starting matching three-node ScyllaDB cluster.'
    $savedErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $scyllaStartOutput = (& docker compose -f $scyllaCompose up -d 2>&1 | Out-String)
        $scyllaStartExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedErrorActionPreference
    }
    if ($scyllaStartExitCode -ne 0) {
        Write-RunLog "ScyllaDB Compose returned exit code $scyllaStartExitCode; checking actual node readiness before treating the handoff as failed. Detail: $($scyllaStartOutput.Trim())"
    }

    $ready = $false
    for ($attempt = 1; $attempt -le 60; $attempt++) {
        try {
            $status = (& docker exec scylla-node1 nodetool status 2>&1 | Out-String)
            $nodeCommandExitCode = $LASTEXITCODE
        } catch {
            $status = $_.Exception.Message
            $nodeCommandExitCode = 1
        }
        $upNodes = ([regex]::Matches($status, '(?m)^UN\s+')).Count
        if ($nodeCommandExitCode -eq 0 -and $upNodes -ge 3) {
            $ready = $true
            Write-RunLog 'All three ScyllaDB nodes report UN.'
            break
        }
        Write-RunLog "ScyllaDB not ready (attempt $attempt/60; UN nodes: $upNodes)."
        Start-Sleep -Seconds 30
    }

    if (-not $ready) {
        throw 'ScyllaDB did not reach three UN nodes within 30 minutes.'
    }

    $scyllaSucceeded = Start-ProfileAttempt `
            'scylla' `
            $scyllaProfile `
            $scyllaTestSeries `
            $scyllaConsoleLog

    if (-not $scyllaSucceeded) {
        exit 3
    }

    Write-RunLog 'Cassandra and ScyllaDB both completed valid optimized series.'
} catch {
    Write-RunLog ("TODAY SUPERVISOR FAILED: " + $_.Exception.Message)
    exit 1
}

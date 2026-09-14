param(
    [Parameter(Mandatory = $true)]
    [string[]]$ExperimentId
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$resultsRoot = Join-Path $projectRoot 'results'
$eventsFile = Join-Path $resultsRoot 'experiment-events-v1.csv'
$resourceFile = Join-Path $resultsRoot 'resource-usage-v1.csv'
$summaryRoot = Join-Path $resultsRoot 'resource-summaries'
$logFile = Join-Path $resultsRoot 'benchmark-execution.log'

function Write-RunLog {
    param([string]$Message)
    Add-Content -LiteralPath $logFile -Encoding utf8 -Value (
        '[{0:O}] {1}' -f [DateTime]::UtcNow, $Message
    )
}

function Get-Number {
    param($Value)
    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace([string]$Value)) {
        return 0.0
    }
    return [double]::Parse([string]$Value, [System.Globalization.CultureInfo]::InvariantCulture)
}

function Get-Average {
    param([object[]]$Values)
    if ($Values.Count -eq 0) { return 0.0 }
    return [double](($Values | Measure-Object -Average).Average)
}

function Get-Maximum {
    param([object[]]$Values)
    if ($Values.Count -eq 0) { return 0.0 }
    return [double](($Values | Measure-Object -Maximum).Maximum)
}

function Format-Value {
    param([double]$Value, [string]$Unit)
    return ('{0:N2} {1}' -f $Value, $Unit)
}

New-Item -ItemType Directory -Path $summaryRoot -Force | Out-Null
$events = @(Import-Csv -LiteralPath $eventsFile)
$resourceRows = @(Import-Csv -LiteralPath $resourceFile)

foreach ($id in $ExperimentId | Select-Object -Unique) {
    $experimentEvents = @($events | Where-Object { $_.experiment_id -eq $id })
    if ($experimentEvents.Count -eq 0) {
        throw "No audit events were found for experiment $id."
    }

    $firstEvent = $experimentEvents | Select-Object -First 1
    $lastEvent = $experimentEvents | Select-Object -Last 1
    $rows = @($resourceRows | Where-Object { $_.experiment_id -eq $id })
    $snapshotGroups = @($rows | Group-Object captured_at_utc)
    $hostCpu = @($snapshotGroups | ForEach-Object { Get-Number $_.Group[0].host_cpu_percent })
    $hostRam = @($snapshotGroups | ForEach-Object { Get-Number $_.Group[0].host_memory_used_mb })
    $databaseCpuPerSnapshot = @($snapshotGroups | ForEach-Object {
            [double](($_.Group | ForEach-Object { Get-Number $_.container_cpu_percent } | Measure-Object -Sum).Sum)
    })
    $databaseRamPerSnapshotMb = @($snapshotGroups | ForEach-Object {
            [double](($_.Group | ForEach-Object { (Get-Number $_.container_memory_bytes) / 1MB } | Measure-Object -Sum).Sum)
    })

    $lines = @(
        'DATABASE RESOURCE SUMMARY'
        "Experiment ID: $id"
        "Database: $($firstEvent.database)"
        "Test series: $($firstEvent.test_series)"
        "Experiment status: $($lastEvent.event_type)"
        "Experiment started (UTC): $($firstEvent.event_at_utc)"
        "Experiment finished (UTC): $($lastEvent.event_at_utc)"
        "Resource samples: $($snapshotGroups.Count) snapshots / $($rows.Count) container rows"
        ''
        'HOST (Windows laptop)'
        "Average CPU: $(Format-Value (Get-Average $hostCpu) '%')"
        "Peak CPU: $(Format-Value (Get-Maximum $hostCpu) '%')"
        "Average RAM used: $(Format-Value (Get-Average $hostRam) 'MB')"
        "Peak RAM used: $(Format-Value (Get-Maximum $hostRam) 'MB')"
        ''
        'DATABASE CONTAINERS (all three database nodes combined)'
        "Average CPU: $(Format-Value (Get-Average $databaseCpuPerSnapshot) '%')"
        "Peak CPU: $(Format-Value (Get-Maximum $databaseCpuPerSnapshot) '%')"
        "Average RAM used: $(Format-Value (Get-Average $databaseRamPerSnapshotMb) 'MB')"
        "Peak RAM used: $(Format-Value (Get-Maximum $databaseRamPerSnapshotMb) 'MB')"
        ''
        'PER-NODE DATABASE PROCESS USAGE'
    )

    foreach ($node in @($rows | Group-Object container_name | Sort-Object Name)) {
        $nodeCpu = @($node.Group | ForEach-Object { Get-Number $_.container_cpu_percent })
        $nodeRamMb = @($node.Group | ForEach-Object { (Get-Number $_.container_memory_bytes) / 1MB })
        $lines += "$($node.Name): average CPU $(Format-Value (Get-Average $nodeCpu) '%'); peak CPU $(Format-Value (Get-Maximum $nodeCpu) '%'); average RAM $(Format-Value (Get-Average $nodeRamMb) 'MB'); peak RAM $(Format-Value (Get-Maximum $nodeRamMb) 'MB')"
    }

    $lines += @(
        ''
        'Raw network I/O and block I/O samples are preserved in ../resource-usage-v1.csv.'
        'Sampling interval: 60 seconds. CPU is Docker CPU percent; RAM is container resident memory reported by Docker.'
    )

    $outputFile = Join-Path $summaryRoot "$id-resources.txt"
    Set-Content -LiteralPath $outputFile -Value $lines -Encoding utf8
    Write-RunLog "Resource summary written for ${id}: $outputFile"
}

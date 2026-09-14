param(
    [ValidateRange(1, 48)]
    [int]$DurationHours = 14
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$resultsRoot = Join-Path $projectRoot 'results'
$eventsFile = Join-Path $resultsRoot 'experiment-events-v1.csv'
$resourceFile = Join-Path $resultsRoot 'resource-usage-v1.csv'
$logFile = Join-Path $resultsRoot 'benchmark-execution.log'
$deadline = [DateTime]::UtcNow.AddHours($DurationHours)

function Write-RunLog {
    param([string]$Message)
    $line = '[{0:O}] {1}' -f [DateTime]::UtcNow, $Message
    Add-Content -LiteralPath $logFile -Value $line -Encoding utf8
}

function Convert-SizeToBytes {
    param([string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return 0
    }

    $match = [regex]::Match($Value.Trim(), '^([0-9]+(?:\.[0-9]+)?)\s*([KMGT]?i?B)?$',
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    if (-not $match.Success) {
        return 0
    }

    $number = [double]::Parse(
            $match.Groups[1].Value,
            [System.Globalization.CultureInfo]::InvariantCulture
    )
    $unit = $match.Groups[2].Value.ToUpperInvariant()
    $factor = switch ($unit) {
        'KIB' { 1KB }
        'MIB' { 1MB }
        'GIB' { 1GB }
        'TIB' { 1TB }
        'KB' { 1000 }
        'MB' { 1000 * 1000 }
        'GB' { 1000 * 1000 * 1000 }
        'TB' { 1000 * 1000 * 1000 * 1000 }
        default { 1 }
    }
    return [long]($number * $factor)
}

function Get-ActiveExperiment {
    if (-not (Test-Path -LiteralPath $eventsFile)) {
        return $null
    }

    $latest = @{}
    foreach ($event in @(Import-Csv -LiteralPath $eventsFile)) {
        $latest[$event.experiment_id] = $event
    }

    return $latest.Values |
            Where-Object { $_.event_type -eq 'STARTED' } |
            Sort-Object event_at_utc -Descending |
            Select-Object -First 1
}

function Escape-Csv {
    param([string]$Value)
    return '"' + ($Value -replace '"', '""') + '"'
}

if (-not (Test-Path -LiteralPath $resourceFile)) {
    Set-Content -LiteralPath $resourceFile -Encoding utf8 -Value (
            'captured_at_utc,experiment_id,database,host_cpu_percent,' +
            'host_memory_used_mb,host_memory_available_mb,container_name,' +
            'container_cpu_percent,container_memory_bytes,' +
            'container_memory_limit_bytes,container_network_io,container_block_io'
    )
}

Write-RunLog "Resource monitor started for $DurationHours hours; sampling Windows and Docker resources every 60 seconds."

while ([DateTime]::UtcNow -lt $deadline) {
    try {
        $experiment = Get-ActiveExperiment
        $os = Get-CimInstance Win32_OperatingSystem
        $cpu = Get-CimInstance Win32_Processor |
                Measure-Object -Property LoadPercentage -Average
        $hostCpu = [math]::Round([double]$cpu.Average, 2)
        $availableMb = [math]::Round([double]$os.FreePhysicalMemory / 1024, 2)
        $usedMb = [math]::Round(
                ([double]$os.TotalVisibleMemorySize - [double]$os.FreePhysicalMemory) / 1024,
                2
        )
        $capturedAt = [DateTime]::UtcNow.ToString('o')
        $experimentId = if ($null -eq $experiment) { '' } else { $experiment.experiment_id }
        $database = if ($null -eq $experiment) { '' } else { $experiment.database }

        $rawStats = & docker stats --no-stream --format '{{json .}}' 2>$null
        foreach ($rawStat in @($rawStats)) {
            if ([string]::IsNullOrWhiteSpace($rawStat)) {
                continue
            }

            $stat = $rawStat | ConvertFrom-Json
            if ($stat.Name -notmatch '^(cassandra|scylla)-node[1-3]$') {
                continue
            }

            $memoryParts = $stat.MemUsage -split ' / '
            $memoryBytes = Convert-SizeToBytes $memoryParts[0]
            $memoryLimitBytes = if ($memoryParts.Count -gt 1) {
                Convert-SizeToBytes $memoryParts[1]
            } else {
                0
            }
            $containerCpu = [double]::Parse(
                    ($stat.CPUPerc -replace '%', ''),
                    [System.Globalization.CultureInfo]::InvariantCulture
            )

            $line = @(
                (Escape-Csv $capturedAt)
                (Escape-Csv $experimentId)
                (Escape-Csv $database)
                $hostCpu,
                $usedMb,
                $availableMb,
                (Escape-Csv $stat.Name)
                $containerCpu,
                $memoryBytes,
                $memoryLimitBytes,
                (Escape-Csv $stat.NetIO)
                (Escape-Csv $stat.BlockIO)
            ) -join ','
            Add-Content -LiteralPath $resourceFile -Value $line -Encoding utf8
        }
    } catch {
        Write-RunLog ("Resource monitor sample failed: " + $_.Exception.Message)
    }

    Start-Sleep -Seconds 60
}

Write-RunLog "Resource monitor reached its $DurationHours-hour limit and stopped."

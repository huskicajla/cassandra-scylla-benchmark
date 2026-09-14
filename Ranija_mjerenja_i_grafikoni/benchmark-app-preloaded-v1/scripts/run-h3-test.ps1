param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('cassandra', 'scylla')]
    [string]$Database,
    [Parameter(Mandatory = $true)]
    [ValidateSet(1, 3)]
    [int]$NodeCount
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaHome = 'C:\Users\HP\.jdks\temurin-21.0.12'
$nodeLabel = if ($NodeCount -eq 1) { '1-node' } else { '3-nodes' }
$profile = "h3-$Database-$nodeLabel"
$consoleLog = Join-Path $projectRoot "results\h3-$Database-$nodeLabel.log"

$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"
Push-Location $projectRoot
try {
    & .\mvnw.cmd "-Dspring-boot.run.profiles=$profile" spring-boot:run 2>&1 |
            Tee-Object -FilePath $consoleLog -Append
    exit $LASTEXITCODE
} finally {
    Pop-Location
}

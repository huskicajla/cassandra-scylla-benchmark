param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('cassandra', 'scylla')]
    [string]$Database,
    [ValidateSet('1m', '1node-1m')]
    [string]$Topology = '1m'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaHome = 'C:\Users\HP\.jdks\temurin-21.0.12'
$profile = "preloaded-$Database-$Topology"
$consoleLog = Join-Path $projectRoot "results\today_preloaded_${Database}_console_${Topology}.log"

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

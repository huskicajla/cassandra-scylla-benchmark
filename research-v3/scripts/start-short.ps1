param(
    [Parameter(Mandatory=$true)][string]$Plan,
    [string]$DatasetPath=(Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'data-generator/output/telemetry-50m')
)
$ErrorActionPreference='Stop'
$Plan=(Resolve-Path -LiteralPath $Plan).Path
& wsl -d docker-desktop -u root -- sysctl -w fs.aio-max-nr=1048576
if($LASTEXITCODE -ne 0){throw 'AIO podesavanje nije uspjelo.'}
& "$PSScriptRoot/run-short.ps1" -Plan $Plan -DatasetPath $DatasetPath

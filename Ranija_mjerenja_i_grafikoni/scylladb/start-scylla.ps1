 $ErrorActionPreference = "Stop"

$requiredAioLimit = 1048576
$composeFile = Join-Path $PSScriptRoot "docker-compose.yml"

Write-Host "Setting Linux AIO limit to $requiredAioLimit..."

wsl.exe -d docker-desktop -u root -- sh -c "sysctl -w fs.aio-max-nr=$requiredAioLimit"

Write-Host "Checking AIO limit..."

$aio = wsl.exe -d docker-desktop -u root -- sh -c "cat /proc/sys/fs/aio-max-nr"

Write-Host "AIO limit: $aio"

if ([int64]$aio.Trim() -lt $requiredAioLimit) {
    Write-Host "ERROR: AIO limit was not configured correctly." -ForegroundColor Red
    exit 1
}

Write-Host "Starting ScyllaDB cluster..."

docker compose -f $composeFile up -d

Write-Host "ScyllaDB startup initiated."

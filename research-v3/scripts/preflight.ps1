param([string]$DatasetPath, [int]$Nodes=3)
. "$PSScriptRoot/common.ps1"
$java=Find-Java21
$aio=(& wsl -d docker-desktop -u root -- cat /proc/sys/fs/aio-max-nr | Out-String).Trim()
if($LASTEXITCODE -ne 0 -or [long]$aio -lt 1048576) {throw 'Zajednicki Docker AIO limit mora biti najmanje 1048576. Pokrenite start-background.ps1.'}
Write-Host "Zajednicki AIO limit: $aio"
Write-Host "Java 21: $java"
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker CLI nije instaliran.' }
$info=Invoke-DockerChecked @('info','--format','{{json .}}') | ConvertFrom-Json
if ($info.OSType -ne 'linux') { throw 'Ukljucite Linux containers u Docker Desktopu.' }
if ([int]$info.NCPU -lt 2*$Nodes) { throw "Docker treba najmanje $($Nodes*2) dostupnih CPU niti za ovu topologiju." }
if ([long]$info.MemTotal -lt (6*$Nodes+1)*1GB) { throw "Docker/WSL treba barem $(6*$Nodes+1) GiB RAM-a. Podesite WSL budzet uz prostor za Windows i Java klijent." }
Assert-NoOtherContainers
if ($DatasetPath) {
    $manifest=Join-Path $DatasetPath 'manifest.json'
    if (-not (Test-Path -LiteralPath $manifest)) { throw 'Nedostaje manifest.json u dataset folderu.' }
    $data=Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
    if ($data.total_records -lt 10000000) { throw 'Za cijeli plan potrebno je najmanje 10M zapisa.' }
    foreach ($i in 0..9) {
        $chunk=Join-Path $DatasetPath ('chunk-{0:D3}.csv' -f $i)
        if (-not (Test-Path -LiteralPath $chunk)) { throw "Nedostaje $chunk" }
    }
    Write-Host 'Pronadjen manifest i prvih 10 chunkova. Ucitavanje ce provjeriti broj i format redova.'
}
try { Get-CimInstance Win32_ComputerSystem | Select-Object TotalPhysicalMemory | Format-List } catch { Write-Host 'Host RAM nije dostupan kroz CIM; Docker budzet je provjeren.' }
Write-Host "Docker: $($info.NCPU) CPU niti; $([math]::Round($info.MemTotal/1GB,1)) GiB RAM."
Write-Host 'Preflight je prosao. Prije mjerenja zatvorite druge zahtjevne programe i onemogucite sleep.'

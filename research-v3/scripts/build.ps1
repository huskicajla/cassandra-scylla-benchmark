. "$PSScriptRoot/common.ps1"
$java = Find-Java21
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java)
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
Push-Location (Join-Path $ResearchRoot 'benchmark-app-research-v3')
try {
    & .\mvnw.cmd --settings (Join-Path $ResearchRoot 'maven-settings.xml') --batch-mode test package
    if ($LASTEXITCODE -ne 0) { throw 'Izgradnja/testovi nisu prosli. Pogledajte Maven izlaz.' }
} finally { Pop-Location }
Write-Host 'JAR i testovi su spremni.'

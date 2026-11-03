[CmdletBinding(DefaultParameterSetName = 'Local')]
param(
    [Parameter(Mandatory = $true, ParameterSetName = 'Local')]
    [string] $PromtoolPath,
    [Parameter(Mandatory = $true, ParameterSetName = 'Docker')]
    [switch] $Docker,
    [Parameter(ParameterSetName = 'Docker')]
    [string] $DockerImage = 'prom/prometheus:v3.5.0@sha256:63805ebb8d2b3920190daf1cb14a60871b16fd38bed42b857a3182bc621f4996'
)
$ErrorActionPreference = 'Stop'
if ($Docker) {
    $executable = (Get-Command docker -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $prefix = @('run', '--rm', '--network', 'none', '--read-only',
        '--tmpfs', '/tmp:rw,noexec,nosuid,size=64m', '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges', '--label', 'io.codex.task=datahub-capacity-7',
        '--label', 'io.codex.disposable=true', '--mount', "type=bind,source=$PSScriptRoot,target=/work,readonly",
        '--workdir', '/work', '--entrypoint', '/bin/promtool', $DockerImage)
} else {
    $executable = (Get-Command $PromtoolPath -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $prefix = @()
}
if (-not $executable) { throw 'Specify the trusted promtool executable path or use -Docker.' }
Push-Location $PSScriptRoot
try {
    & $executable @prefix check rules 'dataforge-run.rules.yml'
    if ($LASTEXITCODE -ne 0) { throw 'Prometheus rule validation failed.' }
    & $executable @prefix check config 'prometheus-dataforge.yml.example'
    if ($LASTEXITCODE -ne 0) { throw 'Prometheus scrape configuration validation failed.' }
    $fixtures = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter 'dataforge-run*.test.yml' -File | Sort-Object Name | ForEach-Object Name)
    if ($fixtures.Count -eq 0) { throw 'No Prometheus regression fixtures found.' }
    & $executable @prefix test rules @fixtures
    if ($LASTEXITCODE -ne 0) { throw 'Prometheus alert regression failed.' }
} finally {
    Pop-Location
}

param([string]$ByteBuddyJar = "$PSScriptRoot\lib\byte-buddy.jar")
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

function Run-Javac([string[]]$Arguments) {
    & javac '-J-Duser.language=en' '-J-Duser.country=US' @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
}
function Run-Jar([string[]]$Arguments) {
    & jar @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'jar failed; close any running tracer/target JVM before rebuilding' }
}
function Get-Sources([string]$PackagePath) {
    $sourceDirectory = Join-Path $PSScriptRoot ("src\" + $PackagePath)
    if (!(Test-Path -LiteralPath $sourceDirectory -PathType Container)) {
        throw "Source folder not found: $sourceDirectory. Keep package folders directly under src."
    }
    $files = @(Get-ChildItem -LiteralPath $sourceDirectory -Recurse -File -Filter '*.java' | ForEach-Object {$_.FullName})
    if ($files.Count -eq 0) { throw "No Java source files found in $sourceDirectory" }
    return $files
}

if (!(Test-Path -LiteralPath $ByteBuddyJar)) {
    throw 'Place Byte Buddy 1.17.8 at lib/byte-buddy.jar, or pass -ByteBuddyJar path.'
}
# Eclipse uses ONE source root, src. Select packages only when assembling separate JARs.
$sources = @{
    runtime = @(Get-Sources 'trace\runtime')
    agent = @(Get-Sources 'trace\agent')
    controller = @(Get-Sources 'trace\control')
    demo = @(Get-Sources 'demo')
}
foreach ($part in @('runtime','agent','controller','demo')) {
    New-Item -ItemType Directory -Force -Path "build\$part" | Out-Null
}
New-Item -ItemType Directory -Force -Path 'dist\lib' | Out-Null
Copy-Item -LiteralPath $ByteBuddyJar -Destination 'dist\lib\byte-buddy.jar' -Force

Run-Javac (@('--release','8','-encoding','UTF-8','-g','-d','build\runtime') + $sources.runtime)
Run-Jar @('cf','dist\trace-runtime.jar','-C','build\runtime','.')
Run-Javac (@('--release','8','-encoding','UTF-8','-g','-cp',"build\runtime;$ByteBuddyJar",'-d','build\agent') + $sources.agent)
@"
Manifest-Version: 1.0
Premain-Class: trace.agent.Agent
Class-Path: lib/byte-buddy.jar

"@ | Set-Content -LiteralPath 'build\agent.mf' -Encoding ascii
Run-Jar @('cfm','dist\trace-agent.jar','build\agent.mf','-C','build\agent','.')
foreach ($part in @('controller','demo')) {
    Run-Javac (@('--release','8','-encoding','UTF-8','-g','-d',"build\$part") + $sources[$part])
    Run-Jar @('cf',"dist\trace-$part.jar",'-C',"build\$part",'.')
}
Write-Output 'BUILD_OK: dist/trace-agent.jar, trace-runtime.jar, trace-controller.jar, trace-demo.jar'

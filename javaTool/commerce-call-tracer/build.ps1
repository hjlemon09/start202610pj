param([string]$ByteBuddyJar = "$PSScriptRoot\lib\byte-buddy.jar")
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
function Run-Javac([string[]]$Arguments) {
    & javac '-J-Duser.language=en' '-J-Duser.country=US' @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
}
function Run-Jar([string[]]$Arguments) {
    & jar @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
}
if (!(Test-Path -LiteralPath $ByteBuddyJar)) { throw 'Place Byte Buddy 1.17.8 at lib/byte-buddy.jar, or pass -ByteBuddyJar path.' }
foreach ($part in @('runtime','agent','controller','demo','test')) {
    New-Item -ItemType Directory -Force -Path "build\$part" | Out-Null
}
New-Item -ItemType Directory -Force -Path 'dist\lib' | Out-Null
Copy-Item -LiteralPath $ByteBuddyJar -Destination 'dist\lib\byte-buddy.jar' -Force
$runtimeSources = @(Get-ChildItem 'src\runtime' -Recurse -Filter '*.java' | ForEach-Object {$_.FullName})
Run-Javac (@('--release','8','-encoding','UTF-8','-g','-d','build\runtime') + $runtimeSources)
Run-Jar @('cf','dist\trace-runtime.jar','-C','build\runtime','.')
$agentSources = @(Get-ChildItem 'src\agent' -Recurse -Filter '*.java' | ForEach-Object {$_.FullName})
Run-Javac (@('--release','8','-encoding','UTF-8','-g','-cp',"build\runtime;$ByteBuddyJar",'-d','build\agent') + $agentSources)
@"
Manifest-Version: 1.0
Premain-Class: trace.agent.Agent
Class-Path: lib/byte-buddy.jar

"@ | Set-Content -LiteralPath 'build\agent.mf' -Encoding ascii
Run-Jar @('cfm','dist\trace-agent.jar','build\agent.mf','-C','build\agent','.')
foreach ($part in @('controller','demo')) {
    $sources = @(Get-ChildItem "src\$part" -Recurse -Filter '*.java' | ForEach-Object {$_.FullName})
    Run-Javac (@('--release','8','-encoding','UTF-8','-g','-d',"build\$part") + $sources)
    Run-Jar @('cf',"dist\trace-$part.jar",'-C',"build\$part",'.')
}
$tests = @(Get-ChildItem 'src\test' -Recurse -Filter '*.java' | ForEach-Object {$_.FullName})
if ($tests.Count -gt 0) { Run-Javac (@('--add-modules','jdk.jdi','-encoding','UTF-8','-g','-cp','build\controller;build\runtime','-d','build\test') + $tests) }
Write-Output 'BUILD_OK: dist/trace-agent.jar, trace-runtime.jar, trace-controller.jar, trace-demo.jar'

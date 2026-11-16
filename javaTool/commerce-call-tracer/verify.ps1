$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# Tests are compiled only for explicit verification, never by build.ps1.
$testDirectory = Join-Path $PSScriptRoot 'src\trace\test'
if (!(Test-Path -LiteralPath $testDirectory -PathType Container)) {
    throw 'Verification requires src/trace/test. Tests are not required for build.ps1.'
}
$testSources = @(Get-ChildItem -LiteralPath $testDirectory -Recurse -File -Filter '*.java' | ForEach-Object {$_.FullName})
if ($testSources.Count -eq 0) { throw 'No verification sources found in src/trace/test.' }
foreach ($artifact in @('trace-agent.jar','trace-runtime.jar','trace-controller.jar','trace-demo.jar','lib\byte-buddy.jar')) {
    if (!(Test-Path -LiteralPath (Join-Path 'dist' $artifact))) {
        throw "Run .\build.ps1 before verification; missing dist/$artifact"
    }
}
New-Item -ItemType Directory -Force -Path 'build\test' | Out-Null
& javac '-J-Duser.language=en' '-J-Duser.country=US' --add-modules jdk.jdi -encoding UTF-8 -g -cp 'dist\trace-controller.jar;dist\trace-runtime.jar' -d 'build\test' @testSources
if ($LASTEXITCODE -ne 0) { throw 'Verification source compilation failed' }

& java --add-modules jdk.jdi -cp 'build\test;dist\trace-controller.jar' trace.test.BreakpointCheck $PSScriptRoot
if ($LASTEXITCODE -ne 0) { throw 'Breakpoint validation failed' }
& java -cp 'build\test;dist\trace-controller.jar;dist\trace-runtime.jar' trace.test.RuntimeCheck $PSScriptRoot
if ($LASTEXITCODE -ne 0) { throw 'Session boundary validation failed' }
& java -cp 'build\test;dist\trace-controller.jar' trace.test.LoaderCheck $PSScriptRoot
if ($LASTEXITCODE -ne 0) { throw 'Isolated classloader validation failed' }
foreach ($mode in @('size','rows','unlimited','oversized','failure')) {
    & java -cp 'build\test;dist\trace-controller.jar;dist\trace-runtime.jar' trace.test.RotationCheck $PSScriptRoot $mode
    if ($LASTEXITCODE -ne 0) { throw "CSV rotation validation failed: $mode" }
}

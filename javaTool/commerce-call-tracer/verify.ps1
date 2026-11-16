$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
& java --add-modules jdk.jdi -cp 'build\test;build\controller' trace.test.BreakpointCheck $PSScriptRoot
if ($LASTEXITCODE -ne 0) { throw 'Breakpoint validation failed' }
& java -cp 'build\test;build\controller;build\runtime' trace.test.RuntimeCheck $PSScriptRoot
if ($LASTEXITCODE -ne 0) { throw 'Session boundary validation failed' }
& java -cp 'build\test;build\controller' trace.test.LoaderCheck $PSScriptRoot
if ($LASTEXITCODE -ne 0) { throw 'Isolated classloader validation failed' }

@echo off
setlocal
pushd "%~dp0"
call build.cmd
if errorlevel 1 goto failed
java -Xmx2g -cp "bin;lib\cfr-0.152.jar" execute.Execute %*
set "RUN_RESULT=%ERRORLEVEL%"
popd
exit /b %RUN_RESULT%
:failed
popd
exit /b 1

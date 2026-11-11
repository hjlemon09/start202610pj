@echo off
setlocal
pushd "%~dp0"
if not exist bin mkdir bin
javac --release 21 -encoding UTF-8 -cp "lib\cfr-0.152.jar" -d bin src\com\javatool\search\*.java
set "BUILD_RESULT=%ERRORLEVEL%"
popd
exit /b %BUILD_RESULT%

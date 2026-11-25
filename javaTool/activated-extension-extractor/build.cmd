@echo off
setlocal
pushd "%~dp0"
if not exist bin mkdir bin
javac --release 21 -encoding UTF-8 -d bin src\com\sapcommerce\extractor\*.java
set "BUILD_RESULT=%ERRORLEVEL%"
popd
exit /b %BUILD_RESULT%

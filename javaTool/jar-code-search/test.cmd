@echo off
setlocal
pushd "%~dp0"
call build.cmd
if errorlevel 1 goto failed
javac --release 21 -encoding UTF-8 -cp "bin;lib\cfr-0.152.jar" -d bin test\com\javatool\search\SearchTest.java
if errorlevel 1 goto failed
java -cp "bin;lib\cfr-0.152.jar" com.javatool.search.SearchTest
set "TEST_RESULT=%ERRORLEVEL%"
popd
exit /b %TEST_RESULT%
:failed
popd
exit /b 1

@echo off
setlocal
pushd "%~dp0"
if not exist test-bin mkdir test-bin
javac --release 21 -encoding UTF-8 -d test-bin src\com\sapcommerce\occresponse\*.java src\execute\*.java test\com\sapcommerce\occresponse\*.java
if errorlevel 1 goto failed
java -cp test-bin com.sapcommerce.occresponse.OccResponseAnalyzerTest
set "TEST_RESULT=%ERRORLEVEL%"
popd
exit /b %TEST_RESULT%
:failed
popd
exit /b 1

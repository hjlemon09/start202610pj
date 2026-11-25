@echo off
setlocal
pushd "%~dp0"
if not exist test-bin mkdir test-bin
javac --release 21 -encoding UTF-8 -d test-bin src\com\sapcommerce\extractor\*.java test\com\sapcommerce\extractor\*.java
if errorlevel 1 goto failed
java -cp test-bin com.sapcommerce.extractor.ActivatedExtensionExtractorTest
set "TEST_RESULT=%ERRORLEVEL%"
popd
exit /b %TEST_RESULT%
:failed
popd
exit /b 1

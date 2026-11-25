@echo off
setlocal
pushd "%~dp0"
call "%~dp0build.cmd"
if errorlevel 1 goto failed
java %EXTRACTOR_OPTS% -cp bin com.sapcommerce.extractor.ActivatedExtensionExtractor %*
set "RUN_RESULT=%ERRORLEVEL%"
popd
exit /b %RUN_RESULT%
:failed
popd
exit /b 1

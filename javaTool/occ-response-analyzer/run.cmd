@echo off
setlocal
call "%~dp0build.cmd"
if errorlevel 1 exit /b 1
java %OCC_RESPONSE_ANALYZER_OPTS% -cp "%~dp0bin" execute.Execute %*
exit /b %ERRORLEVEL%

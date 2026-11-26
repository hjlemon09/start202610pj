@echo off
cd /d "%~dp0"
set "TRACE_CONFIG=%~1"
if not defined TRACE_CONFIG set "TRACE_CONFIG=%~dp0config\demo.properties"
java -cp dist\trace-controller.jar execute.Execute "%TRACE_CONFIG%"

@echo off
setlocal
call "%~dp0build.cmd"
if errorlevel 1 exit /b 1
if not defined JAVAPARSER_JAR set "JAVAPARSER_JAR=%~dp0javaparser-core-3.27.1.jar"
java -Xmx6g -cp "%~dp0bin;%JAVAPARSER_JAR%" execute.Execute %*
exit /b %ERRORLEVEL%

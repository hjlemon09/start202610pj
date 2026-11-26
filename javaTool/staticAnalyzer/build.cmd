@echo off
setlocal
pushd "%~dp0"
if not defined JAVAPARSER_JAR set "JAVAPARSER_JAR=%~dp0javaparser-core-3.27.1.jar"
if not exist "%JAVAPARSER_JAR%" (
    echo [ERROR] JavaParser jar not found: %JAVAPARSER_JAR%. Set JAVAPARSER_JAR to its path.
    popd
    exit /b 1
)
if not exist bin mkdir bin
javac --release 21 -encoding UTF-8 -cp "%JAVAPARSER_JAR%" -d bin com\CallTreeExtractor.java com\CallGraphCsv.java com\SpringBeanReport.java execute\Execute.java
set "BUILD_RESULT=%ERRORLEVEL%"
popd
exit /b %BUILD_RESULT%

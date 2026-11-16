@echo off
cd /d "%~dp0"
java "-javaagent:%~dp0dist\trace-agent.jar=%~dp0config\demo.properties" -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005 -cp dist\trace-demo.jar demo.shop.Demo 2

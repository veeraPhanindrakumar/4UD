@echo off
setlocal
cd /d "%~dp0"
call start-mysql.bat
if errorlevel 1 exit /b 1
if not exist out mkdir out
"C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\javac.exe" -cp "lib\mysql-connector-j.jar" -d out src\App.java
if errorlevel 1 exit /b 1
"C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe" -cp "out;lib\mysql-connector-j.jar" App

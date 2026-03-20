@echo off
setlocal
set "MYSQLD=C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqld.exe"
set "DATADIR=C:\ProgramData\MySQLData"

netstat -ano | findstr ":3306" >nul
if %errorlevel%==0 exit /b 0

if not exist "%MYSQLD%" (
  echo MySQL server executable not found at "%MYSQLD%".
  exit /b 1
)

if not exist "%DATADIR%" (
  echo MySQL data directory not found at "%DATADIR%".
  exit /b 1
)

start "" /B "%MYSQLD%" --datadir="%DATADIR%" --port=3306 --bind-address=127.0.0.1

set /a attempts=0
:wait_loop
timeout /t 1 /nobreak >nul
netstat -ano | findstr ":3306" >nul
if %errorlevel%==0 exit /b 0
set /a attempts+=1
if %attempts% geq 15 (
  echo MySQL did not start on port 3306.
  exit /b 1
)
goto wait_loop

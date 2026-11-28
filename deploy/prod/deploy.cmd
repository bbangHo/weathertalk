@echo off
rem WeatherTalk production backend deploy (mini PC, C:\Users\user\weathertalk-prod).
rem GitHub Actions uploads app-new.jar and this file, then runs it.
rem Steps: keep current jar as app-prev.jar -> swap in new jar -> restart backend -> health check -> roll back on failure.
rem ASCII only: cmd reads this file in the OEM code page, so non-ASCII text can break parsing.
setlocal
cd /d %~dp0
set CURL=C:\Windows\System32\curl.exe
set HEALTH_URL=https://bbangho-server.duckdns.org/api/v1/home

if not exist app-new.jar (
  echo [deploy] app-new.jar not found
  exit /b 2
)

copy /y app.jar app-prev.jar >nul || (echo [deploy] backup failed & exit /b 3)

call dc.bat --profile prod stop backend
move /y app-new.jar app.jar >nul || goto rollback
call dc.bat --profile prod up -d backend
type compose.log

call :wait_healthy
if not errorlevel 1 (
  echo [deploy] OK
  exit /b 0
)

:rollback
echo [deploy] health check failed, rolling back to app-prev.jar
call dc.bat --profile prod stop backend
copy /y app-prev.jar app.jar >nul
call dc.bat --profile prod up -d backend
call :wait_healthy
if errorlevel 1 (
  echo [deploy] server still down after rollback
  exit /b 5
)
echo [deploy] rolled back to previous version
exit /b 4

rem Caddy answers 502/503 while the backend is down. Any other code (200, 4xx) means the app is up. Up to 3 minutes.
:wait_healthy
for /l %%i in (1,1,36) do (
  for /f %%c in ('%CURL% -s -o NUL -w "%%{http_code}" %HEALTH_URL%') do (
    if not "%%c"=="000" if not "%%c"=="502" if not "%%c"=="503" if not "%%c"=="504" (
      echo [deploy] health %%c
      exit /b 0
    )
  )
  rem timeout.exe fails without a console (SSH), so wait with ping instead.
  C:\Windows\System32\ping.exe -n 6 127.0.0.1 >nul
)
exit /b 1

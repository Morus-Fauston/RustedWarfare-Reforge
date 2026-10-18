@echo off
setlocal
if not defined JAVA_HOME (
  for /d %%J in ("%ProgramFiles%\Microsoft\jdk-25*") do if exist "%%~fJ\bin\java.exe" set "JAVA_HOME=%%~fJ"
)
cd /d "%~dp0\..\.."
if not exist "Engine\pc\build\install\pc\bin\pc.bat" (
  echo Build the demo first: cd Engine ^&^& gradlew.bat :pc:installDist
  pause
  exit /b 1
)
call "Engine\pc\build\install\pc\bin\pc.bat" --mode demo %*
if errorlevel 1 pause

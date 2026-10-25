@echo off
rem Portable launcher: the jars in lib\, no console window. Uses a bundled
rem runtime\ when present, else JAVA_HOME, else javaw from the PATH (Java 21
rem or newer). Arguments (--hidden, --toggle <tunnel>) are passed through.
setlocal
set "DIR=%~dp0"
set "JAVAW="
if exist "%DIR%runtime\bin\javaw.exe" set "JAVAW=%DIR%runtime\bin\javaw.exe"
if not defined JAVAW if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVAW=%JAVA_HOME%\bin\javaw.exe"
if not defined JAVAW where javaw.exe >nul 2>nul && set "JAVAW=javaw.exe"
if not defined JAVAW (
    echo AmneziaWG needs Java 21 or newer. Install it (for example Eclipse Temurin 21^),
    echo or unpack a JRE into the runtime\ folder next to this file.
    pause
    exit /b 1
)
start "" "%JAVAW%" -cp "%DIR%lib\*" org.amnezia.awg.desktop.MainKt %*
endlocal

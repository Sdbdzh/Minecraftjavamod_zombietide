@echo off
REM Force console to UTF-8 (CP65001) so Chinese log output is readable.
REM JVM (Java 18+) writes stdout as UTF-8 by default; console must match.
chcp 65001 >nul
cd /d "%~dp0"
call gradlew runServer

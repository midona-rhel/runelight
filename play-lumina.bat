@echo off
setlocal
cd /d "%~dp0"
call gradlew.bat run --console=plain
if errorlevel 1 pause

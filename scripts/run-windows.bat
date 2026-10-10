@echo off
cd /d "%~dp0"
java -jar AudioALL.jar %*
if errorlevel 1 pause

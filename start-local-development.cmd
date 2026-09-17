@echo off
setlocal
title Building Energy Carbon Platform - Local Development
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Start-LocalDevelopment.ps1" %*
if errorlevel 1 (
  echo.
  echo Local development failed. Review the message above.
  pause
)

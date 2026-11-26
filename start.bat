@echo off
cd /d "%~dp0"

set "PY_EXE=%LOCALAPPDATA%\Python\bin\python3.exe"
if not exist "%PY_EXE%" set "PY_EXE=python"

if not exist ".venv\Scripts\python.exe" (
    echo Creating virtual environment...
    "%PY_EXE%" -m venv .venv
)

echo Installing / checking dependencies...
.venv\Scripts\python.exe -m pip install -r requirements.txt -q

echo Starting ICT/SMC Video Translator...
.venv\Scripts\python.exe -m uvicorn app:app --host 0.0.0.0 --port 8000
pause

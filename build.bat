@echo off
rem Builds Squid with the Java 25 that Kelp downloads for Minecraft 26.3.
rem   build.bat        builds Squid and the example mods
rem   build.bat test   also runs the tests
cd /d "%~dp0"
set JAVA=%APPDATA%\Kelp\runtimes\java-runtime-epsilon\bin\java.exe
if not exist "%JAVA%" (
    echo Kelp has not downloaded Java 25 yet. Open Kelp and play Minecraft 26.3 once first.
    pause
    exit /b 1
)
"%JAVA%" tools\Build.java %* || (pause & exit /b 1)

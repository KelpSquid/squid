@echo off
rem Starts this Minecraft server with Squid. Change 4G to give it more or less memory.
cd /d "%~dp0"
java -Xmx4G -jar squid.jar nogui
pause

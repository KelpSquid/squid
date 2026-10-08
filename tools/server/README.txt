Squid Server
============

A Minecraft server with Squid, so players who have Squid get voice chat (and
more later) on it. Players without Squid can still join; it's a normal
Minecraft server to them.

What you need
-------------
Java 25 or newer (Minecraft 26.3 needs it). Get it from https://adoptium.net
if "java -version" says something older.

Starting it
-----------
1. Put this folder where you want the server to live.
2. Windows: double-click start.bat. Mac or Linux: run "sh start.sh"
3. The first time, Squid downloads Minecraft's server from Mojang (it checks
   Mojang's fingerprint), and Minecraft stops right away and makes eula.txt.
   Read Mojang's EULA (the link is in eula.txt). If you agree, change
   eula=false to eula=true in eula.txt yourself, then start it again.

Everything else is a normal Minecraft server: server.properties, worlds,
whitelist and ops all work the same.

Voice chat
----------
Squid Voice is on by default. Its settings are in config/squid-voice.properties
(made the first time the server starts):

  enabled=true        false turns voice chat off
  mode=proximity      proximity (players near each other), world (everyone),
                      or groups (only people in the same group)
  distance=48         how far voices carry in proximity mode, 8 to 128 blocks
  groups=true         whether players can make groups

Restart the server after changing them. Voice only goes to players who have
Squid, and the server never saves any of it.

Folders
-------
squid.jar   Squid itself (runs the server)
lib         two small libraries Squid needs (ASM, for changing Minecraft's code)
builtin     Squid's own server parts, like the voice relay
mods        server mods made for Squid go here

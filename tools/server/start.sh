#!/bin/sh
# Starts this Minecraft server with Squid. Change 4G to give it more or less memory.
cd "$(dirname "$0")"
exec java -Xmx4G -jar squid.jar nogui

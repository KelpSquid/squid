THE SQUID KIT
=============

Everything you need to make Squid mods in a code editor, without Kelp.
(With Kelp you don't need this: Kelp's New Mod makes a project that's already set up.)

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.


What's inside
-------------
squid-api.jar           the Squid library: every command a mod can use
squid-api-sources.jar   its code, which is where your editor reads what each command does
docs/index.html         the same explanations as web pages
ExampleMod/             a working project to start from


Make a mod
----------
1. Copy ExampleMod next to these files and rename it (and src/ExampleMod.java, and the class inside) to your mod's name.
2. Open the folder in VS Code (with its Java extension) or IntelliJ.
3. Tell your editor where Minecraft is, so it knows Minecraft's code too. It's the game's jar, like
   %APPDATA%\.minecraft\versions\26.3\26.3.jar  (or, with Kelp, %APPDATA%\Kelp\versions\26.3\26.3.jar)
     VS Code:  add it to "include" in ExampleMod\.vscode\settings.json
     IntelliJ: File > Project Structure > Libraries > + > Java, and pick it (add squid-api.jar the same way)
4. To play it, put the whole folder in a Squid instance's mods folder. Squid builds it by itself as the game starts.
   Or, in Kelp's Mods screen, Pack it into one .squid file to share.


A project
---------
MyMod/
  squid.json     {"name": "My Mod"}   (the id, version and main class can be left out)
  src/           your code, as many files and folders as you like
  resources/     pictures and sounds

Squid adds "import squid.api.*;" to every file by itself, so you never need it, but editors like seeing it.
More: https://github.com/SamuelArther/squid

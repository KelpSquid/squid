<p align="center"><img src="branding/squid.png" width="160" alt="Squid logo"></p>

 > **NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**
  > Minecraft is a trademark of Microsoft Corporation.

# Squid

A Minecraft mod loader, made from scratch. It works with the [Kelp](https://github.com/SamuelArther/kelp) launcher and Minecraft 26.3.

## Your first mod in 5 minutes

1. In Kelp, open **Instances**, pick one, click **Mods**, then **New Mod**.
2. Type a name, like "Rainbow Sheep", and click **Create**. Kelp makes `RainbowSheep.java` and opens it.
3. Change what's inside `start()`, save, and play. That's it: no build step, no jar. Squid compiles it as the game starts.

```java
public class RainbowSheep extends EasyMod {
    void start() {
        say("Rainbow Sheep is working!");

        onKey("H", () -> {
            say("You pressed H! You're at " + x() + ", " + y() + ", " + z());
            playSound("entity.experience_orb.pickup");
        });
    }
}
```

| Command | What it does |
| --- | --- |
| `say("Hi!")` | A chat message only you see |
| `showText("Hi!")` | Text just above your hotbar |
| `playSound("entity.experience_orb.pickup")` | Plays a sound only you hear |
| `giveItem("diamond", 3)` | Gives you items (cheats need to be on) |
| `command("time set day")` | Runs a command, like typing `/time set day` |
| `splash("Hi!")` | Changes the yellow text on the title screen |
| `onJoin(() -> { ... })` | Runs when you join a world |
| `onKey("H", () -> { ... })` | Runs when you press a key (players can change it in Controls) |
| `every(10, () -> { ... })` | Runs every 10 seconds |
| `onTick(() -> { ... })` | Runs 20 times a second |
| `x()`, `y()`, `z()`, `health()`, `playerName()`, `random(1, 6)` | Things to know |

If there's a mistake, the game still opens. The title screen and Kelp say which line it's on and what's wrong, like *"there's a mistake on line 3: a ; is missing at the end of the line"*. A mod that goes wrong while you play says so in the chat and switches that part off.

When you're ready for more, `squid()` gives an easy mod everything below.

## How it works

Kelp starts Squid instead of Minecraft. Squid:

1. finds the mods in the instance's `mods` folder,
2. lets each mod set up its hooks,
3. starts Minecraft through its own class loader, so the hooks get written into Minecraft's code as each class loads.

## Building

You need Kelp to have downloaded Minecraft 26.3 once, because Squid builds with the Java 25 that Kelp downloads for it.

```
build.bat         builds build/squid.jar, the built-in parts, the example mods and the Squid library, and copies Squid into Kelp's folder
build.bat test    also runs the tests (they load real Minecraft classes without opening the game)
```

## The Squid library (for IDEs)

Easy mods need nothing but a `.java` file. For bigger mods, the Squid library lets an IDE like IntelliJ or VS Code
know every Squid command: it autocompletes them, shows what each one does, and underlines mistakes as you type.

`build.bat` makes it in `build/maven`, laid out like a Maven repository:

- `squid-api-<version>.jar`: just `squid.api`, everything a mod can use
- `-sources.jar` and `-javadoc.jar`, so the IDE can show Squid's code and explanations
- a `.pom` saying the library needs [ASM](https://asm.ow2.io/) (for `patch`)

Its name is `org.kelplauncher:squid-api:<version>`. With Gradle, until it's online:

```kotlin
repositories {
    maven { url = uri("file:///C:/path/to/squid/build/maven") }
    mavenCentral()
}
dependencies {
    compileOnly("org.kelplauncher:squid-api:0.1")
}
```

Use `compileOnly`: Squid is already in the game, so a mod's jar never includes it. Your mod also needs Minecraft
itself to build against, which Kelp downloads to `%APPDATA%\Kelpersions.3.3.jar`. `build.bat test` checks
that every example mod builds with just the library.

## The Store

The Squid Store is built into Squid (`builtin/store`), so everyone has it: a **Store** button on the title screen, next to Realms. It has three tabs: **Dev-picked**, **Mods** and **Resource Packs**. Install puts a mod in `mods` (it starts next time the game opens) or a resource pack in `resourcepacks`.

The store reads one list, `store.json`, from the [squid-store](https://github.com/SamuelArther/squid-store) repo. Every item has a fingerprint (sha256), and a download that doesn't match it is thrown away. Nothing gets in the list without being approved; submissions will come through submit.kelplauncher.org.

`build.bat` makes `build/store`: a `store.json` and a `files` folder with every first-party mod. Upload both to the squid-store repo and the store shows them. To test with another list, start the game with `-Dsquid.store=<link to a store.json>`.

## Making a mod

A Squid mod is a `.jar` with a `squid.json` inside:

```json
{
    "id": "hello-squid",
    "name": "Hello Squid",
    "version": "1.0.0",
    "description": "Changes the title screen splash to prove Squid works.",
    "authors": ["Samuel"],
    "main": "hello.HelloSquid"
}
```

If your mod needs another mod, list its id in `"depends": ["other-mod"]`. Squid starts that mod first.

`"minecraft"` says which Minecraft versions your mod works on: `"26.3"` for just that one, `"26.3.x"` for 26.3 and its updates, or a list like `["26.3.x", "26.4"]`. Leave it out and Squid tries your mod on any version.

Squid never lets one mod stop the game from opening. A mod for a different version, a second copy of a mod, or a mod missing something it needs is skipped, and Kelp says why.

`main` is a class that implements `SquidMod`:

```java
public class HelloSquid implements SquidMod {
    @Override
    public void init(Squid squid) {
        squid.atStart("net.minecraft.client.resources.SplashManager", "getSplash", call ->
                call.cancel(new SplashRenderer(Component.literal("Squid is working!"))));
    }
}
```

What a mod can do in `init`:

- `atStart(class, method, hook)` runs code at the start of a method. `call.cancel(value)` skips the rest of it.
- `atEnd(class, method, hook)` runs code when a method returns. `call.setReturnValue(value)` changes what it returns.
- `onHud(hud -> ...)` draws on the screen every frame: `hud.box(...)`, `hud.text(...)`, `hud.outline(...)`.
- `onTick(() -> ...)` runs 20 times a second.
- `addKeyBinding(name, key)` adds a key to Minecraft's Controls screen, where players can change it. `isDown()` says if it's held, `pressed()` if it was just pressed.
- `patch(class, node -> ...)` changes a class's bytecode directly with [ASM](https://asm.ow2.io/).

Set hooks up in `init` before touching any Minecraft class, or that class will already be loaded without them. See [`examples`](examples) for whole mods: Hello Squid, Zoom, Compass and Minimap.

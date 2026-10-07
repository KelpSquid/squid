<p align="center"><img src="branding/squid.png" width="160" alt="Squid logo"></p>

 > **NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**
  > Minecraft is a trademark of Microsoft Corporation.

# Squid

A Minecraft mod loader, made from scratch. It works with the [Kelp](https://github.com/SamuelArther/kelp) launcher and Minecraft 26.3.

## How it works

Kelp starts Squid instead of Minecraft. Squid:

1. finds the mods in the instance's `mods` folder,
2. lets each mod set up its hooks,
3. starts Minecraft through its own class loader, so the hooks get written into Minecraft's code as each class loads.

## Building

You need Kelp to have downloaded Minecraft 26.3 once, because Squid builds with the Java 25 that Kelp downloads for it.

```
build.bat         builds build/squid.jar and the example mods, and copies Squid into Kelp's folder
build.bat test    also runs the tests (they load real Minecraft classes without opening the game)
```

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

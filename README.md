<p align="center"><img src="branding/squid.png" width="160" alt="Squid logo"></p>

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

If your mod needs another mod, list its id in `"depends": ["other-mod"]`. Squid starts that mod first, and tells you if it's missing.

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
- `addKeyBinding(name, key)` adds a key to Minecraft's Controls screen, where players can change it. `isDown()` says if it's held.
- `patch(class, node -> ...)` changes a class's bytecode directly with [ASM](https://asm.ow2.io/).

Set hooks up in `init` before touching any Minecraft class, or that class will already be loaded without them. See [`examples/hello-squid`](examples/hello-squid) for a whole mod, and [`examples/zoom`](examples/zoom) for one that adds a key.

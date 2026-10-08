# The .sqda file

A `.sqda` ("squidaudio") is Squid's own sound file. It holds the sound itself, squeezed with Squid Music
(Squid's own codec), and everything Minecraft and mods need to know about it: loop points, a volume track,
beats and cues, light cues, sound settings, triggers and info. A resource pack can drop one in next to (or
instead of) an `.ogg`, with no `sounds.json` editing.

Make one with Kelp's **Sound Maker**, or with SqdaTool (`java -cp squid.jar squid.audio.SqdaTool --help`).

## Layout

```
"SQDA"            4 bytes
version           1 byte (1)
chunk, chunk, ...
```

Every chunk is:

```
name              4 ASCII letters, like "LOOP"
length            int32, how many bytes follow
body              length bytes
```

Numbers are big-endian. `UTF` below means a text: a uint16 byte count, then the text in Java's modified UTF-8
(the same as `DataOutputStream.writeUTF`), so one text is at most 65535 bytes.

**Chunks a reader doesn't know are skipped.** That is how new kinds of chunk can be added later without breaking
older Squids. The version byte only goes up for a change older readers couldn't survive.

**The sound comes last.** Every `VARI` chunk is after every other chunk, so a reader that only wants the settings
(Squid does this when a pack loads) can stop at the first `VARI`.

### CRCS: checks on every chunk

`CRCS` has an empty body. Every chunk **after** it ends with a CRC32 (the same one zip files use) of the rest of
its body, and the chunk's length counts those 4 bytes. A file whose check doesn't match is damaged, and Squid
refuses it with a clear message instead of playing noise. Squid writes `CRCS` first, right after the version.

Older readers skip `CRCS` as unknown, and the 4 extra bytes at the end of each body don't bother them, since
every chunk below says how many things it holds.

### CODC: which codec

| Field | Type | Meaning |
| --- | --- | --- |
| codec | uint8 | 1 = Squid Music |
| version | uint8 | Squid Music's bitstream version, now 1 |

A reader that gets a codec it doesn't know, or a newer version than it can decode, says "update Squid"
instead of guessing. A file without `CODC` is Squid Music version 1.

## Chunks

Times and lengths are in **samples** (moments) of the variant they belong to, not seconds. At 48000 Hz,
48000 samples is one second. `variant` is the variant's number, in the order the `VARI` chunks come (0 is the
first).

### INFO: title, artist and anything else

| Field | Type |
| --- | --- |
| count | uint16 |
| count times: key, value | UTF, UTF |

Common keys: `title`, `artist`, `album`, `year`, `license`.

### SNDS: sound settings (like sounds.json's)

| Field | Type | Meaning |
| --- | --- | --- |
| subtitle | UTF | shown with subtitles on; empty for none |
| volume | float32 | 1 is normal. Readers keep it between 0 and 10 |
| pitch | float32 | 1 is normal. Readers keep it between 0.05 and 10 |
| distance | int32 | how far it's heard, in blocks (16 is Minecraft's normal) |
| stream | uint8 | 0 Squid decides, 1 always stream, 2 never |

Without `SNDS`, the settings are `"", 1, 1, 16, 0`. Minecraft only places mono sounds in the world, so
`distance` does nothing for stereo sounds.

### LOOP: loop points

| Field | Type |
| --- | --- |
| variant | uint16 |
| start | int64 |
| end | int64 |

A looping sound plays from the start of the variant to `end`, then jumps back to `start`, forever. If there is
real sound after `end`, the first 256 samples after `end` fade out while the loop's start fades in, so the seam
doesn't click. A loop shorter than 512 samples loops the whole variant instead.

### CUES: beats, bars, sections and named cues

| Field | Type |
| --- | --- |
| count | int32 |
| count times: variant, at, kind, name | uint16, int64, uint8, UTF |

Kinds: 0 cue, 1 beat, 2 bar (also a beat), 3 section. Mods hear them through `onSoundCue` as the sound plays.

### LITE: light cues

| Field | Type | Meaning |
| --- | --- | --- |
| count | int32 | |
| variant | uint16 | |
| at | int64 | when it starts |
| length | int64 | how long it lasts |
| color | int32 | 0xRRGGBB |
| brightness | uint8 | 0 to 255 |
| effect | uint8 | 0 on, 1 flash, 2 fade, 3 strobe |
| group | UTF | which lights, by name; empty for all |

(The fields after `count` repeat `count` times.)

### TRIG: triggers

| Field | Type | Meaning |
| --- | --- | --- |
| count | uint16 | |
| entity | UTF | like `minecraft:creeper`, or `*` for any |
| event | uint8 | 0 comes into view, 1 leaves view, 2 comes near |
| distance | float32 | blocks; 0 or less means 32 |
| sound | UTF | a Minecraft sound, or `variant:name` for one of this file's variants |
| volume | float32 | |
| pitch | float32 | |
| cooldown | int32 | game ticks (20 a second) before it can fire again |

Triggers only work while this sound is playing.

### LEVL: the volume track

| Field | Type | Meaning |
| --- | --- | --- |
| variant | uint16 | |
| step | uint16 | samples per value (1024) |
| channels | uint8 | |
| count | int32 | how many bytes follow |
| values | count bytes | loudness, 0 (silent) to 255 (loudest), channel after channel for each step |

Mods read it through `SquidAudio.level()`, so things can bounce to the music without analyzing the sound
while it plays.

### VARI: a variant (the sound)

| Field | Type | Meaning |
| --- | --- | --- |
| name | UTF | like `main` or `sting` |
| weight | uint16 | how often it's picked at random; 0 means it only plays from a trigger |
| rate | int32 | samples a second, 8000 to 192000 (see below) |
| channels | uint8 | 1 or 2 |
| samples | int64 | how long it is, in samples per channel |
| frames | int32 | must be `ceil(samples / 1024) + 1` |
| frames times: length, frame | uint16, bytes | one Squid Music frame |

Each Squid Music frame is 1024 samples per channel. The **first frame is a warm-up**: its sound is the silence
in front of the variant, so sample 0 starts in frame 1. Frames come in **groups of 16**, and each group starts
fresh, so playback can only start decoding at a frame that's a multiple of 16. To jump to sample `n`, start decoding at
frame `floor(n / 1024 / 16) * 16`, keep going up to frame `floor(n / 1024) + 1`, and take the sound from there.

A damaged frame plays as silence until the next group.

Squid's writers fit sound with any other sample rate to this range by a whole number of times, so nothing
drifts: slower sound (like 4000 Hz) is stretched 2, 3... times, and faster sound (like 384 kHz) keeps the
average of every 2, 3... samples. Loop points, cues and lights are in samples of the rate that's written.

## Limits readers enforce

So a damaged or made-up file can't freeze the game or take its memory:

- a variant's `frames` must match its `samples`, its `rate` must be 8000 to 192000, and
  `samples * channels` at most 2^27
- a volume track's `count` can't be more than its chunk
- settings that aren't real numbers go back to normal; volume is kept between 0 and 10, pitch between 0.05 and 10,
  and distance between 1 and 1024 blocks. A trigger's volume and pitch are kept the same way, and a distance that
  isn't a real number counts as 0 (the normal 32 blocks)

Writers keep every text to at most 21845 characters, so it always fits its 65535-byte limit, and refuse to write
anything readers would refuse (a rate outside 8000 to 192000, more than 2 channels, `samples * channels` over
2^27, a weight over 65535).

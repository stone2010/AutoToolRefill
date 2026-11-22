# Build notes — manual MCP toolchain (1.9.4)

The ForgeGradle setup in `build.gradle` is the historical, canonical one. This file documents
the alternative that was actually used to build the released jar, because the MCP snapshot
artifact it depends on (`mcp_snapshot-20160524`) is no longer served by the Forge maven.

It is a plain javac pipeline: no Gradle, no ForgeGradle, no IDE.

## Why it is needed

ForgeGradle normally does this for you, in three steps:

```
notch (obfuscated)  --deobf-->  SRG  --mcp-->  MCP names
```

1. `deobfuscation_data-1.9.4.lzma`, shipped inside the Forge universal jar, maps **notch → SRG**.
2. `mcp_snapshot-20160524-1.9.4.zip` (`methods.csv` + `fields.csv`) maps **SRG → MCP**.
3. SpecialSource performs both remaps.

`joined.srg` (which ForgeGradle downloads) is just (1) and (2) concatenated, so it can be rebuilt
by hand.

## Steps

**1. Build the mappings.**

- Extract `deobfuscation_data-1.9.4.lzma` from `forge-1.9.4-12.17.0.2051-universal.jar` and
  decompress it (LZMA "alone" format) — this is notch → SRG.
- Take `methods.csv` / `fields.csv` from the MCP snapshot and emit `PK:` / `FD:` / `MD:` lines
  in SRG syntax for SRG → MCP.
- Concatenate both into `mcp.srg`.

**2. Build a compile classpath.**

Remap vanilla `1.9.4.jar` and the Forge classes with SpecialSource, twice:

```bash
java -jar SpecialSource.jar --in-jar 1.9.4.jar --out-jar srg.jar --srg-in notch2srg.srg
java -jar SpecialSource.jar --in-jar srg.jar   --out-jar mc-mcp.jar --srg-in srg2mcp.srg
```

`mc-mcp.jar` + `forge-mcp.jar` + `log4j-api.jar` is then a normal compile classpath, and the
source can use MCP names directly.

**3. Compile.**

```bash
javac --release 8 -encoding UTF-8 -proc:none \
      -classpath "mc-mcp.jar;forge-mcp.jar;log4j-api.jar" \
      -d classes src/main/java/com/stg/autorefill/AutoRefill.java
```

**4. Reobfuscate MCP → SRG.**

The inverse mapping (`mcp2srg.srg`) is built by swapping the two sides of `mcp.srg`. One catch:
**SpecialSource only remaps members declared on the class that is referenced.** `player.inventory`
compiled against `EntityPlayer` will not be rewritten, because `inventory` is declared on
`EntityPlayer`… but a call compiled against a subclass such as `EntityPlayerMP`, or a reference to
an inherited member, needs the mapping present on *that* class too. So the inverse mapping is
expanded down the inheritance tree before use.

```bash
java -jar SpecialSource.jar --in-jar autorefill-1.2.0.jar \
     --out-jar autorefill-1.2.0-reobf.jar --srg-in mcp2srg_full.srg
```

**5. Verify.**

Before shipping, every `Fieldref` / `Methodref` in the remapped jar is checked against the SRG
runtime jars. Anything that does not resolve will be a `NoSuchMethodError` in game, so the check is
worth the five seconds:

```
checked 45 refs, 0 missing
```

## Gotchas

- **`javac` may be blocked from writing temp files** in restricted environments. `JavaCompiler`
  with an in-memory `ForwardingJavaFileManager` works around it; the class files can then be
  written by any other process.
- **`log4j-api` is not on the vanilla classpath.** Add `log4j-api-2.0-beta9.jar` explicitly.
- **Resource paths must be `assets/<modid>/...`** at the jar root, not `src/main/resources/...`.
  Easy to get wrong when writing the jar by hand.
- `Item.REGISTRY.getNameForObject` returns `null` for unregistered items — always null-check before
  `toString()`, or you get an NPE inside a tick handler.

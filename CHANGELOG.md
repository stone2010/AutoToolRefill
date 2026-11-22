# Changelog

## 1.2.0

- Localized chat messages (English / 简体中文) via `assets/autorefill/lang`.
- `mcmod.info` now advertises the GitHub URL.

## 1.1.0

- **Rewritten trigger logic.** `PlayerDestroyItemEvent` alone does not fire when a tool wears out in
  1.9.4, so the mod now uses three paths: the client event, a server-tick scan for `stackSize <= 0`
  shells, and a server-tick diff of the held slot.
- Refills are queued for a couple of ticks so the server's `setHeldItem(null)` cannot wipe the
  replacement.
- Error logging is rate-limited; no single bad tick can flood the log.

## 1.0.0

- First version: refill from inventory, craft from materials, config file, chat message.

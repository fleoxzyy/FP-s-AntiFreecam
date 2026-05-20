# FPAntiFreeCam

**FPAntiFreeCam** is a high-performance, region-aware Anti-FreeCam plugin for Minecraft servers. It prevents players from using FreeCam/FreeLook mods to scout underground bases, mob farms, or hidden structures by replacing underground blocks with air (or a configured replacement) in outbound packets.

Designed for modern Minecraft versions and high-population servers, it features native support for **Paper**, **Folia**, and **Bedrock (via Geyser/Floodgate)**.

## 🚀 Features

*   **Donut-Style Protection:** When players are above a certain Y-level (surface), everything below them is hidden.
*   **Packet-Level Security:** Uses [PacketEvents](https://github.com/retrooper/packetevents) for low-level block and chunk manipulation.
*   **Folia Support:** Multi-threaded region-aware scheduling to prevent thread-safety issues.
*   **Bedrock Compatibility:** Automatically detects Geyser/Floodgate players and optimizes refresh radii for mobile/console clients.
*   **Optimized Performance:**
    *   Asynchronous chunk refreshing.
    *   Configurable tick-batching for Paper/Spigot.
    *   Low overhead packet interception.
*   **Entity Hiding:** Optionally hides mobs and other entities in the "void zone" to prevent entity-based scouting.
*   **Auto-Updating Config:** Safely merges new configuration options without resetting user settings.

## 🛠️ Installation

1.  Download the latest `.jar` from the [Releases](https://github.com/fleoxxzy/FPAntiFreeCam/releases) page.
2.  Place the jar in your server's `plugins/` folder.
3.  Restart your server.
4.  Configure your protected worlds in `plugins/FPAntiFreeCam/config.yml`.

## ⚙️ Configuration

```yaml
config-version: 1

settings:
  language: "en"
  debug-mode: false
  refresh-cooldown-seconds: 3

protection:
  surface-y: 16.0 # Y level at-or-above which protection is active
  void-y: 15     # Blocks at-or-below this Y are hidden
```

## 📜 Permissions

*   `fpantifreecam.admin`: Full access to all commands.
*   `fpantifreecam.reload`: Permission to use `/fpac reload`.
*   `fpantifreecam.bypass`: Players with this permission will see the world normally.

## 🏗️ Building

To build the project yourself, ensure you have Java 21+ installed and use the Gradle wrapper:

```bash
./gradlew build
```

The shadowed jar will be located in `build/libs/`.

## 📄 License

This project is licensed under the **MIT License**. See the [LICENSE](LICENSE) file for details. Forks and modifications are encouraged!

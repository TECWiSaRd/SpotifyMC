# SpotifyMC

A NeoForge 1.21.1 mod that plays YouTube and Spotify music in-game, synchronized for everyone on a server.

- `/music play <link or search>` queues a song, an album, a playlist or an artist's top songs from YouTube, YouTube Music or Spotify, a SoundCloud link, or any search terms.
- `/music radio <song or artist>` plays an endless radio of similar songs.
- Everyone on the server hears the same song at the same position. Players who join mid-song start at the right spot.
- Works in single-player and on dedicated servers. Players without the mod can still join; they just don't hear the music.

## How it works

```
 /music play ...           ┌──────────── server ────────────┐
 ───────────────────────▶  │ queue + playback clock         │  no extra software needed
                           │ Spotify/YouTube metadata (HTTP)│
                           └───────┬───────────────┬────────┘
              "track 7, 42.3s in"  │               │  "track 7 is 3:33 long"
                                   ▼               │
                   ┌───────────── each client ─────┴────────┐
                   │ Spotify linked + Spotify track?        │
                   │   yes → plays in your Spotify app      │
                   │   no  → yt-dlp → ffmpeg → speakers     │
                   └────────────────────────────────────────┘
```

The server never streams audio. It tells each client what is playing and how far in, and each client streams the audio itself. Because the server sends the position rather than a clock time, clocks that differ between machines don't matter.

### A note on Spotify

Spotify doesn't let third-party software receive its audio (it's DRM-protected), so no mod can legitimately play Spotify audio through Minecraft. SpotifyMC handles Spotify links in two ways:

1. **Linked Spotify Premium account** (optional, per player): the track plays in that player's own Spotify app (desktop, phone or web player) through Spotify Connect, kept in sync with the server.
2. **Everyone else**: the mod finds the same song on YouTube ("Artist - Title") and plays it in-game.

## Requirements

| Where | What |
|---|---|
| Server | NeoForge 21.1.x for Minecraft 1.21.1, and this mod. Nothing else. |
| Each client | This mod, **ffmpeg**, and **yt-dlp** (downloaded automatically by default). |

Install ffmpeg:
- macOS: `brew install ffmpeg`
- Windows: `winget install ffmpeg` (restart the launcher afterwards)
- Linux: `sudo apt install ffmpeg` (or your distribution's equivalent)

Alternatively, put the `ffmpeg` or `ffmpeg.exe` binary in `.minecraft/spotifymc/bin/`.

yt-dlp: by default the mod downloads the official release from GitHub into `.minecraft/spotifymc/bin/` the first time it's needed. It checks the SHA-256 checksum the release publishes, and refreshes the copy weekly, because YouTube changes often break older versions. To use your own install instead, turn off `autoDownloadYtDlp`.

## Commands

### `/music` (server, shared by everyone)

| Command | |
|---|---|
| `/music play <url or search>` | Add to the queue (see the table below for what links work) |
| `/music radio <song, link or artist>` | Start an endless radio of similar songs. It keeps adding songs until you stop it |
| `/music radio off` | Stop the radio. The current song finishes |
| `/music skip` | Skip the current track |
| `/music pause` / `/music resume` | Pause or resume for everyone |
| `/music stop` | Stop and clear the queue |
| `/music shuffle` | Shuffle the queue |
| `/music queue` | Show the queue |
| `/music np` | Show the current track and progress |

### What you can queue

| Link | What gets queued |
|---|---|
| Spotify track | That song |
| Spotify album or playlist | Up to 100 songs (`maxPlaylistTracks`) |
| Spotify artist | Their top 10 songs |
| YouTube or YouTube Music video | That video |
| YouTube or YouTube Music playlist or album (`?list=...`) | Its first 100 videos |
| YouTube Mix (`list=RD...`) | The 25 songs in the Mix, once (use `/music radio` for endless) |
| Plain text, e.g. `daft punk get lucky` | The top YouTube result |
| SoundCloud, Bandcamp, Vimeo | That track (if the players' `allowedHosts` allows it) |

### Radio

`/music radio` accepts a song name, a YouTube link, or a Spotify track, artist, album or playlist link. It finds one song and plays YouTube's Mix of similar music. When the queue runs low, it adds more from the Mix of the latest radio song and skips anything already played. Songs added with `/music play` go before the radio songs. Radio also skips videos over 15 minutes, such as full albums or hour-long mixes. `/music stop` turns radio off as well.

Spotify's own personalized radio and Daily Mix playlists only exist inside each user's account, so the server can't read them. Use `/music radio <spotify artist or song link>` instead.

By default anyone can use the control commands. Set `controlPermissionLevel = 2` in `world/serverconfig/spotifymc-server.toml` to limit them to operators.

### `/musicclient` (only affects you)

| Command | |
|---|---|
| `/musicclient volume <0-200>` | Your music volume (default 100). Over 100 boosts quiet music. Also scaled by the Master and Jukebox/Note Blocks sliders |
| `/musicclient on` / `off` | Turn server music on or off just for you |
| `/musicclient resync` | Restart the current track at the server's position |
| `/musicclient status` | What's playing, and whether yt-dlp, ffmpeg and Spotify are set up |
| `/musicclient spotify link` / `unlink` | Connect your Spotify account |
| `/musicclient update` | Download and install the newest version from GitHub |

All client settings are also available under **Mods → SpotifyMC → Config**.

## Updates

When you join a world, the mod checks [GitHub Releases](https://github.com/TECWiSaRd/SpotifyMC/releases) for a newer version. If there is one, chat shows **[Install update]** and **[What's new]** buttons. Clicking **[Install update]** does the following:
- Downloads the new jar into your `mods` folder.
- Checks it against the SHA-256 checksum GitHub publishes.
- Removes the old jar. On Windows the old jar is locked while the game runs, so it's removed as soon as Minecraft closes.

Restart Minecraft to use the new version. Nothing is downloaded unless you click. To turn off the check, set *Check for Updates on Join* to off in the config.

**Servers update themselves.** A dedicated server, such as one on exaroton, checks GitHub when it starts and every 12 hours. It installs a newer version the same way: checksum-verified, replacing the old jar. The new version takes effect at the next restart, and online operators get a chat message saying so. To update by hand instead, set `autoUpdate = false` in `world/serverconfig/spotifymc-server.toml`.

Players and the server don't have to be on the same version. Features follow whichever side runs them: queue and radio features come from the server, while volume, updates and exaroton come from the player. Music keeps working across versions unless an update changes the network protocol. In that case, mismatched players can still join but hear no music until they update.

### Publishing a new version (maintainers)

1. Change `mod_version` in `gradle.properties`, for example to `1.0.2`, and commit.
2. Tag the commit and push the tag: `git tag v1.0.2 && git push origin main v1.0.2`
3. GitHub Actions builds the jar and publishes the release. The first build takes about 15 minutes; later ones are faster.

## exaroton: start your server when Minecraft launches

If your server is on [exaroton](https://exaroton.com), the mod can start it the moment Minecraft launches. By the time you reach the server list, it's usually online. This feature isn't related to the music.

1. Create an API token at <https://exaroton.com/account/>.
2. In-game, run `/exaroton token <your token>`. The mod lists your servers to confirm the token works.
3. From now on, every time Minecraft launches, the server starts in the background. A pop-up appears when it's starting and when it's online.

| Command | |
|---|---|
| `/exaroton start` / `stop` | Start or stop the server now |
| `/exaroton status` | Show whether it's online, and how many players are on |
| `/exaroton servers` | List the servers on your account |
| `/exaroton token <token>` / `token clear` | Save or remove your API token |

- **Several servers:** if your account has more than one, choose one under *Mods → SpotifyMC → Config → exaroton → Server*. You can enter its name, address or ID.
- **Turning it off:** to keep the commands but stop the automatic start, turn off *Start Server on Launch*.
- **Credits:** a running server uses exaroton credits. exaroton stops empty servers automatically after a while, so launching Minecraft and not joining costs only a few minutes.
- **Keep the token private:** it can control your whole exaroton account. It's stored in `.minecraft/spotifymc/exaroton-token.txt`, readable only by you, and kept outside the `config` folder so modpack exports don't include it. Players without a token are unaffected.

## Linking Spotify (optional, Premium only)

1. Go to <https://developer.spotify.com/dashboard> and create an app. Any name works. Select **Web API**.
2. Add this Redirect URI: `http://127.0.0.1:43821/callback`
3. Copy the app's **Client ID** into *Mods → SpotifyMC → Config → Spotify → Spotify Client ID*, or into `config/spotifymc-client.toml`.
4. Open Spotify on any device, then run `/musicclient spotify link` in-game and approve in the browser.

Apps in Spotify's development mode only work for accounts added under *User Management* in the dashboard. The simplest setup is for each player to create their own app, which takes about two minutes. Tokens are stored locally in `config/spotifymc-spotify-token.json` and are never sent to the Minecraft server.

## Security

The server decides what your client streams, so the client limits what a server can make it do:
- Queries are passed to yt-dlp after `--`, so a server can't inject yt-dlp options.
- Clients only stream YouTube searches and sites listed in `allowedHosts`. The defaults are youtube.com, youtu.be, soundcloud.com, bandcamp.com and vimeo.com. Add `*` to allow anything yt-dlp supports.
- Spotify URIs are checked against a strict pattern before being sent to Spotify.

## Troubleshooting

- **"Sign in to confirm you're not a bot"**: YouTube is rate-limiting you. Set *Extra yt-dlp Arguments* to `--cookies-from-browser firefox` (or `chrome`, `safari`, ...).
- **"ffmpeg not found"**: launchers started from the Dock or Start menu often don't see your shell's `PATH`. Set *ffmpeg Path* explicitly, or copy ffmpeg into `.minecraft/spotifymc/bin/`.
- **Spotify: "Open Spotify on any device first"**: Spotify Connect needs a running Spotify app to control.
- **Out of sync**: `/musicclient resync`.
- Detailed errors are in `logs/latest.log` (search for `SpotifyMC`).

## Limitations

- Only the first 100 videos of a YouTube playlist are added.
- Live internet radio station URLs (Icecast/SHOUTcast) work only if players add the station's site, or `*`, to `allowedHosts`. YouTube livestreams work by default and play until skipped.
- Audio plays through the system's default output device, not the device selected in Minecraft's sound options.
- Music isn't positional; it plays at the same volume wherever you are.

## Building

```
./gradlew build          # → build/libs/spotifymc-<version>.jar
./gradlew runClient      # dev client
./gradlew runServer      # dev server (accept the EULA in run/eula.txt first)
```

Requires JDK 21. Gradle downloads it automatically if it's missing.

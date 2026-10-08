# Friendscape Events: RuneLite plugin

RuneLite plugin that feeds Friendscape Events with XP, KC, item, collection log and pet progress. Sending is off by default and opt-in per the Plugin Hub rules.

Product docs, the build spec and the backlog live in the umbrella repo; the API and website have their own repo.

## Status

Skeleton (backlog ticket 007): the "Send data to Friendscape" config item (off by default), the side panel header with the sending toggle and disclosure, a server health check, and the Leagues and special world filter. Join codes, Event cards and detection come in later tickets.

## Privacy and the opt-in

- Nothing leaves the client until "Send data to Friendscape" is on. The panel's "Turn on sending" button flips that same config item; there is no second switch.
- Nothing is sent on Leagues, Deadman, Fresh Start, tournament, beta, speedrunning, PvP Arena or Last Man Standing worlds.
- Turning sending off cancels every in-flight request.
- Network calls use RuneLite's injected `OkHttpClient` and never run on the client thread.

Plugin Hub manifest `warning=` line (also the config item's confirm text):

```
warning=This plugin submits your username, account hash, drops, kill counts, XP and screenshots to a 3rd-party server (friendscape) not controlled or verified by the RuneLite Developers.
```

## Development

Requires JDK 11 or later (the Plugin Hub builds with 11). Put a local JDK path in the gitignored `gradle.properties` if Gradle should not use the default:

```
org.gradle.java.home=/path/to/jdk
```

- `./gradlew build` compiles and runs the tests (CI runs the same on every PR).
- `./gradlew run` launches RuneLite in developer mode with the plugin sideloaded.
- The API base defaults to `https://www.friendscape.cc/api/`, the production host. Until public opening (backlog ticket 047) www serves only the coming soon page, so the health check there fails and the header shows "Server unreachable". Point a sideloaded build at the dev host with `-Dfriendscape.apiBase=<url>` (add it to the `run` task's `jvmArgs`).

## License

BSD-2-Clause, as the Plugin Hub requires.

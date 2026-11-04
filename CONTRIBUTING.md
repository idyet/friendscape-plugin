# Contributing

## Development

Requires JDK 11 or later (the Plugin Hub builds with 11). Put a local JDK path in the gitignored `gradle.properties` if Gradle should not use the default:

```
org.gradle.java.home=/path/to/jdk
```

- `./gradlew build` compiles and runs the tests (CI runs the same on every PR).
- `./gradlew run` launches RuneLite in developer mode with the plugin sideloaded.
- `./gradlew runDev` does the same against the dev API host, `https://dev.friendscape.cc/api/`.
- In developer mode, type `::fschat` in game to print a normal and a problem chat line.
- The API base defaults to `https://dev.friendscape.cc/api/` until the final release, when it moves to `https://www.friendscape.cc/api/`. Point a build anywhere else with `-Dfriendscape.apiBase=<url>`.

The KC regexes are lifted from RuneLite (BSD-2, attribution in `KcMessages.java`). Product docs, the build spec and the backlog live in the Friendscape umbrella repo.

## Run against the stub

`./gradlew runStub` starts a stub API on `localhost:8787` and RuneLite in one JVM, with the plugin pointed at the stub. It shows card states the real server cannot drive yet, such as Hardcore skulls and server notices. The stub lives in test sources (`StubApi.java`), so it never ships in the Plugin Hub jar. `./gradlew runStub -PstubPort=<port>` moves it.

1. Run `./gradlew runStub`, log in and turn on sending. Discovery echoes your RSN, so any account works.
2. The showcase (`src/test/resources/stub/discovery.json`) has seven Live Events: a solo Skill of the week with a `died` skull, a Team Boss of the week with a `transferred` skull, one with no skull, and two server notices (red with the notifier, then plain), all without a Leaderboard as from an older server; then a solo Skill of the week whose Leaderboard has the player mid table under a skulled rival, and a Team Boss of the week whose Leaderboard has the player's Team outside the top 3, the player outside its top 3, and a removed member.
3. Edit `discovery.json` while it runs: the stub re-reads it on every request, so the cards change on the next discovery poll (at most a minute, or reopen the panel). `{{now}}`, `{{now-2h}}`, `{{now+3d}}` (units `s`, `m`, `h`, `d`) and `{{rsn}}` resolve at serve time; fixed ISO times work too. Notices show once per Event per session, so an edited notice needs a relog.
4. Switch scenario live, no relaunch: `curl localhost:8787/_stub/scenario/skull-appears` (the no-skull card gains a `died` skull) or `skull-clears` (the first card's current Life reaches its best). `curl localhost:8787/_stub/scenario/showcase` goes back. Add a scenario as `src/test/resources/stub/scenarios/<name>.json`.
5. Grep the console for `[stub]`: one line per request with its path, `X-Friendscape-Protocol` and `X-Friendscape-Version`. Readings list each metric with the World bit (`hardcore=true` on a Hardcore world), and discovery logs `rsn`, `accountHash`, `accountType` (`null` until varps sync) and `hardcore`.

The stub answers `health`, `readings` (counted on every Live Event), `submissions`, `report` and `vote` (when the Event has a Vote); anything else is a 404.

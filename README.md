# friendscape

Tracks your progress in Friendscape clan Events: bingo, Skill of the week, Boss of the week.

If you are on the roster of a Friendscape Event, this plugin sends the progress that Event counts to the Friendscape server: XP, kill counts, drops, new collection log slots and pets. The side panel and an optional overlay show each Event's status, your Team's standing and, for bingo, a mini board.

## Getting started

1. Install friendscape from the Plugin Hub.
2. Open the friendscape panel in the RuneLite sidebar.
3. Click **Turn on sending** and accept the prompt. Nothing is sent until you do.
4. Log in. The panel shows "Playing as <your name>" and a card for each Event you are on.

No cards? Your character is not on an Event roster yet. Ask your Event organizer to add your exact RSN.

## What it sends

Only while sending is on, you are logged in, and an Event you are on wants it:

- **XP:** every skill at login and at an Event's start and end, then changed skills at most once a minute, plus once at logout.
- **Kill counts:** the count from each kill-count, clue completion and rifts closed chat message.
- **Drops, collection log slots and pets:** only items an open bingo Tile is watching, each with a screenshot of the game as proof.

Nothing is sent from Leagues, Deadman, Fresh Start, tournament, beta, speedrunning, PvP Arena or Last Man Standing worlds.

### Game and RuneLite settings drops rely on

- **Raids, chests, clues, minigames and pickpockets** come from RuneLite's core **Loot Tracker** plugin. Keep it enabled.
- **Collection log slots** need the in-game **Collection log: new addition notification** chat setting on.
- **Pets** need the in-game **Untradeable loot notifications** setting on, so the chat line names the pet.

If a screenshot fails (client minimised, say), the drop still goes through, and chat tells you to upload a screenshot on the event page so it counts.

## The panel

Each Event gets a card with its status and your Team's standing. From a card you can:

- **Open event page** or, for a bingo board too large for the sidebar, **Open board**.
- **Show board / Hide board**: the bingo mini board. Click a Tile to open it on the website.
- **Open event page to upload**: shown when a drop is waiting for a screenshot.
- **Report to Friendscape**: shown when progress is on hold while Friendscape checks your RSN. The answer comes in chat.
- **⋯ > Leave**: stop sending for that Event from this install. Your progress and roster entry stay, and **Rejoin** on the same card turns it back on.

## Configuration

| Setting | Default | What it does |
|---|---|---|
| Send data to Friendscape | Off | Sends your progress in Friendscape Events you are rostered in to the Friendscape server. Same switch as the panel's **Turn on sending**. |
| Chat line when counted | On | Prints a chat line when a drop, collection log slot or pet counts for an Event. |
| Notify on Team completions | On | Shows a notification when your Team completes a Tile or reaches a Tier. |
| Notify on problems | On | Uses the RuneLite notifier when something needs your attention, such as progress on hold. |
| Show overlay | On | Shows each live Event with its status and your rank in game. |

## Privacy

This plugin submits your username, account hash, drops, kill counts, XP and screenshots to a 3rd-party server (friendscape) not controlled or verified by the RuneLite Developers.

- Turning sending off stops all traffic and cancels every request in flight.
- Drops waiting to send are kept under `.runelite/friendscape/submissions` so a crash or lost connection does not lose them. They are deleted once sent or no longer needed.
- Network calls never run on the client thread.

## Development

Requires JDK 11 or later (the Plugin Hub builds with 11). Put a local JDK path in the gitignored `gradle.properties` if Gradle should not use the default:

```
org.gradle.java.home=/path/to/jdk
```

- `./gradlew build` compiles and runs the tests (CI runs the same on every PR).
- `./gradlew run` launches RuneLite in developer mode with the plugin sideloaded.
- `./gradlew runDev` does the same against the dev API host, `https://dev.friendscape.cc/api/`.
- The API base defaults to `https://www.friendscape.cc/api/`. Point a build anywhere else with `-Dfriendscape.apiBase=<url>`.
- Testers without the Plugin Hub build: `./gradlew shadowJar` (`build/libs/friendscape-1.0-SNAPSHOT-all.jar`), then follow [SIDELOAD.md](SIDELOAD.md).

The KC regexes are lifted from RuneLite (BSD-2, attribution in `KcMessages.java`). Product docs, the build spec and the backlog live in the Friendscape umbrella repo.

## License

[BSD 2-Clause](LICENSE).

# Testing friendscape before it is on the Plugin Hub

friendscape is not on the RuneLite Plugin Hub yet. Until it is, testers run a copy of RuneLite with the plugin built in. That copy is a separate program from your normal RuneLite: your usual RuneLite, settings and plugins stay as they are.

You need:

- The file `friendscape-1.0-SNAPSHOT-all.jar`, sent to you by the organizer. Only run a copy you got from them directly.
- Java 11 or newer.
- About 10 minutes the first time.

## 1. Install Java

Download and install **Temurin 17 (LTS)** from <https://adoptium.net/>. Take the defaults.

Check it worked: open a terminal (Windows: Start, type `cmd`, Enter; Mac: Spotlight, type `Terminal`, Enter) and run:

```
java -version
```

It should print a version of 11 or higher. If it says the command is not found, restart your computer and try again.

## 2. Let the test client log in with your Jagex account

Skip this step if you log in with an old-style email and password (no Jagex account).

The test client cannot open the Jagex Launcher, so your normal RuneLite has to hand it your login once:

1. Open the RuneLite configure window:
   - **Windows:** Start menu, run **RuneLite (configure)**.
   - **Mac:** in Terminal run `/Applications/RuneLite.app/Contents/MacOS/RuneLite --configure`
2. In **Client arguments** add `--insecure-write-credentials`, then click **Save**.
3. Start RuneLite from the Jagex Launcher as normal, wait until you reach the login screen, then close it.

RuneLite has now saved a login file at `.runelite/credentials.properties` in your user folder.

**That file logs into your account without your password. Never send it to anyone, including the organizer.** When testing is over, see [After testing](#after-testing).

## 3. Start the test client

Put the jar somewhere easy, such as your Downloads folder. In a terminal:

**Windows**

```
cd %USERPROFILE%\Downloads
java -Dfriendscape.apiBase=https://dev.friendscape.cc/api/ -jar friendscape-1.0-SNAPSHOT-all.jar --debug
```

**Mac**

```
cd ~/Downloads
java -Dfriendscape.apiBase=https://dev.friendscape.cc/api/ -jar friendscape-1.0-SNAPSHOT-all.jar --debug
```

RuneLite opens. Keep the terminal window open while you play; closing it closes the client.

Log in, then open the friendscape panel in the RuneLite sidebar. Click **Turn on sending** and accept the prompt. Nothing is sent until you do.

You should see "Playing as <your name>" and a card for each Event you are on.

## Reporting a problem

Tell the organizer what happened and roughly when, and send the log file:

- **Windows:** `%USERPROFILE%\.runelite\logs\client.log`
- **Mac:** `~/.runelite/logs/client.log`

The `--debug` part of the start command makes this log record every message to and from friendscape. Send `client.log` only, never `credentials.properties`.

## Troubleshooting

| Problem | Fix |
|---|---|
| `java` is not recognised | Restart your computer after installing Java. Still failing: reinstall Java and tick "Add to PATH". |
| `Unable to access jarfile` | The terminal is in the wrong folder, or the file name differs. Check the name in your Downloads folder. |
| The login screen asks for an email and password, but you use a Jagex account | Redo step 2, making sure you reached the login screen via the Jagex Launcher. |
| RuneLite says it is out of date | Game updates (usually Wednesdays) can break the test client. Ask the organizer for a new jar. |
| The panel says "Server unreachable" | Check you typed the `-Dfriendscape.apiBase=...` part exactly. Otherwise tell the organizer. |
| No Event cards | Your character is not on an Event roster yet. Send the organizer your exact RSN. |

## After testing

1. Open RuneLite (configure) again, remove `--insecure-write-credentials`, and click **Save**.
2. Delete `credentials.properties` from the `.runelite` folder in your user folder.
3. On runescape.com, open account settings and click **End sessions** so the saved login stops working.
4. Delete the jar. Your normal RuneLite was never changed.

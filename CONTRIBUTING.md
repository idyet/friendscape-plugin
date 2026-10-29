# Contributing

## Development

Requires JDK 11 or later (the Plugin Hub builds with 11). Put a local JDK path in the gitignored `gradle.properties` if Gradle should not use the default:

```
org.gradle.java.home=/path/to/jdk
```

- `./gradlew build` compiles and runs the tests (CI runs the same on every PR).
- `./gradlew run` launches RuneLite in developer mode with the plugin sideloaded.
- `./gradlew runDev` does the same against the dev API host, `https://dev.friendscape.cc/api/`.
- The API base defaults to `https://dev.friendscape.cc/api/` until the final release, when it moves to `https://www.friendscape.cc/api/`. Point a build anywhere else with `-Dfriendscape.apiBase=<url>`.

The KC regexes are lifted from RuneLite (BSD-2, attribution in `KcMessages.java`). Product docs, the build spec and the backlog live in the Friendscape umbrella repo.

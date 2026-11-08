# IntelliJ Daetekt Plugin

[![Join the DVD Discord for support](https://img.shields.io/discord/1497680181274345585)](https://discord.davidarthurcole.me/)
![Pre Merge Checks](https://github.com/DavidArthurCole/daetekt-intellij-plugin/workflows/Pre%20Merge%20Checks/badge.svg)
[![JetBrains Plugin](https://img.shields.io/jetbrains/plugin/v/35914-daetekt.svg)](https://plugins.jetbrains.com/plugin/35914-daetekt)
[![License](https://img.shields.io/github/license/DavidArthurCole/daetekt-intellij-plugin.svg)](LICENSE)

Integrates _detekt_, a static code analysis tool for the Kotlin programming language, into IntelliJ.
Versus the upstream **[detekt-intellij-plugin](https://github.com/detekt/detekt-intellij-plugin)** (from which this repository is forked), this version integrates with:
- detekt 2.0.0-alpha6
- Java 21
- Kotlin 2.3+

![detekt in action](./img/detekt.png "detekt in action")

The plugin can be downloaded from the [Jetbrains plugin repository](https://plugins.jetbrains.com/plugin/35914-daetekt).

## Enabling the plugin

- `Settings -> Plugins -> Marketplace -> Search for detekt -> Install`
- Configure the plugin via `Settings -> Tools -> detekt`

### Help testing beta releases

- `Settings -> Plugins -> Manage Plugin Repositories`
- Add `https://plugins.jetbrains.com/plugins/beta/list` (check for trailing spaces!)
- Help test `.beta.x` releases and report bugs

## Configuration Options

![configuration](./img/configuration.png "configuration")

That's it. Detekt issues will be annotated on-the-fly while coding.

## Autocorrection

You may optionally click `Refactor` -> `AutoCorrect by detekt rules` to auto correct detekt violations if possible.

## Building / developing the plugin

For building the project, the [Gradle IntelliJ plugin](https://github.com/JetBrains/gradle-intellij-plugin)
is used.

```bash
# linux & macOS
./gradlew buildPlugin
# windows
gradlew buildPlugin
```

To test your development, use task `runIde` which will automatically run an Intellij instance to test your new version of detekt plugin.
```bash
# linux & macOS
./gradlew runIde
# windows
gradlew runIde
```

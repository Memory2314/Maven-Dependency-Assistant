# Maven Dependency Assistant

![Build](https://github.com/Memory2314/Maven-Dependency-Assistant/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/33294-maven-dependency-assistant.svg)](https://plugins.jetbrains.com/plugin/33294-maven-dependency-assistant)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/33294-maven-dependency-assistant.svg)](https://plugins.jetbrains.com/plugin/33294-maven-dependency-assistant)

English | [简体中文](./README_CN.md)

An IntelliJ Platform plugin for searching Maven Central and copying ready-to-use dependency snippets.

## Features

- Search by artifact name, Maven coordinates, or class name.
- Browse artifact versions and release dates.
- Generate snippets for Maven, Gradle, SBT, Mill, Ivy, Grape, Leiningen, and Buildr.
- Choose the dependency scope and Gradle Groovy or Kotlin DSL format.
- Group and paginate search results.
- Cache and optionally preload results.
- English and Simplified Chinese interfaces.

## Usage

Open **Maven Dependency Search** from the tool window or **Tools | Search Maven Dependency** (`Ctrl+Shift+D`). Search for an artifact, select a version and build tool, then click the preview to copy the dependency snippet.

Plugin options are available under **Settings | Tools | Maven Dependency Assistant**.

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Maven-Dependency-Assistant"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/33294-maven-dependency-assistant) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/33294-maven-dependency-assistant/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/Memory2314/Maven-Dependency-Assistant/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

## Build

JDK 21 is required.

```shell
./gradlew buildPlugin
```

The plugin archive is generated in `build/distributions`.

See [CHANGELOG.md](./CHANGELOG.md) for release notes.

## License

LSPosed is licensed under the **GNU General Public License v3 (GPL-3)** (http://www.gnu.org/copyleft/gpl.html).
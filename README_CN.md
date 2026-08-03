# Maven Dependency Assistant

![Build](https://github.com/Memory2314/Maven-Dependency-Assistant/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/33294-maven-dependency-assistant.svg)](https://plugins.jetbrains.com/plugin/33294-maven-dependency-assistant)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/33294-maven-dependency-assistant.svg)](https://plugins.jetbrains.com/plugin/33294-maven-dependency-assistant)

[English](./README.md) | 简体中文

一款用于搜索 Maven Central 并复制依赖代码的 IntelliJ Platform 插件。

## 功能

- 按构件名称、Maven 坐标或类名搜索。
- 查看构件版本及发布日期。
- 生成 Maven、Gradle、SBT、Mill、Ivy、Grape、Leiningen 和 Buildr 依赖代码。
- 选择依赖 scope，以及 Gradle Groovy 或 Kotlin DSL 格式。
- 对搜索结果进行分组和分页。
- 缓存并按需自动预加载搜索结果。
- 提供英文和简体中文界面。

## 使用

通过工具窗口或 **Tools | Search Maven Dependency**（`Ctrl+Shift+D`）打开 **Maven Dependency Search**。搜索构件，选择版本和构建工具，然后点击预览区域复制依赖代码。

插件选项位于 **Settings | Tools | Maven Dependency Assistant**。

## 构建

需要 JDK 21。

```shell
./gradlew buildPlugin
```

插件包生成在 `build/distributions` 目录。

版本变更请参阅 [CHANGELOG.md](./CHANGELOG.md)。

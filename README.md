# GH Actions for WebStorm

A WebStorm plugin that shows GitHub Actions runs for the repository your open project was cloned from,
right inside the IDE. Author: [ravy.pro](http://ravy.pro).

[![Plugin build & release](https://github.com/Rovniy/github-actions-viewer/actions/workflows/plugin-release.yml/badge.svg)](https://github.com/Rovniy/github-actions-viewer/actions/workflows/plugin-release.yml)

## Features

- **GitHub Actions** tool window (bottom). It shows a tree of runs → jobs → steps with statuses and durations.
  You can filter by workflow or show only the current branch.
- Job and step logs in a console, with errors and warnings highlighted. Timestamps are optional.
- Commands:
  - **Run Workflow…** triggers `workflow_dispatch`. The form for inputs is built from the local workflow file.
  - **Re-run all jobs**, **Re-run failed jobs**, **Re-run selected job**, **Re-run with debug logging**
  - **Cancel run**
  - **Open on GitHub**, **Open workflow file**
- The list refreshes automatically while the tool window is visible (every 15 s by default).

The repository is taken from the project's git remotes (`origin` first, then `upstream`, then any GitHub remote).
GitHub Enterprise hosts work too.

> GitHub can't re-run a single step, either through the API or in its web UI. The smallest unit you can re-run
> is a job, which is why the plugin offers **Re-run selected job** and **Re-run failed jobs** instead.

## Installation

1. Download `github-actions-plugin-<version>.zip` from the
   [latest release](https://github.com/Rovniy/github-actions-viewer/releases/latest).
2. In WebStorm, open **Settings | Plugins | ⚙ | Install Plugin from Disk…** and pick the zip. Don't unpack it.
3. Open **View | Tool Windows | GitHub Actions**.

Requires WebStorm 2026.1 or newer. Other IntelliJ-based IDEs that have the Git plugin should work as well.

## Authentication

The plugin uses the first token it finds:

1. **Settings | Tools | GitHub Actions** (stored in the IDE password safe);
2. the `GITHUB_TOKEN` or `GH_TOKEN` environment variable;
3. the GitHub CLI: `gh auth token`. If you've already run `gh auth login`, you don't need to configure anything.

A classic token needs the `repo` scope. A fine-grained token needs the **Actions: Read and write**
permission on the repository.

## Development

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for building, running, the architecture, releases and maintenance.

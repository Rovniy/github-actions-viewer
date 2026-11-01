# Development and maintenance

This guide covers building, debugging, releasing and maintaining the **GH Actions** WebStorm plugin.
Author and maintainer: [ravy.pro](http://ravy.pro).

- [Requirements](#requirements)
- [Quick start](#quick-start)
- [Project layout](#project-layout)
- [Architecture](#architecture)
- [Testing](#testing)
- [Debugging](#debugging)
- [CI/CD and releases](#cicd-and-releases)
- [Maintenance](#maintenance)
- [Troubleshooting](#troubleshooting)
- [Known limitations](#known-limitations)

## Requirements

| Tool | Version | Notes |
|---|---|---|
| JDK to run Gradle | 17+ | WebStorm's bundled JBR works (`<WebStorm>/jbr`). |
| JDK 21 toolchain | 21 | Used to compile. Gradle downloads it automatically (foojay resolver) if you don't have it. |
| Gradle | 9.8.1 | Through the wrapper (`./gradlew`). Don't install it separately. |
| Target IDE | WebStorm 2026.1+ (build 261+) | A local install, or a WebStorm downloaded by Gradle. |

Pinned build versions are in `build.gradle.kts` (Kotlin 2.3.21, IntelliJ Platform Gradle Plugin 2.19.0) and
`gradle/wrapper/gradle-wrapper.properties`.

## Quick start

```bash
# Windows (Git Bash); on macOS/Linux, point JAVA_HOME at any JDK 17+
export JAVA_HOME="/c/Program Files/JetBrains/WebStorm 2025.2.4/jbr"

./gradlew runIde                 # start a sandbox WebStorm with the plugin installed
./gradlew test                   # unit tests
./gradlew buildPlugin            # build/distributions/github-actions-plugin-<version>.zip
./gradlew verifyPluginStructure  # check plugin.xml and the archive layout
```

The sandbox IDE keeps its own config, plugins and logs in `build/idea-sandbox/`. Open any project with a GitHub
remote there, then open **View | Tool Windows | GitHub Actions**.

### Target IDE

`gradle.properties` controls which IDE the plugin is compiled against and run in:

| Property | Meaning |
|---|---|
| `localIdePath` | Path to an installed IDE. Used when the directory exists, so nothing is downloaded. |
| `platformVersion` | WebStorm version to download when `localIdePath` is empty or missing (as on CI). |
| `pluginMajorMinor` | First two parts of the version. See [Versioning](#versioning). |

To build exactly the way CI does: `./gradlew buildPlugin -PlocalIdePath=`. The first run downloads WebStorm, which is a large download.

## Project layout

```
.github/workflows/plugin-release.yml   CI: build, test, package, publish a GitHub release
build.gradle.kts, settings.gradle.kts  Gradle build (IntelliJ Platform Gradle Plugin 2.x)
gradle.properties                      Versions and target IDE
src/main/resources/META-INF/plugin.xml Plugin descriptor: tool window, settings page, notification group
src/main/kotlin/games/xploit/ghactions/
  api/       GitHubApiClient (REST calls) and the JSON models
  auth/      TokenProvider: settings → env → gh CLI
  git/       RepoDetector: git remotes → owner/repo/host, current branch
  settings/  Persistent settings (+ token in PasswordSafe) and the Settings page
  ui/        Tool window factory, main panel, tree nodes/renderer, Run Workflow dialog, status icons
  util/      Log parsing, workflow_dispatch input parsing, time formatting
src/test/kotlin/                       Unit tests for the pure logic
```

## Architecture

```
GhActionsToolWindowFactory
        │ creates
        ▼
GhActionsPanel ──► RepoDetector (Git4Idea)       which repo / branch
   │   │      ──► TokenProvider                  which token
   │   │      ──► GitHubApiClient                REST calls (pooled thread)
   │   └─ Tree (RunData → JobData → StepData)    RunTreeRenderer, StatusIcons
   └──── ConsoleView                              LogFormatter renders logs
```

### Key decisions

- **Threading.** All network and `gh` CLI calls run on a pooled thread through `GhActionsPanel.background(...)`.
  Results come back on the EDT with `invokeLater`, and are dropped if the panel has been disposed. Don't call
  `GitHubApiClient` or `TokenProvider` on the EDT.
- **Refresh.** `refresh()` loads the workflows (on a forced refresh), the runs, and the jobs of every expanded run.
  Then it rebuilds the tree and restores expansion and selection using `NodeData.key`. The log view reloads only
  when the selected node's `NodeData.version` (its status) changes, so auto-refresh doesn't make the log flicker.
- **Auto-refresh.** A 5-second ticker checks whether the tool window is visible and whether
  `refreshSeconds` has passed since the last refresh.
- **Logs.** `GET /actions/jobs/{id}/logs` answers with a redirect to a signed blob URL. The client follows that
  redirect itself, **without** the `Authorization` header, because blob storage rejects foreign auth headers.
  Logs of completed jobs are cached in memory for each job id.
- **Step logs.** A job log has no step markers, so `LogFormatter.linesForStep` picks lines by the step's
  `started_at`/`completed_at` window. Lines without a timestamp inherit the previous line's timestamp.
- **Workflow inputs.** `WorkflowInputsParser` reads `on.workflow_dispatch.inputs` from the *local* workflow file
  with a small indentation-based scan. This avoids depending on the YAML plugin. Flow-style input mappings
  aren't supported. Add a test case before you extend it.
- **HTTP.** `java.net.http.HttpClient` with `ProxySelector.getDefault()`, so the IDE's proxy settings apply.
  Every request sends the `X-GitHub-Api-Version: 2022-11-28` header.
- **Secrets.** The token is stored in the IDE `PasswordSafe`, never in `ghActionsPlugin.xml`.

### Adding a new command

1. Add the REST call to `GitHubApiClient`. Use `post(...)` for actions.
2. In `GhActionsPanel`, declare an action with `action(text, icon, enabled) { apiCommand("…") { it.yourCall() } }`
   and add it to `createToolbarGroup()` and/or `createContextGroup()`.
   Action properties are declared **before** the `init { setup() }` block at the end of the class. Kotlin
   initializes properties in declaration order, so keep that block last.
3. `apiCommand` shows a notification and refreshes the tree after 3 s and 10 s, because GitHub registers new
   runs with a delay.

## Testing

- `./gradlew test` runs JUnit 4 tests in `src/test/kotlin`. They cover remote URL parsing, `workflow_dispatch`
  input parsing, log parsing and step filtering. These parts are pure Kotlin and easy to break, so add a test
  there whenever you touch them.
- The UI and the API calls have no automated tests. Check them by hand in `./gradlew runIde` against a repository
  you control: list runs, open a job log, open a step log, run a workflow with inputs, re-run, cancel.
- CI runs `test`, `buildPlugin` and `verifyPluginStructure` on every push and pull request.

## Debugging

- **Debugger:** run the `runIde` Gradle task in debug mode from the IDE (Gradle tool window → `intellij platform` →
  `runIde` → Debug). Breakpoints in plugin code work.
- **Logs:** `build/idea-sandbox/<IDE version>/log/idea.log`. To see `TokenProvider` debug messages, add
  `#games.xploit.ghactions` in **Help | Diagnostic Tools | Debug Log Settings** of the sandbox IDE.
- **API by hand:** `gh api repos/<owner>/<repo>/actions/runs` returns the same data the plugin gets.
- **Reset settings:** delete `build/idea-sandbox/<IDE version>/config/options/ghActionsPlugin.xml`.

## CI/CD and releases

Workflow: `.github/workflows/plugin-release.yml`.

| Trigger | What happens |
|---|---|
| Push to `main` | Build, test and package, then publish a GitHub release `v<version>` with the zip attached. |
| Pull request | Build, test and package. The zip is uploaded as a workflow artifact. Nothing is released. |
| Manual (`workflow_dispatch`) | Same as a push. A release is published only when it runs on `main`. |

Changes that touch only `*.md`, `docs/**` or `.idea/**` don't trigger the workflow.

Jobs:

1. **build**: JDK 21 (Temurin), `gradle/actions/setup-gradle`. The setup-gradle cache includes the downloaded WebStorm,
   so later runs are much faster. Then `./gradlew test buildPlugin verifyPluginStructure -PpluginVersion=… -PlocalIdePath=`.
   It uploads the zip as an artifact, and the test report if the job fails.
2. **release** (main only, `contents: write`): downloads the artifact and runs `gh release create v<version>`
   with install notes plus notes generated from the merged PRs and commits. If the tag already exists (a re-run
   of the same workflow run), it replaces the asset instead of failing.

The workflow uses only the built-in `GITHUB_TOKEN` and doesn't need any secrets.

### Versioning

The version is `<pluginMajorMinor>.<GitHub run number>`, for example `0.1.42`. Every push to `main` produces
a new, increasing version without any commits back to the repository.

- To bump the minor or major version, edit `pluginMajorMinor` in `gradle.properties`, e.g. `0.2` or `1.0`.
- Local builds are versioned `<pluginMajorMinor>.0-SNAPSHOT`.
- Run numbers never reset. After a bump, the patch number keeps counting from the current run number
  (`0.1.57` → `0.2.58`). That's expected.

### Publishing to JetBrains Marketplace (optional)

CI doesn't do this yet. To add it:

1. Create a Marketplace token and add it as the `PUBLISH_TOKEN` repository secret.
   Optionally add signing secrets as well (`CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`).
2. Configure `intellijPlatform { publishing { token = providers.environmentVariable("PUBLISH_TOKEN") } }`
   (and `signing { … }`) in `build.gradle.kts`.
3. Add a `./gradlew publishPlugin` step to the release job.

Check the plugin name and description in `plugin.xml` against the Marketplace guidelines before the first upload.

## Maintenance

### Supporting a new IDE version

The plugin declares `sinceBuild = "261"` and no `untilBuild`, so new IDE versions load it. When a new
WebStorm comes out:

1. Set `platformVersion` (and `localIdePath`, if you use a local install) to the new version.
2. Run `./gradlew test buildPlugin runIde` and fix any compilation errors or deprecation warnings.
3. Optionally run JetBrains Plugin Verifier: `./gradlew verifyPlugin`. It needs
   `intellijPlatform { pluginVerification { ides { recommended() } } }` in `build.gradle.kts`, and it downloads
   several IDEs.
4. Raise `sinceBuild` only if you start using an API that doesn't exist in older builds.

### Updating build tooling

| Component | Where | How |
|---|---|---|
| Gradle | wrapper | `./gradlew wrapper --gradle-version <x.y.z>` |
| IntelliJ Platform Gradle Plugin | `build.gradle.kts` | Bump `org.jetbrains.intellij.platform`. Check the [changelog](https://github.com/JetBrains/intellij-platform-gradle-plugin/releases). |
| Kotlin | `build.gradle.kts` | Keep it close to the Kotlin version bundled with the target IDE. |
| GitHub Actions | `.github/workflows/plugin-release.yml` | Actions are pinned to major tags (`@v7`). Bump them when a new major comes out. |

As a rule, wait about two weeks after a dependency release before adopting it.

### GitHub API

- All endpoints are under `/repos/{owner}/{repo}/actions/…`. See the
  [REST API docs](https://docs.github.com/en/rest/actions).
- If GitHub publishes a new `X-GitHub-Api-Version`, review the breaking changes before you change the header in
  `GitHubApiClient.request`.
- Rate limit: about 5,000 requests per hour for an authenticated user. One refresh costs 1–2 requests plus one
  per expanded run, so the default 15-second interval stays well below the limit.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| "No GitHub remote found in this project" | The project has no git remote on a GitHub host, or Git4Idea hasn't finished loading. The panel picks the repo up on the next git change. You can also press Refresh. |
| "GitHub token not found" | Set a token in Settings, export `GITHUB_TOKEN`, or run `gh auth login`. The IDE must be able to find `gh` on its `PATH`. |
| HTTP 401 | The token is invalid or expired. The cached `gh` token is dropped automatically, so press Refresh. |
| HTTP 403/404 on actions | The token has no access to the repository, or lacks the `repo` scope (classic) or the Actions write permission (fine-grained). |
| Run Workflow: "no workflow_dispatch trigger" | The workflow file has no `workflow_dispatch` trigger. GitHub accepts dispatches only for workflows that have one. |
| Log of a running job is empty | Expected. GitHub serves job logs through the API only after the job finishes. |
| Gradle: "Cannot find a Java installation … languageVersion=21" | The foojay resolver couldn't download a JDK (offline or behind a proxy). Install a JDK 21 or configure Gradle's proxy. |
| CI build is slow the first time | WebStorm is downloaded on the first run. Later runs restore it from the setup-gradle cache. |

## Known limitations

- **No "run from step":** GitHub can't re-run individual steps. The smallest unit is a job.
- **Logs of running jobs:** they aren't available until the job finishes. There is no live streaming.
- **Step log boundaries:** steps are matched by time, so lines from a neighbouring step logged in the same second
  can show up at the edges.
- **Workflow inputs:** they are read from the local file, so if you changed the inputs on another branch, the
  form shows the local version.

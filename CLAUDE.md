# CLAUDE.md

WebStorm plugin **GH Actions**. It shows the GitHub Actions runs, jobs, steps and logs of the repository the open
project was cloned from, and can run, re-run and cancel workflows. Kotlin, IntelliJ Platform Gradle Plugin 2.x.
Repo: `Rovniy/github-actions-viewer`. Author: Andrei Rovniy, http://ravy.pro.

Read `docs/DEVELOPMENT.md` for the full developer guide. This file lists only what you need to work safely and the
things that are easy to get wrong.

## Working with the owner

- The owner writes in **Russian**: answer in Russian. Code, comments, UI strings and docs are in **English**.
- Don't commit or push unless asked. A push to `main` **publishes a public GitHub release** (see CI below).
- `README.md` is user-facing and `docs/DEVELOPMENT.md` is for developers. Update them when behaviour, commands or
  the release process change.

## Commands

The environment is Windows with Git Bash. The system `java` is JDK 26; Gradle was verified on WebStorm's JBR 25
instead, so set `JAVA_HOME` first:

```bash
export JAVA_HOME="/c/Program Files/JetBrains/WebStorm 2025.2.4/jbr"   # folder name is stale; it contains WebStorm 2026.1.x
./gradlew test                                # JUnit 4, pure-logic tests
./gradlew buildPlugin verifyPluginStructure   # zip → build/distributions/
./gradlew runIde                              # sandbox WebStorm (GUI; the owner checks it by hand)
./gradlew test buildPlugin verifyPluginStructure -PlocalIdePath= -PpluginVersion=0.1.999   # exactly what CI runs
```

Before you call a change done: `test buildPlugin verifyPluginStructure` must pass with **no new compiler warnings**,
including deprecations, which the platform removes over time.
To check the API client against the real GitHub API, write a temporary JUnit test that takes the token from
`gh auth token` and calls `GitHubApiClient` on a public repo (`actions/checkout` worked). Delete the test afterwards.
`gh` is installed and logged in as `Rovniy`.

## Layout

```
src/main/kotlin/games/xploit/ghactions/
  api/GitHubApiClient.kt       blocking REST client (java.net.http), GitHubModels.kt = Gson data classes
  auth/TokenProvider.kt        token: Settings (PasswordSafe) → GITHUB_TOKEN/GH_TOKEN → `gh auth token` (cached per host)
  git/RepoDetector.kt          Git4Idea remotes → host/owner/name (origin > upstream > any github host); current branch
  settings/                    GhActionsSettings (app-level state + token), GhActionsConfigurable (Settings | Tools)
  ui/GhActionsPanel.kt         main tool window panel: tree, console, toolbar/context actions, refresh, commands
  ui/RunTree.kt                NodeData (RunData/JobData/StepData) + LoadingData/MessageData + renderer
  ui/RunWorkflowDialog.kt      workflow_dispatch dialog, inputs built from the local workflow YAML
  util/LogFormatter.kt         log parsing (timestamps, ##[group]/##[error]…, ANSI strip), per-step filtering
  util/WorkflowInputsParser.kt indentation-based parser for on.workflow_dispatch.inputs
src/main/resources/META-INF/plugin.xml   tool window "GitHub Actions", applicationConfigurable, notificationGroup
src/test/kotlin/.../PureLogicTest.kt     tests for URL parsing, inputs parser, log parser/step filter
```

## Things that are easy to break

- **Init order in `GhActionsPanel`.** Action properties are declared in the class body, and `init { setup() }` must
  stay the **last** member, because Kotlin initializes properties in declaration order. If an `init` block comes
  earlier, the toolbar builds with null actions.
- **Threading.** Network and `gh` calls go only through `GhActionsPanel.background(repo, task, onSuccess, onError)`.
  It runs `task` on a pooled thread and the callbacks on the EDT, and skips them if the panel is disposed.
  Never call `GitHubApiClient` or `TokenProvider` on the EDT. Actions use `ActionUpdateThread.EDT`.
- **Job logs.** `/actions/jobs/{id}/logs` returns a 302 to a signed blob URL. The redirect is followed by hand
  **without the `Authorization` header** (the client uses `Redirect.NEVER`). Don't switch to automatic redirects.
- **Logs of running jobs** aren't served by the API. The UI shows the step list and reloads when the job's
  `NodeData.version` changes.
- **Step logs** are cut out of the job log by the step's time window, since there are no step markers.
  Neighbouring lines from the same second can leak in, and that's acceptable.
- **Refresh.** The tree is fully rebuilt on every refresh. Expansion and selection are restored by `NodeData.key`,
  and the log reloads only when `key` or `version` changes. During a rebuild, `rebuilding = true` blocks the
  selection listener. Keep this mechanism if you change the tree, otherwise auto-refresh makes the log flicker.
- **Gson models.** Every field has a default value, so Kotlin generates a no-arg constructor and Gson keeps the
  defaults. Don't add non-defaulted fields: Gson would leave them null through Unsafe.
- **WorkflowInputsParser** is a deliberately small scanner, so the plugin doesn't need the YAML plugin. Comments are
  stripped only outside quotes. Write a test in `PureLogicTest` first when you extend it.
- **"Run from step" is impossible.** The GitHub API and web UI can only re-run whole jobs. The owner asked for this
  feature originally and was told it can't be done. Don't fake it.

## Build and CI facts

- **Pinned versions:** Gradle 9.8.1 (wrapper), Kotlin 2.3.21, `org.jetbrains.intellij.platform` 2.19.0,
  foojay resolver 1.0.0 (`settings.gradle.kts`; it provisions the JDK 21 toolchain).
  `sinceBuild = 261`, no `untilBuild`. Bytecode target is Java 21.
  `kotlin.stdlib.default.dependency=false`, because the IDE bundles the stdlib.
- **Target IDE:** if the `localIdePath` directory exists, it's used. Otherwise WebStorm `platformVersion` is
  downloaded (CI passes `-PlocalIdePath=`). Bundled plugin dependency: `Git4Idea`.
- **Version:** `-PpluginVersion` overrides; the default is `<pluginMajorMinor>.0-SNAPSHOT`. CI uses
  `<pluginMajorMinor>.<run_number>`. To bump, change `pluginMajorMinor` in `gradle.properties`.
- **`.github/workflows/plugin-release.yml`:**
  - push to `main` → build + test + release `v<version>` with the zip
  - PR → build only
  - changes to `**.md`, `docs/**` or `.idea/**` don't trigger it
  - the release is made with `gh release create --generate-notes`; a re-run of the same run replaces the asset
  - actions are pinned to major tags
  - first green run: 2026-11-01, release `v0.1.1`
- `.gitattributes` forces LF for `gradlew`. Keep it, or the Linux runner may fail.
- When you upgrade anything, choose releases at least ~2 weeks old.

## Picking the project up after a long break

1. `git log --oneline -20` and `gh run list -R Rovniy/github-actions-viewer -L 5`: see what changed and whether CI
   is green.
2. Check the installed WebStorm: `cat "/c/Program Files/JetBrains/"*/product-info.json | grep -E '"version"|buildNumber'`.
   If it's newer than `platformVersion`, update `platformVersion` and `localIdePath`, rebuild, and fix deprecations.
3. Look for newer Gradle, IntelliJ Platform Gradle Plugin, Kotlin and action versions, and bump them one at a time
   with a full build after each. The "Maintenance" section of `docs/DEVELOPMENT.md` has the steps.
4. Run the full build and the API smoke test described above before you start new work.

## Ideas not done yet

These were discussed or are natural next steps; nothing has been requested yet:
- Marketplace publishing (the steps are in `docs/DEVELOPMENT.md`)
- Plugin Verifier in CI
- using the account from the bundled GitHub plugin instead of a separate token
- downloading run artifacts
- ANSI colours in logs (they are currently stripped)

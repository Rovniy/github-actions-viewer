package games.xploit.ghactions

import games.xploit.ghactions.api.Step
import games.xploit.ghactions.git.RepoDetector
import games.xploit.ghactions.util.LogFormatter
import games.xploit.ghactions.util.LogLineKind
import games.xploit.ghactions.util.WorkflowInputsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PureLogicTest {

    @Test
    fun parsesRemoteUrls() {
        val expected = RepoDetector.Parsed("github.com", "octo", "hello-world")
        assertEquals(expected, RepoDetector.parseRemoteUrl("https://github.com/octo/hello-world.git"))
        assertEquals(expected, RepoDetector.parseRemoteUrl("https://github.com/octo/hello-world"))
        assertEquals(expected, RepoDetector.parseRemoteUrl("git@github.com:octo/hello-world.git"))
        assertEquals(expected, RepoDetector.parseRemoteUrl("ssh://git@github.com:22/octo/hello-world.git"))
        assertEquals(expected, RepoDetector.parseRemoteUrl("https://user:pass@github.com/octo/hello-world.git/"))
        assertEquals(
            RepoDetector.Parsed("ghe.corp.io", "team", "svc"),
            RepoDetector.parseRemoteUrl("git@ghe.corp.io:team/svc.git"),
        )
        assertNull(RepoDetector.parseRemoteUrl("not a url"))
    }

    @Test
    fun parsesDispatchInputs() {
        val yaml = """
            name: Deploy
            on:
              push:
                branches: [main]
              workflow_dispatch:
                inputs:
                  environment:
                    description: "Target environment"   # comment
                    required: true
                    type: choice
                    options:
                      - staging
                      - production
                    default: staging
                  dry_run:
                    description: >
                      Multi-line: description
                      continues here
                    type: boolean
                    default: false
                  version:
                    description: 'Version to deploy'
                  tags:
                    type: choice
                    options: [a, "b", c]
            jobs:
              deploy:
                runs-on: ubuntu-latest
        """.trimIndent()
        val info = WorkflowInputsParser.parse(yaml)
        assertTrue(info.hasDispatch)
        assertEquals(listOf("environment", "dry_run", "version", "tags"), info.inputs.map { it.name })

        val env = info.inputs[0]
        assertEquals("Target environment", env.description)
        assertTrue(env.required)
        assertEquals("choice", env.type)
        assertEquals(listOf("staging", "production"), env.options)
        assertEquals("staging", env.default)

        val dry = info.inputs[1]
        assertEquals("boolean", dry.type)
        assertEquals("false", dry.default)
        assertNull(dry.description)

        assertEquals("Version to deploy", info.inputs[2].description)
        assertEquals("string", info.inputs[2].type)
        assertEquals(listOf("a", "b", "c"), info.inputs[3].options)
    }

    @Test
    fun detectsDispatchWithoutInputs() {
        assertTrue(WorkflowInputsParser.parse("on: [push, workflow_dispatch]").hasDispatch)
        assertTrue(WorkflowInputsParser.parse("on:\n  workflow_dispatch:\n  push:\n").let { it.hasDispatch && it.inputs.isEmpty() })
        assertFalse(WorkflowInputsParser.parse("on:\n  push:\n# workflow_dispatch:\n").hasDispatch)
    }

    @Test
    fun parsesLogAndFiltersBySteps() {
        val raw = "﻿2026-11-01T10:00:00.1000000Z ##[group]Run actions/checkout@v4\n" +
            "2026-11-01T10:00:00.2000000Z with: x\n" +
            "2026-11-01T10:00:01.0000000Z ##[endgroup]\n" +
            "2026-11-01T10:00:05.5000000Z \u001B[31mnpm ERR!\u001B[0m boom\n" +
            "continuation line\n" +
            "2026-11-01T10:00:06.0000000Z ##[error]Process completed with exit code 1.\n"
        val lines = LogFormatter.parse(raw)
        assertEquals(6, lines.size)
        assertEquals(LogLineKind.GROUP, lines[0].kind)
        assertEquals("▼ Run actions/checkout@v4", lines[0].text)
        assertTrue(lines[2].isHidden)
        assertEquals("npm ERR! boom", lines[3].text)
        assertEquals(lines[3].time, lines[4].time)
        assertEquals(LogLineKind.ERROR, lines[5].kind)

        val checkout = Step(name = "checkout", number = 1, startedAt = "2026-11-01T10:00:00Z", completedAt = "2026-11-01T10:00:01Z")
        assertEquals(3, LogFormatter.linesForStep(lines, checkout).size)
        val build = Step(name = "build", number = 2, startedAt = "2026-11-01T10:00:05Z", completedAt = null)
        assertEquals(3, LogFormatter.linesForStep(lines, build).size)
    }
}

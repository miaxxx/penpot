# Harness directory guide

| Path | Responsibility | Change frequency |
| --- | --- | --- |
| `AGENTS.md` | Short mandatory entry and routing rules | Low |
| `CLAUDE.md` | Claude Code compatibility router | Low |
| `.serena/memories/` | Penpot architecture and module knowledge | As architecture evolves |
| `.harness/GUIDE.md` | Five-system model and work loop | Low |
| `.harness/RULES.md` | Non-negotiable execution constraints | Low |
| `.harness/TOOLS.md` | Tool and command catalog | Medium |
| `.harness/CHECKS.md` | Verification policy | Medium |
| `.harness/environment.json` | Machine-readable environment contract | Medium |
| `.harness/feature_list.json` | Harness capability inventory | Medium |
| `.harness/task-map.json` | Current task source-output-evidence state | Every long task |
| `.harness/PROGRESS.md` | Human progress summary | Every milestone |
| `.harness/session-handoff.md` | Resume instructions | Before an unfinished stop |
| `scripts/harness/` | Executable initialization, checks, state, traceability | Medium |
| `docs/harness/` | Explanations and examples | Medium |

Do not turn `AGENTS.md` into an encyclopedia. It routes agents to the focused
source of truth.

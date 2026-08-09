# Agent Workflow

## 1. Read

Read `critical-info`, affected module core memories, active feature state and
the last handoff. Follow only relevant references to keep context bounded.

## 2. Initialize

Run `./init.sh --check`. Record missing tools or unhealthy services as blockers;
do not silently substitute a different runtime.

## 3. Scope

Choose one feature. Confirm dependencies, allowed paths, acceptance criteria,
required checks and ownership before edits.

## 4. Inspect

Build/query the focused source map, then confirm findings in source. Inspect
nearby tests and transaction boundaries.

## 5. Plan and execute

Use small reversible changes. Keep generated AI design preview separate from
native apply. Stop if the task requires destructive operations or new secrets.

## 6. Verify

Run registered checks. A passing check is evidence; a convincing explanation is
not. Failures feed the next edit cycle.

## 7. Record and hand off

Update feature status, progress, evidence and the exact next command. End in a
state another agent can resume without the previous chat.

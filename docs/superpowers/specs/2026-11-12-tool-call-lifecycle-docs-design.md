# Tool-call lifecycle documentation design

## Goal

Help runtime implementers understand the persisted tool-call flow, especially
what a claim means and how policy denial differs from user rejection.

## Scope and approach

Add a short chronological flow to the existing Tool-call lifecycle section in
`docs/modules/onecx-chat-svc/pages/onecx-chat-svc.adoc`. Keep its current
contract bullets and add an ordered explanation of registration, policy and
user decisions, claims, execution outcomes, optional continuations, and runtime
replay. Define a claim as serialized, owner-bound permission to execute a
tool, not proof that the tool has executed.

Clarify that the runtime evaluates policy and invokes the tool, while the
service persists lifecycle state and prevents unapproved execution. Explain
that policy `DENY` and an `ALWAYS_ASK` proposal rejected by a user both block
execution but have different recorded states and reasons. Distinguish the
tool outcome from optional sanitized continuation text, and explain replay as
the ordered lifecycle history returned to the runtime.

## Validation

Review the AsciiDoc section for accurate state transitions, readable sequence,
and consistent operation names. This documentation-only change does not need
behavioral tests.

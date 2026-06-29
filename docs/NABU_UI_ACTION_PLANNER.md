# Nabu Vision + UI Tree Action Planner

## Ownership

Nabu owns:

- goal interpretation and model prompting
- UI-tree indexing and compact planner state
- the constrained action DSL
- stale-state checks, safety policy, confirmation, traces, and loop limits

Glaive owns:

- `AccessibilityService` lifecycle and capability checks
- atomic active-window observation
- screenshots and accessibility node capture
- resolving validated selectors against the live tree
- gestures, node actions, global actions, and execution results

The model never invokes Glaive directly. It emits JSON, Nabu parses and validates it, and only then may Nabu call a narrow Glaive tool.

## Current Bridge Audit

Glaive already exports `take_screenshot` and `read_screen`. Its accessibility service can capture screenshots and serialize the active application window.

The current bridge is not sufficient for an execution loop:

- screenshot and XML are separate calls and may describe different screen states
- XML omits package name, resource ID, editability, visibility, and window metadata
- results are shared-storage paths rather than one observation envelope
- there are no accessibility action tools
- XML IDs are not stable or shared between observation and execution

## Indexed State

`UiTreeIndexer` accepts current Glaive XML and UIAutomator-style XML. It produces deterministic IDs from package, resource ID, text, content description, class, bounds, and tree path.

IDs are stable only for a specific observed tree. Every plan includes `screen_id`; Nabu rejects a plan produced for an older observation.

## Planner Contract

The planner returns JSON only:

```json
{
  "goal": "Turn on dark mode",
  "screen_id": "6f37e3d5d71a21ef",
  "steps": [
    {
      "action": "tap",
      "target": {
        "element_id": "e_36bf2419b5c59ac4",
        "fallback_bounds": [920, 215, 1010, 285]
      }
    },
    {
      "action": "assert",
      "condition": {
        "element_id": "e_36bf2419b5c59ac4",
        "checked": true
      }
    }
  ]
}
```

Each model response contains exactly one non-assert action and at most one trailing assertion. Nabu observes again after every executed action.

Supported core actions:

- `tap`
- `long_press`
- `type_text`
- `press_back`
- `press_home`
- `scroll`
- `wait`
- `assert`
- `ask_user`
- `done`

## Safety Decisions

Validation returns one of:

- `Allow`
- `RequireConfirmation`
- `Block`
- `Invalid`

The initial policy blocks payments, purchases, banking transfers, passwords, 2FA/auth approvals, account deletion, factory reset, and unknown APK installation. Sending, posting, calling, deleting files, changing security settings, and granting permissions require confirmation.

This keyword policy is only the first layer. Before enabling execution, policy should also classify the active package, target element semantics, action type, and recent action trace.

## Glaive Bridge V2

Add a versioned `observe_ui` tool that captures one logical state and returns:

```json
{
  "schema_version": 2,
  "observation_id": "...",
  "captured_at_ms": 0,
  "package": "com.android.settings",
  "window_title": "Settings",
  "rotation": 0,
  "display_bounds": [0, 0, 1080, 2376],
  "xml_path": "...",
  "screenshot_path": "..."
}
```

The XML must include `package`, `resource-id`, `editable`, `visible`, `clickable`, `enabled`, `scrollable`, `long-clickable`, `checkable`, `checked`, `password`, bounds, and tree path.

Add narrow execution tools:

- `ui_tap`
- `ui_long_press`
- `ui_set_text`
- `ui_scroll`
- `ui_global_action`

Every call must include `observation_id` and a selector copied from the observation. Glaive must re-resolve the selector against the current active window and reject it if package/window identity changed. Coordinate fallback is allowed only inside the observed display bounds.

## Iterative Loop

1. Nabu calls `observe_ui`.
2. Nabu indexes XML and attaches the screenshot only when visual context is needed.
3. The model emits one action and optional assertion.
4. Nabu parses and validates the plan.
5. Nabu blocks, asks for confirmation, asks the user, or calls one Glaive action.
6. Nabu observes again and evaluates the assertion.
7. Stop on success, user intervention, policy decision, repeated state, or loop budget.

Initial limits should be five actions, one confirmation at a time, a 30-second total deadline, and termination after two unchanged observations.

# AmadeusClient

AmadeusClient is an Android client used by AstrBot to control a physical phone.

## Project Status

This project is still under active development.

## What It Is

AmadeusClient is the device-side app in the AstrBot system.
It does not perform complex reasoning.
It focuses on two tasks:

1. Perception: capture the current UI state through Android Accessibility.
2. Action execution: execute phone actions sent by AstrBot.

## Current Implementation

The current version already includes:

1. Accessibility service registration and runtime enablement flow.
2. Real-time UI tree capture from the active window.
3. UI snapshot serialization into structured `ui_state` JSON.
4. Snapshot filtering and deduplication to reduce repeated noise.
5. In-app visual preview that renders the latest `ui_state` as a screen-like layout.

## High-Level Principle

1. Android Accessibility events are observed on-device.
2. The active UI node tree is converted into a structured snapshot.
3. The snapshot is used as the perception input for AstrBot.
4. AstrBot will later send action commands back to this client for execution.

## Next Steps

Planned work includes:

1. WebSocket communication with AstrBot.
2. Action command execution pipeline (`click`, `swipe`, `input_text`, `back`, `home`).
3. Better handling for black-box / low-accessibility pages.

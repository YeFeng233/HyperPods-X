# HyperPods-X Miuix UI guidelines

Read this file before changing the Miuix settings renderer. The source is
[Mishka's UI guidelines](https://github.com/YuKongA/Mishka/blob/bbd7960527d38c50b330a5e72ff93b605d1df644/docs/ui-guidelines.md),
retrieved on 2026-10-30. An unchanged copy is in
[reference/mishka-ui-guidelines.md](reference/mishka-ui-guidelines.md).
The rules below adapt its reusable UI conventions to this single-activity,
physical-device settings app. Mishka's proxy, subscription, traffic and tab-rail
rules do not apply here.

- Keep routes, section order, capability filtering and labels in `SettingsCatalog`.
  Both themes render that catalog; the Miuix renderer owns no device settings.
  Preserve the iOS component tree, materials and typography when updating shared data.
- Use Miuix 0.9.4 components and semantic theme colors. Preferences use
  `ArrowPreference`, `SwitchPreference`, `WindowDropdownPreference` and
  `BasicComponent`. Use library buttons, radio rows, sliders and text fields.
  Avoid hand-drawn arrows, switches, battery shells and generic Material controls.
  Inline trailing values match the closed `WindowDropdownPreference` trigger:
  `body2.fontSize`, `onSurfaceVariantActions` (disabled: `disabledOnSecondaryVariant`)
  and end alignment, with the library's default text weight and line height.
- Each screen uses `Scaffold`, a scroll-aware `TopAppBar` and `LazyColumn` with
  `scrollEndHaptic`, `overScrollVertical` and the top bar's nested scroll connection.
  Use `SmallTopAppBar` on wide or short windows. Large titles use the library's
  default foreground color, never a custom blue hero title.
- A page owns its blur backdrop. Record scrolling content once and use it for the
  top `BlurredBar`. Keep the fallback opaque and respect system dark mode.
- Keep list nodes full width. Center content using list horizontal padding on wide
  windows; include horizontal cutout/navigation insets. Use 12dp card side/bottom
  margins. A first card gets a 12dp top spacer; `SmallTitle` already supplies its
  own top spacing. Finish with a 24dp spacer plus navigation-bar padding.
- Multirow groups use individually keyed lazy card segments. First/last segments
  use clipped 16dp squircle corners, middle rows use a plain fill. Native preference
  content owns row padding. Static explanatory cards may remain a single item.
- Use squircle backgrounds for noninteractive fills, squircle clipping for images,
  and squircle surfaces for clickable custom shapes. Do not add offscreen clipping
  to pure fills. Native controls already supply their own shapes and feedback.
- Prefer Miuix icons. The native AirPods selector uses the installed Settings
  plugin's day/night circles and selection rings with the same inner mode artwork
  as the iOS theme. Battery glyphs use the HyperOS Symbols font
  subset. These renderer-specific resources must not replace iOS resources.
- Read confirmed values from `PodsState`, write through `PodsService` or
  `DeviceRepository`, and retain existing capability and pending checks. App-side
  ear automation and the earbud firmware sensor setting are different controls.
  Listening changes reuse the tested optimistic-intent reducer and serial command
  queue, including disconnect and timeout recovery.
- Collect screen flows with lifecycle awareness. Keep high-frequency state reads in
  draw/layout when possible. Do not create global device caches or perform protocol,
  root, host-restart or permission work during composition.
- Preserve the explicit device-settings entry point and current application data.
  Use `adb install -r` for updates; the user performs visual acceptance on the phone.

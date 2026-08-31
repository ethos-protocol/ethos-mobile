# Widget Refresh Budget Constraints

WidgetKit (iOS) and Glance (Android) both impose OS-level limits on how often a
widget can actually redraw, independent of what the app requests. This is a
reference for anyone extending widget refresh behavior (#198, #244, #248) so the
real-world ceiling is known up front instead of being rediscovered by trial and
error.

## WidgetKit (iOS)

- WidgetKit does not guarantee `TimelineEntry` execution at the requested time.
  It allocates each widget a daily "refresh budget" (observed to be on the
  order of **40-70 reloads per day** per widget kind on a device with typical
  usage), spent adaptively based on how often the user views the widget and
  the containing app.
- Requesting a shorter interval than the remaining budget allows does not
  bypass the limit — WidgetKit silently coalesces or delays the reload instead
  of erroring.
- Background App Refresh being disabled for the app, Low Power Mode, and the
  widget not being currently visible on a Home Screen/Lock Screen all reduce
  the effective budget further.
- There is no public API to query remaining budget; `WidgetCenter.reloadTimelines`
  calls beyond the budget are simply dropped.

### How `TTLWidget.swift` fits within the budget

`ios/EthosProtocol/Sources/Widget/TTLWidget.swift`'s `computeNextUpdateInterval`
scales the *requested* next-reload interval by vault urgency — 15 minutes when
the most urgent vault's TTL is comfortable, down to 2 minutes when it's under
30 minutes from expiry. This does not request unlimited refreshes: it spends
the same daily budget on refreshes concentrated around genuinely urgent
windows rather than spread evenly, so the widget is more likely to be current
when it matters most (a vault about to expire) instead of wasting budget on
frequent-but-low-value updates during long calm periods. The 15-minute floor
keeps normal-state usage well within observed daily budget even if the widget
is pinned and viewed frequently.

## Glance (Android)

Not yet implemented in this repo (no `GlanceAppWidget` exists as of this
writing). For whoever adds one:

- Glance widgets are subject to `WorkManager`-scheduled updates, and
  `GlanceAppWidgetManager` periodic updates are throttled to a **minimum
  ~30-minute interval** by AppWidgetManager (`updatePeriodMillis` below 30
  minutes is clamped up to 30 minutes by the platform).
- Sub-30-minute refresh (e.g. mirroring TTLWidget's 2-minute critical-urgency
  tier) requires an explicit one-off `WorkManager` job per update rather than
  a periodic request, and is still subject to Doze/App Standby deferral when
  the device is idle.

## Practical takeaway

Treat "refresh every N minutes" as a request, not a guarantee, on both
platforms. Design refresh logic (like the urgency scaling above) to make the
best use of a budget that's roughly an order of magnitude coarser than naive
per-minute polling would assume.

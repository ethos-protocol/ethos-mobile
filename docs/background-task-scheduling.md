# iOS Background Task Scheduling Budget (#204)

`BackgroundRefreshService` and `CheckInSyncTask` each register a distinct
`BGTaskScheduler` identifier:

| Task | Identifier | Request type | Requested cadence |
|---|---|---|---|
| TTL polling | `app.ethos-protocol.vault-ttl-refresh` | `BGAppRefreshTaskRequest` | every 3,600s (`earliestBeginDate`) |
| Check-in sync | `app.ethos-protocol.checkin-sync` | `BGProcessingTaskRequest`, `requiresNetworkConnectivity = true` | resubmitted immediately after every run, and whenever `PendingCheckInStore` gains an item |

Both are declared in `BGTaskSchedulerPermittedIdentifiers` (Info.plist) and
registered independently in `EthosProtocolApp`/`AppDelegate`.

## Budget considerations

`BGAppRefreshTaskRequest` and `BGProcessingTaskRequest` draw from separate
scheduling pools — `BGAppRefreshTask` budget is governed by app usage
patterns (roughly one opportunity per app-usage session), while
`BGProcessingTask` budget is longer-running but only granted opportunistically
(charging/idle, or `requiresNetworkConnectivity` conditions being met). They
are not competing for the exact same allowance, but both still count against
the device-wide ceiling iOS applies across *all* background work for the app,
so a device running many other background-heavy apps can still starve either
task.

`earliestBeginDate` on both requests is a lower bound, not a guarantee — the
actual cadence a device delivers can run well behind the requested interval,
especially for `BGAppRefreshTask` on a rarely-foregrounded app.

## Auditing real-world cadence

Neither task previously logged when it was scheduled or actually invoked,
which made it impossible to compare requested vs. observed cadence without
attaching a debugger. Both now emit an `os_log` (subsystem
`app.ethos-protocol`, category `background-scheduling`) signpost on
`scheduleAppRefresh()`/`scheduleSync()` and on task invocation
(`handleRefresh`/`handleSync`), so a multi-day trace can be pulled from
Console.app (device logs, filtered to that subsystem) to compare the
requested cadence above against what the OS actually delivers.

This is a prerequisite for the follow-up decision called for in #204 —
consolidating both tasks into a single dispatch point — which should only be
done once real-device data confirms the two tasks are actually competing for
budget rather than running independently at their requested cadence. No
consolidation has been made yet: the two tasks currently have different
scheduling requirements (`BGAppRefreshTaskRequest` with no network
requirement vs. `BGProcessingTaskRequest` requiring connectivity) that a
merged task would need to reconcile, and that reconciliation isn't warranted
without evidence of real starvation.

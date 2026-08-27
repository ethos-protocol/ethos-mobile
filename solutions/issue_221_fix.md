## TL;DR

* Add a **client‑side countdown** that starts from the last‑known TTL and the timestamp when it was fetched.
* Re‑calculate the countdown every second with a `setInterval`.
* When a new `vault_updated` event or a poll arrives, **reconcile** the local countdown with the fresh server value – the server value always wins.
* Add a pure helper (`reconcileTTL`) and unit tests that exercise the reconciliation logic.

Below is a full, self‑contained patch that can be dropped into the repo (the repo is assumed to be a React/TypeScript codebase that already has a `VaultTTL` component and a `useVault` hook that fetches `/vaults/:id/ttl`).

---

## 1.  Design Overview

| Concern | Solution |
|---------|----------|
| **Static TTL** | Render a *live* countdown that ticks every second. |
| **Drift** | Store the *fetch timestamp* and compute the remaining time as `serverTTL - (now - fetchedAt)`. |
| **Reconciliation** | On every fresh server value, recompute the remaining time. If the new remaining time differs from the local one, replace the local value – the server always wins. |
| **Testing** | Pure helper `reconcileTTL
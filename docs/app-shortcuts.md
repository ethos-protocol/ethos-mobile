# App Shortcuts Documentation

Ethos-Protocol supports iOS App Shortcuts (iOS 16.1+) for automation workflows.

## Available Shortcuts

### Check In Vault

Automatically checks in to a specified vault to extend its TTL.

**Parameters:**
- `vaultID` (String): The ID of the vault to check in to

**Example:**
```
Run App Intent "Check In Vault"
  Vault ID: "vault123abc..."
```

**Result:**
- Returns success message on successful check-in
- Throws error if vault not found or network unavailable

### View Vault Expiry

Retrieves the remaining TTL (time-to-live) for a specified vault.

**Parameters:**
- `vaultID` (String): The ID of the vault to check

**Example:**
```
Run App Intent "View Vault Expiry"
  Vault ID: "vault123abc..."
```

**Result:**
- Returns formatted TTL (e.g., "2d 14h 30m")
- Returns message if vault has no TTL information

## Usage in Shortcuts App

1. Open the Shortcuts app on your iOS device
2. Create a new shortcut
3. Search for "Ethos-Protocol" actions
4. Add "Check In Vault" or "View Vault Expiry" action
5. Enter the vault ID
6. Configure automation trigger (time, location, etc.)
7. Save and enable

## Supported Automation Triggers

- Time-based (daily, weekly, specific times)
- Location-based (on arrival/departure)
- Automatic (at app launch, on specific events)
- Manual (run immediately)

## Authentication

App Shortcuts require the user to be authenticated in the Ethos-Protocol app. 
If authentication is required, the app will be automatically opened.

## Error Handling

- **Vault Not Found**: Occurs when the provided vault ID doesn't exist
- **Authentication Required**: Occurs when user is not logged in
- **Network Error**: Occurs when network connectivity is unavailable

## Limitations

- Shortcuts require the app to be installed
- Some operations may require Face ID/Touch ID authentication
- Network connectivity is required for vault operations

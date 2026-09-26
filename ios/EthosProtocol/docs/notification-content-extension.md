# Notification Content Extension Setup

## Overview
The Notification Content Extension allows displaying rich, custom UI for check-in reminders and vault expiry notifications, including interactive buttons for snooze actions directly from the notification without opening the app.

## Setup Instructions

### 1. Create the Extension Target in Xcode
1. File → New → Target
2. Choose "Notification Content Extension"
3. Name it "EthosProtocolNotificationExtension"
4. Set minimum deployment target to iOS 16 (or later)

### 2. Configure Extension Info.plist
The extension's `Info.plist` should include:
```xml
<key>NSExtension</key>
<dict>
    <key>NSExtensionAttributes</key>
    <dict>
        <key>UNNotificationExtensionCategory</key>
        <string>CHECK_IN</string>
        <key>UNNotificationExtensionInitialContentSizeRatio</key>
        <real>1</real>
        <key>UNNotificationExtensionDefaultContentHidden</key>
        <false/>
    </dict>
    <key>NSExtensionPointIdentifier</key>
    <string>com.apple.usernotifications.content-extension</string>
</dict>
```

### 3. Implement NotificationViewController
```swift
import UserNotifications
import UserNotificationsUI

class NotificationViewController: UIViewController, UNNotificationContentExtension {
    @IBOutlet weak var vaultIDLabel: UILabel!
    @IBOutlet weak var ttlLabel: UILabel!
    @IBOutlet weak var statusLabel: UILabel!
    
    func didReceive(_ notification: UNNotification) {
        let userInfo = notification.request.content.userInfo
        let vaultID = userInfo["vault_id"] as? String ?? "Unknown"
        
        vaultIDLabel.text = "Vault: \(String(vaultID.prefix(12)))"
        ttlLabel.text = notification.request.content.body
        statusLabel.text = "Active"
    }
    
    func didReceive(_ response: UNNotificationResponse, completionHandler completion: @escaping (UNNotificationContentExtensionResponseOption) -> Void) {
        completion(.dismissAndForwardAction)
    }
}
```

### 4. Add Extension to App's Capability
1. In Xcode, select the main app target
2. Signing & Capabilities → + Capability → "App Groups"
3. Add the same App Group to the extension target

### 5. Update Notification Payload
Remote notifications should include:
```json
{
    "aps": {
        "alert": {
            "title": "Check-in Reminder",
            "body": "Vault ABC123 expires in 1d 5h"
        },
        "badge": 1,
        "sound": "default",
        "category": "CHECK_IN",
        "mutable-content": 1
    },
    "vault_id": "GXXXXXX...",
    "ttl_remaining": 86400
}
```

## Features Enabled

### Check-in Reminder Notifications
- Display vault ID and TTL remaining
- Show action buttons for:
  - "Check In Now" (requires biometric authentication)
  - "Snooze 7 days"
  - "Snooze 14 days"

### Visual Enhancements
- Formatted vault expiry countdown
- Color-coded status indicators
- Swipeable action buttons
- Lock screen integration (biometric auth required for check-in)

## Testing

### Simulate in Simulator
Use `simctl` to send a test notification:
```bash
xcrun simctl push booted com.ethosprotocol \
  '{
    "aps": {
      "alert": "Check-in Reminder",
      "category": "CHECK_IN",
      "mutable-content": 1
    },
    "vault_id": "GXXXXXX",
    "ttl_remaining": 86400
  }'
```

### Device Testing
Send from backend API or use Apple's push notification testing in Xcode.

## Notes
- `.customDismissAction` in notification category allows custom dismiss behavior
- `.authenticationRequired` ensures biometric check for sensitive actions
- `.foreground` option brings app to foreground for check-in action
- Snooze actions run with `.foreground` to trigger reschedule

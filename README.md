# CommuteWidget

CommuteWidget is a personal Android widget application for Samsung Galaxy S24 Ultra and One UI devices that displays real-time commute and schedule information on the home screen.
It fetches live traffic duration, distance, and congestion-colored static route maps from Google Maps Platform.
The application is built and installed entirely via the command line without requiring Android Studio or the Google Play Store.

## Features

- Dynamic routing based on active commute windows, calendar mode outside windows, and active favourite overrides.
- Live traffic duration, distance, and congestion-colored route polyline map display.
- Multiple widget sizes including 2x2 compact ETA, 4x2 split card with vertical favourite chips, and 4x4 expanded map layout with automatic size snapping.
- Theme-aware surfaces following system light and dark modes with Material dynamic colors.
- Sub-second tap responsiveness with instant "Updating..." visual feedback and two-minute location caching.
- Favourite destination chips with temporary routing overrides and one-tap restore.
- Leave-by advisor displaying dynamic departure targets and high-priority departure notifications inside active commute windows.
- Automatic calendar mode outside commute windows displaying the next remaining event today with route map or quiet card.
- Background commute history tracking with time-of-day traffic curves and weekday statistics in local SQLite storage.
- Interactive tap zones for data refresh, favourite switching, and direct Google Maps turn-by-turn navigation.
- Scheduled automatic background updates at window boundaries and 10-minute commute sampling slots powered by Android WorkManager across device reboots.
- Graceful offline and failure state caching with relative timestamp tracking and automatic 60-second recovery.
- In-app configuration for addresses, favourites, commute windows, leave-by targets, geocoding selection, transit modes, and calendar selection.

## Google Cloud API Key Setup

Google Cloud billing is required to use Google Maps Platform APIs, though personal usage remains within free monthly tiers.
Treat your API key as sensitive credentials and do not share it.

1. Navigate to `https://console.cloud.google.com` in your browser.
2. Select your Google Cloud project from the top project dropdown.
3. Open the navigation menu and go to **APIs & Services** > **Library**.
4. Search for and enable each of the following three APIs:
   - **Routes API**
   - **Maps Static API**
   - **Geocoding API**
5. Go to **APIs & Services** > **Credentials**.
6. Click **Create Credentials** and select **API key**.
7. In the key creation dialog or key edit page, locate **API restrictions** and choose **Restrict key**.
8. Select **Geocoding API**, **Maps Static API**, and **Routes API** from the API dropdown list.
9. Leave **Application restrictions** set to **None** for personal development use.
10. Save the changes and copy your generated API key.

### API Quota and Monthly Usage

The application is designed to operate comfortably within Google Maps Platform free tier allowances.
The default morning and evening commute collection slots add roughly 900 Routes API calls per month (2 slots x 3 hours x ~6 fetches per hour x 5 weekdays per week x ~4.3 weeks).
Total Routes API usage remains under 1,000 requests per month, well within the 5,000 free monthly Routes Pro calls.
Slot sampling requests fetch duration and routing polyline data without requesting static map images, keeping Maps Static API usage essentially unchanged.
Window boundary auto-refreshes replace fixed daily refreshes, adding roughly 4 background calls per enabled day.
Calendar mode refreshes for located events use one Routes API call and one Maps Static API call, matching manual tap refreshes.
Geocoding API requests occur only when searching and saving addresses in settings.

## Build from Source

Build the debug APK using the local SDK environment.

```bash
cd /Users/crpakala/Documents/AI/CommuteWidget && source ./env.sh && ./gradlew assembleDebug
```

The compiled package lands at:

```
app/build/outputs/apk/debug/app-debug.apk
```

## Install on the Phone

Enable developer options and USB debugging on your Samsung Galaxy S24 Ultra before installing via ADB.

1. Open **Settings** > **About phone** > **Software information**.
2. Tap **Build number** 7 times until developer mode is unlocked.
3. Go back to **Settings** > **Developer options**.
4. Enable **USB debugging**.
5. Connect the phone to your computer with a USB cable and accept the RSA prompt on the phone screen.
6. Install the debug APK from your terminal:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Permissions

CommuteWidget requests runtime permissions based on configured features:

- **Location (`ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`)**: Required for determining current device origin for calendar event routes, favourite destinations, and map refreshes.
  Select **Allow all the time** in system settings so background slot updates can fetch accurate location data.
- **Notifications (`POST_NOTIFICATIONS`)**: Required if the Leave-By Advisor notification feature is enabled.
  The app prompts for this permission when turning on the advisor toggle in settings.
- **Calendar (`READ_CALENDAR`)**: Required if the Calendar feature is enabled.
  The app prompts for this permission when turning on calendar integration in settings.

## In-App Setup

Launch the CommuteWidget app on your device to configure initial settings before adding the widget.

1. Paste your Google Cloud API key into the API key field.
2. Enter your home address, tap search to geocode, and pick the matching result.
3. Enter your work address, tap search to geocode, and pick the matching result.
4. Select your preferred travel mode (Car or Two-Wheeler).
5. Configure **Commute windows** by selecting active days of the week and setting time intervals for the To Work window (default 7:00 AM - 10:00 AM) and To Home window (default 5:00 PM - 8:00 PM).
6. Configure favourite destinations by adding up to 4 saved locations with custom labels and geocoded addresses.
7. Configure the Leave-By Advisor by setting target arrival times for work (default 9:30 AM) and home (default 7:30 PM).
   Note that the advisor applies inside your To Work and To Home windows.
8. Enable calendar integration if desired, and choose which device calendars to scan for event locations.
9. Grant runtime permissions as needed for location, notifications, and calendar access.

## Adding and Resizing the Widget on One UI

1. Long-press any empty area on the One UI home screen.
2. Tap **Widgets**.
3. Locate and tap **CommuteWidget**.
4. Touch and hold the widget preview, then drag it onto your home screen.
5. Use the resize handles to set your preferred size:
   - **2x2**: Compact view showing a large ETA, leave-by status, and quick refresh controls without a map or favourite chips.
   - **4x2**: Split layout with commute or calendar info, leave-by indicator, a vertical column of up to 4 slim favourite chips between the info panel and the map, and a congestion map.
   - **4x4**: Full-size map view with commute or calendar information, leave-by indicator, and all saved favourite chips (up to 4).
   Intermediate dimensions automatically snap to the nearest standard layout.
   All chip, leave-by, and overlay surfaces adapt to the system light and dark theme using Material dynamic colors.

## Daily Behavior

### Commute Windows and Routing Priority

The widget evaluates multiple potential destinations and chooses the active route based on a strict priority order:

1. **Active Favourite Destination**: Highest priority.
   When a favourite chip is tapped, the widget routes from current location to that favourite for the configured override window (default 60 minutes).
2. **Commute Windows**: Pure commute mode.
   - **To Work window** (default 7:00 AM - 10:00 AM on selected days): Displays route from Home to Work, computes leave-by status, and collects history samples every 10 minutes.
   - **To Home window** (default 5:00 PM - 8:00 PM on selected days): Displays route from Work to Home, computes leave-by status, and collects history samples every 10 minutes.
   - Inside windows, widget refreshes are pure commute updates and do not check calendar events.
3. **Calendar Mode**: Default outside commute windows.
   - Active outside configured windows during midday, evenings, weekends, and unselected days.
   - Displays the next remaining event scheduled for today from selected device calendars.
   - If an event has a location, the widget renders a route map from current device location with event title, start time, and ETA.
   - Tapping the map opens Google Maps turn-by-turn navigation to the event location.
   - If an event lacks a location, the widget displays a quiet card showing the title and scheduled time.
   - If no events remain today, the widget displays a card indicating the next upcoming commute window (for example, "Next: To Work at 7:00 am").

### Favourite Destinations

- Manage up to 4 favourite destinations in app settings with custom labels and geocoded addresses.
- Favourites appear as a vertical column of up to 4 slim chips between the info panel and the map on the 4x2 widget, and as interactive chips on the 4x4 widget.
- Chips do not appear on the compact 2x2 widget.
- Tapping an inactive favourite chip routes from the current device location to that destination for a configurable duration (default 60 minutes).
- Tapping the currently highlighted active favourite chip immediately clears the override and reverts to the active commute window or calendar mode.
- The override automatically expires after the configured window elapses.
- Favourite chips can be completely hidden from widgets via a toggle in app settings.
- Chip surfaces follow system light and dark themes with Material dynamic colors.

### Leave-By Advisor

- The Leave-By Advisor calculates dynamic departure times by subtracting the current live travel ETA from your target arrival time.
- The advisor applies inside your To Work and To Home windows.
- Configured in settings with separate target arrival times for arriving at work (default 9:30 AM) and arriving home (default 7:30 PM).
- Displays a formatted leave-by indicator on the widget (for example, "Leave by 8:42 am"), which turns red once the calculated departure moment has passed.
- Sends a high-priority system notification once per direction per day when the departure moment arrives.
- The notification requires `POST_NOTIFICATIONS` permission, requested when enabling the feature.
- If notification permission is denied, the leave-by status indicator still functions on the widget.
- The feature is disabled by default and can be toggled in settings.

### Calendar Mode

- Outside commute windows (evenings, midday, weekends, and unselected days), the widget switches to calendar mode to display upcoming schedule context.
- The feature requires the `READ_CALENDAR` permission.
- After granting permission, select which synced calendars CommuteWidget should monitor.
- The widget scans selected calendars for the next remaining non-all-day event scheduled for today.
- For an event with a location, the widget renders a route map from current location to the event with event title, start time, and ETA.
- Tapping the map image for a located event opens Google Maps turn-by-turn navigation directly to the event address.
- For an event without a location, the widget renders a quiet title-and-time card.
- If no events remain today, the widget displays the next upcoming commute window (for example, "Next: To Work at 7:00 am").
- Inside active commute windows, calendar mode is inactive and the widget displays pure commute routing.

### Commute History and Statistics

- Enabled by default to build historical commute profiles over time.
- Samples commute duration every 10 minutes during active To Work and To Home windows on selected days of the week.
- Slot sampling queries only the Routes API for travel duration without requesting static map images, conserving API quota and bandwidth.
- Slot samples automatically update the ETA displayed on the home screen widget.
- All sample records are stored locally on the device in an SQLite database.
- A "View commute stats" screen in app settings presents average commute duration by weekday, a time-of-day traffic curve per direction, and a highlighted best departure window.
- Data management controls in settings allow viewing total sample count, deleting samples for a specific date, or clearing all recorded history.
- Collection timing may experience minor jitter of a few minutes under Android battery optimization.

### Tap Interactions and Responsiveness

- Tapping a favourite chip or the refresh area immediately displays "Updating..." on the widget for instant visual feedback.
- Refresh requests use the device last known location when it is under 2 minutes old instead of waiting for a fresh GPS lock, completing most tap refreshes in a couple of seconds.
- The "Updating..." state automatically self-clears after 60 seconds if a network request stalls.
- **4x2 and 4x4 sizes**:
  - Tapping the ETA or info area triggers an immediate data refresh.
  - Tapping a favourite chip activates or deactivates that favourite destination override.
  - Tapping the map image opens Google Maps with turn-by-turn navigation to the active destination or located calendar event.
- **2x2 size**:
  - Tapping anywhere on the card area triggers an immediate data refresh.
  - Tapping the map-pin glyph launches Google Maps turn-by-turn navigation.

### Background Updates and WorkManager

- Scheduled auto-refreshes run automatically at the start and end of every configured To Work and To Home window.
- Commute history collection executes periodic background fetches every 10 minutes during active windows on selected days.
- Background jobs are orchestrated through Android WorkManager, ensuring resilience across device reboots and low-memory conditions.
- WorkManager background execution timing can shift by a few minutes depending on Android Doze mode and Samsung battery management.
- Background updates during commute windows refresh standard commute routes, while background updates outside windows refresh calendar mode.

### Failure Behavior

- On fetch failure, the widget keeps showing the last good data.
- The widget displays an updated relative time line and a warning glyph.
- Tapping the widget initiates a manual retry.
- The "Updating..." indicator self-clears after 60 seconds to prevent frozen progress states.

## Troubleshooting

| Issue | Cause | Solution |
| --- | --- | --- |
| Widget says "Open to set up" | Application setup is incomplete. | Open the CommuteWidget app, enter your API key, and save addresses. |
| "API key invalid" | Required APIs are not enabled or key restrictions are misconfigured. | Verify Routes API, Maps Static API, and Geocoding API are enabled and included in key restrictions. |
| "No route found" | Destination or origin address cannot be routed. | Check saved home, work, favourite, or calendar event locations in app settings. |
| Stale data with warning glyph | Network request failed or timed out. | Tap the widget to retry, and verify internet connectivity. |
| Widget stuck on "Updating..." for over a minute | Network stalled or background worker encountered a transient delay. | Tap the widget again to retry; the state self-clears automatically after 60 seconds. |
| Widget shows calendar or next-window card during commute time | Current day is not selected or current time is outside configured windows. | Check in settings that the current day of the week is enabled and the time falls inside the To Work or To Home window. |
| No calendar event shown outside windows | Calendar feature disabled, permission missing, calendars unselected, or event is all-day or not today. | Verify calendar integration is enabled, grant calendar permission, select synced calendars in settings, and ensure the event is scheduled for today and is not an all-day event. |
| Leave-by not appearing or sending notifications | Current time is outside active commute windows or notification permission is denied. | Leave-by only applies inside To Work and To Home windows; verify notification permissions in system settings and ensure arrival targets are configured. |
| Favourite chips missing from widget | Chip display toggle is disabled or widget size is 2x2. | Enable "Show favourite chips" in settings, and note that chips are not displayed on the 2x2 compact size. |
| Weekend or favourite shows wrong origin | Background location permission is missing or restricted. | Grant "Allow all the time" location permission in system app settings. |
| Commute stats are empty | Commute history is disabled, windows or days are unconfigured, or no windows have passed. | Verify history tracking is enabled in settings, ensure active days and windows are configured, and wait for a collection window to pass. |

## Testing

Unit tests run with `./gradlew test`.

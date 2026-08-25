# CommuteWidget

CommuteWidget is a personal Android widget application for Samsung Galaxy S24 Ultra and One UI devices that displays real-time commute information on the home screen.
It fetches live traffic duration, distance, and congestion-colored static route maps from Google Maps Platform.
The application is built and installed entirely via the command line without requiring Android Studio or the Google Play Store.

## Features

- Dynamic route selection based on weekday schedule, weekend location, active favourites, and calendar events.
- Live traffic duration, distance, and congestion-colored route polyline map display.
- Multiple widget sizes including 2x2 compact ETA, 4x2 split card, and 4x4 expanded map layout with automatic size snapping.
- Favourite destination chips with temporary routing overrides and one-tap restore.
- Leave-by advisor displaying dynamic departure targets and high-priority departure notifications.
- Calendar destination detection on manual refresh for upcoming scheduled events.
- Background commute history tracking with time-of-day traffic curves and weekday statistics in local SQLite storage.
- Interactive tap zones for instantaneous data refresh, favourite switching, and direct Google Maps turn-by-turn navigation.
- Scheduled automatic background updates and commute sampling slots powered by Android WorkManager across device reboots.
- Graceful offline and failure state caching with relative timestamp tracking and manual retry.
- In-app configuration for addresses, favourites, calendar lookahead, leave-by targets, geocoding selection, transit modes, and schedule timers.

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

- **Location (`ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`)**: Required for determining current device origin for weekend routing, favourite destinations, and calendar event routes.
  Select **Allow all the time** in system settings so background slot updates can fetch accurate location data.
- **Notifications (`POST_NOTIFICATIONS`)**: Required if the Leave-By Advisor notification feature is enabled.
  The app prompts for this permission when turning on the advisor toggle in settings.
- **Calendar (`READ_CALENDAR`)**: Required if the Calendar Destinations feature is enabled.
  The app prompts for this permission when turning on calendar integration in settings.

## In-App Setup

Launch the CommuteWidget app on your device to configure initial settings before adding the widget.

1. Paste your Google Cloud API key into the API key field.
2. Enter your home address, tap search to geocode, and pick the matching result.
3. Enter your work address, tap search to geocode, and pick the matching result.
4. Select your preferred travel mode (Car or Two-Wheeler).
5. Configure the daily switch time (default is 2:00 PM).
6. Configure the morning and evening auto-refresh times (defaults are 8:00 AM and 5:00 PM).
7. Configure favourite destinations by adding up to 4 saved locations with custom labels and geocoded addresses.
8. Configure the Leave-By Advisor by setting target arrival times for work (default 9:30 AM) and home (default 7:30 PM).
9. Enable calendar integration if desired, and choose which device calendars to scan for event locations.
10. Configure commute history sampling slots (defaults are 7:00 AM - 10:00 AM and 5:00 PM - 8:00 PM on Monday through Friday).
11. Grant runtime permissions as needed for location, notifications, and calendar access.

## Adding and Resizing the Widget on One UI

1. Long-press any empty area on the One UI home screen.
2. Tap **Widgets**.
3. Locate and tap **CommuteWidget**.
4. Touch and hold the widget preview, then drag it onto your home screen.
5. Use the resize handles to set your preferred size:
   - **2x2**: Compact view showing a large ETA, leave-by status, and quick refresh controls without a map or favourite chips.
   - **4x2**: Split layout with commute info, leave-by indicator, up to 2 favourite chips, and a congestion map.
   - **4x4**: Full-size map view with commute information, leave-by indicator, and all saved favourite chips (up to 4).
   Intermediate dimensions automatically snap to the nearest standard layout.

## Daily Behavior

### Routing Direction and Priority

The widget evaluates multiple potential destinations and chooses the active route based on a strict priority order:

1. **Active Favourite Destination**: Highest priority.
   When a favourite chip is tapped, the widget routes from current location to that favourite for the configured override window (default 60 minutes).
2. **Calendar Event Destination**: Second priority.
   When calendar integration is enabled and a manual tap refresh occurs, the widget routes to the next calendar event with a location starting within the lookahead window (1-4 hours, default 3 hours).
3. **Normal Home and Work Schedule**: Default fallback.
   - **Weekday mornings** (before switch time, default 2:00 PM): Displays route from Home to Work.
   - **Weekday evenings** (after switch time, default 2:00 PM): Displays route from Work to Home.
   - **Weekends**: Displays route from current device location to Home.

### Favourite Destinations

- Manage up to 4 favourite destinations in app settings with custom labels and geocoded addresses.
- Favourites appear as interactive chips on the 4x2 widget (up to 2 chips) and 4x4 widget (up to 4 chips).
- Chips do not appear on the compact 2x2 widget.
- Tapping an inactive favourite chip routes from the current device location to that destination for a configurable duration (default 60 minutes).
- Tapping the currently highlighted active favourite chip immediately clears the override and reverts to normal schedule routing.
- The override automatically expires after the configured window elapses.
- Favourite chips can be completely hidden from widgets via a toggle in app settings.

### Leave-By Advisor

- The Leave-By Advisor calculates dynamic departure times by subtracting the current live travel ETA from your target arrival time.
- Configured in settings with separate target arrival times for arriving at work (default 9:30 AM) and arriving home (default 7:30 PM).
- Displays a formatted leave-by indicator on the widget (for example, "Leave by 8:42 am"), which turns red once the calculated departure moment has passed.
- Sends a high-priority system notification once per direction per day when the departure moment arrives.
- The notification requires `POST_NOTIFICATIONS` permission, requested when enabling the feature.
- If notification permission is denied, the leave-by status indicator still functions on the widget.
- The feature is disabled by default and can be toggled in settings.

### Calendar Destinations

- Integrates with local device calendars to automatically route to upcoming appointments.
- Feature is disabled by default and requires the `READ_CALENDAR` permission.
- After granting permission, select which synced calendars CommuteWidget should monitor.
- When you perform a manual tap refresh, the app scans selected calendars for the next event containing a location starting within a configurable lookahead window (1-4 hours, default 3 hours).
- If a matching event is found, the widget routes from current location to the event location and displays the event title on the widget.
- Automatic background refreshes and collection slot fetches never query the calendar to protect battery and prevent unexpected destination changes.
- Manual refresh with an active favourite override takes precedence over calendar events.

### Commute History and Statistics

- Enabled by default to build historical commute profiles over time.
- Samples commute duration every ~10 minutes during configurable morning and evening collection slots (defaults 7:00 AM - 10:00 AM and 5:00 PM - 8:00 PM) on selected weekdays (default Monday through Friday).
- Slot sampling queries only the Routes API for travel duration without requesting static map images, conserving API quota and bandwidth.
- Slot samples automatically update the ETA displayed on the home screen widget.
- All sample records are stored locally on the device in an SQLite database.
- A "View commute stats" screen in app settings presents average commute duration by weekday, a time-of-day traffic curve per direction, and a highlighted best departure window.
- Data management controls in settings allow viewing total sample count, deleting samples for a specific date, or clearing all recorded history.
- Collection timing may experience minor jitter of a few minutes under Android battery optimization.

### Tap Interactions

- **4x2 and 4x4 sizes**:
  - Tapping the ETA or info area triggers an immediate data refresh, which also checks calendar events if enabled.
  - Tapping a favourite chip activates or deactivates that favourite destination override.
  - Tapping the map image opens Google Maps with turn-by-turn navigation to the active destination.
- **2x2 size**:
  - Tapping anywhere on the card area triggers an immediate data refresh.
  - Tapping the map-pin glyph launches Google Maps turn-by-turn navigation.

### Background Updates and Slots

- Scheduled auto-refreshes run on weekdays at configured morning and evening times (defaults 8:00 AM and 5:00 PM).
- Commute history collection slots execute periodic background fetches every ~10 minutes during active morning and evening windows on selected weekdays.
- Background jobs are orchestrated through Android WorkManager, ensuring resilience across device reboots and low-memory conditions.
- WorkManager background execution timing can shift by a few minutes depending on Android Doze mode and Samsung battery management.
- Scheduled automatic background updates update the standard home and work commute route and do not trigger calendar event lookups.

### Failure Behavior

- On fetch failure, the widget keeps showing the last good data.
- The widget displays an updated relative time line and a warning glyph.
- Tapping the widget initiates a manual retry.

## Troubleshooting

| Issue | Cause | Solution |
| --- | --- | --- |
| Widget says "Open to set up" | Application setup is incomplete. | Open the CommuteWidget app, enter your API key, and save addresses. |
| "API key invalid" | Required APIs are not enabled or key restrictions are misconfigured. | Verify Routes API, Maps Static API, and Geocoding API are enabled and included in key restrictions. |
| "No route found" | Destination or origin address cannot be routed. | Check saved home, work, favourite, or calendar event locations in app settings. |
| Stale data with warning glyph | Network request failed or timed out. | Tap the widget to retry, and verify internet connectivity. |
| Weekend or favourite shows wrong origin | Background location permission is missing or restricted. | Grant "Allow all the time" location permission in system app settings. |
| Favourite chips missing from widget | Chip display toggle is disabled or widget size is 2x2. | Enable "Show favourite chips" in settings, and note that chips are not displayed on the 2x2 compact size. |
| Leave-by not sending notifications | Notification permission is denied or departure time falls outside active slots. | Enable notification permission in Android settings, and ensure the leave-by moment falls inside an active collection slot or refresh window. |
| Calendar event not overriding destination | Calendar permission missing, calendars unselected, event lacks location, or update was automatic. | Grant calendar permission, select calendars in settings, ensure the event has a valid location, and tap the widget manually to refresh. |
| Commute stats are empty | Commute history is disabled, slots or days are unconfigured, or no slots have passed. | Verify history tracking is enabled in settings, ensure active days and slots are configured, and wait for a collection slot to pass. |

## Testing

Unit tests run with `./gradlew test`.

# CommuteWidget

CommuteWidget is a personal Android widget application for Samsung Galaxy S24 Ultra and One UI devices that displays real-time commute and schedule information on the home screen.
It fetches live traffic duration and congestion-colored static route maps from Google Maps Platform.
The application is built and installed entirely via the command line without requiring Android Studio or the Google Play Store.
The v5 release redesigns the widget for glanceability, removes obsolete background history sampling and widget chips, and simplifies the visual hierarchy.
The audit and design rulings driving these changes are documented in `UX-AUDIT.md`.

## Features

- Glanceable widget layout showing exactly three elements in commute and routed-event modes: the destination, one large traffic-colored ETA, and the leave-by time (rendered in red when past).
- Clean congestion-colored static route polyline map display without overlay pills or chrome.
- Multiple widget sizes (2x2 compact card, 4x2 split card, and 4x4 expanded map layout) with automatic size snapping.
- Theme-aware surfaces following system light and dark modes with Material dynamic colors.
- Non-text status signals: semi-transparent ETA during in-flight refreshes and cooldown taps, grey ETA for data older than 10 minutes, and error warning glyphs without loading text.
- Clean calendar-empty fallback cards without map areas: large title and start time for unlocated events, "Next up - To Work at 7:00 am" when no events remain, and quiet message cards when no schedule exists.
- Small "Routed" caption when the widget prioritizes a located calendar event over a chronologically earlier unlocated event.
- Wind-down card displaying tomorrow's first calendar event and next device alarm when no events remain today.
- Contextual countdown captions showing time until routed event start or available free time before unlocated events.
- Morning brief caption during the morning commute view summarizing total meetings and first event start time.
- Next scheduled system alarm readout on the bare no-events card.
- Saved places management in settings with direct Google Maps navigation launch buttons.
- Commute and calendar event leave-by advisor computing dynamic departure targets with early arrival buffers and firing precise local high-priority alarm notifications.
- Automatic calendar mode outside commute windows displaying the next remaining event today using live or predicted traffic.
- Automatic 20-minute event ETA background refresh loop while displaying a routed calendar event outside commute windows.
- Low-latency tap refreshes with location cache warming during window boundaries and elimination of redundant background pre-fetch renders.
- Interactive tap zones: tapping the map launches Google Maps turn-by-turn navigation, while tapping the info area triggers an immediate data refresh.
- Scheduled automatic background updates at window boundaries (4 calls per day) powered by Android WorkManager across device reboots.
- Graceful offline and failure state caching with relative error tracking and automatic 60-second recovery.
- In-app configuration for addresses, saved places, commute days, commute windows, leave-by targets, early arrival buffers, live traffic thresholds, geocoding selection, transit modes, and calendar selection.

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

The application operates comfortably within Google Maps Platform free tier allowances.
Background call volume dropped substantially in v5 with the complete removal of the 36-38 daily in-window history sampling calls.
Remaining background calls consist of window-boundary refreshes (4 background calls per enabled day) and the 20-minute event freshness tick only while a routed calendar event is displayed.
Manual tap refreshes and routed calendar mode refreshes execute one Routes API request and one Maps Static API request.
Event leave-by traffic predictions ride on the same single Routes API request by supplying a future departure timestamp when outside the live traffic threshold.
Local leave-by notifications are scheduled entirely on-device via precise alarms and consume zero extra API calls.
Geocoding API requests occur only when searching and saving addresses in settings.
Total Routes API usage remains well below the 5,000 free monthly Routes Pro calls allowance.

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

- **Location (`ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`)**: Required for determining current device origin for calendar event routes, saved places navigation, and map refreshes.
  Select **Allow all the time** in system settings so background boundary and event updates can fetch accurate location data.
- **Notifications (`POST_NOTIFICATIONS`)**: Required if the Leave-By Advisor notification feature is enabled for commute windows or calendar events.
  The app prompts for this permission when turning on the advisor toggle in settings.
- **Calendar (`READ_CALENDAR`)**: Required if the Calendar feature is enabled.
  The app prompts for this permission when turning on calendar integration in settings.
- **System Alarm**: Reading the next scheduled device alarm from the system alarm clock operates on-device and requires no permissions.

## In-App Setup

Launch the CommuteWidget app on your device to configure initial settings before adding the widget.

1. Paste your Google Cloud API key into the API key field.
2. Enter your home address, tap search to geocode, and pick the matching result.
3. Enter your work address, tap search to geocode, and pick the matching result.
4. Select your preferred travel mode (Car or Two-Wheeler).
5. Configure **Commute days** by selecting the days of the week that activate commute windows.
   Unselected days run in calendar mode all day.
6. Configure **Commute windows** by setting time intervals for the To Work window (default 7:00 AM - 10:00 AM) and To Home window (default 5:00 PM - 8:00 PM).
7. Configure **Saved places** by adding up to 4 locations with custom labels and geocoded addresses.
   Each saved place entry provides a Navigate button that opens Google Maps directly.
8. Configure the Leave-By Advisor by enabling the toggle and setting target arrival times for work (default 9:30 AM) and home (default 7:30 PM).
   Set the "Arrive early by" buffer (0-60 minutes, default 10 minutes) and "Use live traffic within" threshold (15-180 minutes, default 60 minutes).
   The advisor applies to active commute windows as well as calendar events with a location.
9. Enable calendar integration if desired, choose which device calendars to scan for event locations, and optionally toggle "Keep event ETA fresh" (enabled by default).
10. Grant runtime permissions as needed for location, notifications, and calendar access.

## Adding and Resizing the Widget on One UI

1. Long-press any empty area on the One UI home screen.
2. Tap **Widgets**.
3. Locate and tap **CommuteWidget**.
4. Touch and hold the widget preview, then drag it onto your home screen.
5. Use the resize handles to set your preferred size:
   - **2x2**: Compact card showing destination, large traffic-colored ETA, and leave-by status without a map.
   - **4x2**: Split layout showing destination, large traffic-colored ETA, leave-by indicator, and clean congestion map.
   - **4x4**: Full-size view showing destination, large traffic-colored ETA, leave-by indicator, and expanded congestion map.
   Intermediate dimensions automatically snap to the nearest standard layout.
   Every size in commute and routed-event modes displays exactly three elements: destination, large traffic-colored ETA, and leave-by time (red when past).
   The widget does not display favourite chips, distance lines, traffic bars, "updated X ago" captions, or map-overlay pills.
   The map image is clean and unobstructed.
   All widget surfaces adapt to system light and dark themes using Material dynamic colors.

## Daily Behavior

### Commute Windows and Routing Priority

The widget selects its active display mode according to configured schedules and calendar state:

1. **Commute Windows**: Active during configured To Work and To Home hours on enabled Commute days.
   - **To Work window** (default 7:00 AM - 10:00 AM): Displays route from Home to Work with large traffic-colored ETA and leave-by departure time.
   - During the morning commute view, a morning brief caption summarizes the day (for example, "3 meetings - first 10:00 am") using selected calendar events.
   - **To Home window** (default 5:00 PM - 8:00 PM): Displays route from Work to Home with large traffic-colored ETA and leave-by departure time.
   - Inside windows, widget refreshes are pure commute updates and do not check calendar events for routing.
2. **Calendar Mode**: Default outside commute windows (midday, evenings, weekends, and unselected commute days).
   - Displays the next remaining non-all-day event scheduled for today from selected device calendars.
   - If an event has a location, the widget renders a clean route map from current device location with destination, start time, large traffic-colored ETA, and calculated leave-by departure time.
   - A routed calendar event displays a countdown caption until its start (for example, "in 1h 45m").
   - Tapping the map opens Google Maps turn-by-turn navigation to the event location.
   - If no routed event exists, the widget displays a single full-width card without a map area.
   - An unlocated event displays a card showing its event title, start time, and a free-time countdown caption (for example, "Free for 2h 10m").
   - When no events remain today, the wind-down card displays "Next up - To Work at 7:00 am" (or the next upcoming window), tomorrow's first calendar event (for example, "Standup at 9:00 am"), and the next scheduled device alarm (for example, "Alarm 6:45 am").
   - When no schedule exists, the bare no-events card displays a quiet empty message along with the next scheduled device alarm line.
   - A small "Routed" caption appears when the widget prioritizes a located event over a chronologically earlier unlocated event.

### Non-Text Status Signals and Responsiveness

- The widget uses non-text visual signals rather than text loading labels or standing timestamp lines.
- While a tap-triggered refresh is in flight, the ETA renders semi-transparent.
- When data is older than 10 minutes, the ETA turns grey to indicate staleness without taking up layout space.
- A tap during the 5-second cooldown flashes the semi-transparent state briefly to confirm that the tap visibly registered.
- On fetch failure, the widget displays a warning glyph while retaining the last valid data.
- Background refreshes skip the redundant pre-fetch render, reducing latency and resource usage.
- Window-boundary refreshes warm the device location cache, allowing subsequent manual taps to skip the slow GPS lock path.
- **4x2 and 4x4 sizes**:
  - Tapping the ETA or info area triggers an immediate data refresh.
  - Tapping the clean map image opens Google Maps turn-by-turn navigation to the active destination or located calendar event.
- **2x2 size**:
  - Tapping the info area triggers an immediate data refresh.
  - Tapping the destination or glyph opens Google Maps turn-by-turn navigation.

### Leave-By Advisor and Departure Notifications

- The Leave-By Advisor calculates dynamic departure times for commute windows and located calendar events.
- In commute windows, departure times are computed by subtracting current live travel ETA from configured target arrival times (work default 9:30 AM, home default 7:30 PM).
- In calendar mode, when displaying an event with a location, the advisor computes and shows a departure target (for example, "Leave by 2:40 pm") to arrive early by the configured buffer.
- The leave-by time renders across all widget sizes in commute and routed-event modes.
- The indicator turns red once the calculated departure moment has passed.
- Settings provide an "Arrive early by" buffer slider (0-60 minutes, default 10 minutes) and a "Use live traffic within" threshold slider (15-180 minutes, default 60 minutes).
- If the event starts within the live traffic threshold (default 60 minutes), the route request queries real-time traffic conditions.
- If the event starts further out than the threshold, the route request includes a future departure timestamp so Google Routes API returns predicted traffic for that time of day.
- Commute leave-by notifications fire from dedicated, precise device alarms scheduled independently of background sampling workers.
- Event leave-by notifications fire at the calculated departure time for located events (for example, "Leave by 2:40 pm for Client meeting - 35 min drive").
- If an event is deleted or removed from the calendar, its pending notification is automatically cancelled on the next refresh.
- Notifications require `POST_NOTIFICATIONS` permission and the "Leave-by advisor" toggle in settings.

### Calendar Freshness and Background Updates

- Scheduled window boundary auto-refreshes run automatically at the start and end of every configured To Work and To Home window (4 background runs per day).
- While the widget displays a routed calendar event outside commute windows, it refreshes the event ETA every 20 minutes.
- The 20-minute event refresh loop is controlled by the "Keep event ETA fresh" toggle in settings (enabled by default).
- Background jobs are orchestrated through Android WorkManager, ensuring resilience across device reboots.
- In-window 10-minute history sampling, stats tracking, and local database storage are completely removed.

### Saved Places

- Manage up to 4 saved locations in app settings with custom labels and geocoded addresses.
- Saved places replace on-widget favourite chips and timed overrides.
- Tapping the Navigate button beside any saved place in settings opens Google Maps turn-by-turn navigation directly to that destination.

## Troubleshooting

| Issue | Cause | Solution |
| --- | --- | --- |
| Widget says "Open to set up" | Application setup is incomplete. | Open the CommuteWidget app, enter your API key, and save addresses. |
| "API key invalid" | Required APIs are not enabled or key restrictions are misconfigured. | Verify Routes API, Maps Static API, and Geocoding API are enabled and included in key restrictions. |
| "No route found" | Destination or origin address cannot be routed. | Check saved home, work, saved places, or calendar event locations in app settings. |
| ETA is semi-transparent | Data refresh is currently in flight. | Wait a few seconds for the network request to complete. |
| ETA is grey | Displayed route data is older than 10 minutes. | Tap the ETA or info area to trigger an immediate data refresh. |
| Stale data with warning glyph | Network request failed or timed out. | Tap the widget to retry, and verify internet connectivity. |
| Widget shows calendar or next-up card during commute time | Current day is unchecked in Commute days or current time is outside configured windows. | Check in settings that the current day of the week is enabled and the time falls inside the window. |
| Unchecked day shows calendar mode all day | Commute days toggle disables commute windows for that day. | Enable the day in Commute days settings if commute mode is desired. |
| No calendar event shown outside windows | Calendar feature disabled, permission missing, calendars unselected, or event is all-day or not today. | Verify calendar integration is enabled, grant calendar permission, select synced calendars in settings, and ensure the event is scheduled for today and is not an all-day event. |
| Commute leave-by not appearing | Feature is disabled or current time is outside active commute windows. | Enable the Leave-by advisor toggle in settings, check active window schedules, and ensure arrival targets are set. |
| Event leave-by missing | Advisor toggle is disabled, calendar event lacks a location, or widget needs a refresh. | Enable the Leave-by advisor in settings, ensure the calendar event contains a routable address, and tap the widget to refresh. |
| Leave-by notification did not fire | Notification permission is missing or the Leave-by advisor toggle is disabled. | Grant notification permission in system settings and ensure the Leave-by advisor toggle is enabled. |
| Leave-by time seems off for far-away events | Far-away events use Google predicted traffic rather than live road conditions. | Predicted traffic is queried outside the live traffic threshold and automatically refines with real-time data on refreshes closer to event start. |
| Weekend shows wrong origin | Background location permission is missing or restricted. | Grant "Allow all the time" location permission in system app settings. |
| Tomorrow event line missing on wind-down card | Calendar feature is disabled or calendars are not selected in settings. | Verify calendar integration is enabled and relevant calendars are selected in app settings. |
| Alarm line missing on wind-down or empty card | No alarm is currently set on the device. | Set an upcoming alarm in the system clock application. |

## Testing

Unit tests run with `./gradlew test`.

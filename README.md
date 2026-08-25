# CommuteWidget

CommuteWidget is a personal Android widget application for Samsung Galaxy S24 Ultra and One UI devices that displays real-time commute information on the home screen.
It fetches live traffic duration, distance, and congestion-colored static route maps from Google Maps Platform.
The application is built and installed entirely via the command line without requiring Android Studio or the Google Play Store.

## Features

- Dynamic route selection based on weekday timing and weekend location.
- Live traffic duration, distance, and congestion-colored route polyline map display.
- Multiple widget sizes including 2x2 compact ETA, 4x2 split card, and 4x4 expanded map layout with automatic size snapping.
- Interactive tap zones for instantaneous data refresh and direct Google Maps turn-by-turn navigation.
- Scheduled automatic background updates powered by Android WorkManager across device reboots.
- Graceful offline and failure state caching with relative timestamp tracking and manual retry.
- In-app configuration for addresses, geocoding selection, transit modes, switch thresholds, and schedule timers.

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

## In-App Setup

Launch the CommuteWidget app on your device to configure initial settings before adding the widget.

1. Paste your Google Cloud API key into the API key field.
2. Enter your home address, tap search to geocode, and pick the matching result.
3. Enter your work address, tap search to geocode, and pick the matching result.
4. Select your preferred travel mode (Car or Two-Wheeler).
5. Configure the daily switch time (default is 2:00 PM).
6. Configure the morning and evening auto-refresh times (defaults are 8:00 AM and 5:00 PM).
7. Grant location permissions when prompted, selecting **Allow all the time** if you plan to use weekend routing from your current location.

## Adding and Resizing the Widget on One UI

1. Long-press any empty area on the One UI home screen.
2. Tap **Widgets**.
3. Locate and tap **CommuteWidget**.
4. Touch and hold the widget preview, then drag it onto your home screen.
5. Use the resize handles to set your preferred size:
   - **2x2**: Compact view showing a large ETA and quick refresh controls without a map.
   - **4x2**: Split layout with commute info on the left and a congestion map on the right.
   - **4x4**: Full-size map view with an overlaid commute information chip.
   Intermediate dimensions automatically snap to the nearest standard layout.

## Daily Behavior

### Direction Logic

The widget automatically switches commute direction based on day and time:
- **Weekday mornings** (before switch time, default 2:00 PM): Displays route from Home to Work.
- **Weekday evenings** (after switch time, default 2:00 PM): Displays route from Work to Home.
- **Weekends**: Displays route from current device location to Home.

### Tap Interactions

- **4x2 and 4x4 sizes**: Tapping the ETA or info area refreshes data, while tapping the map image opens Google Maps turn-by-turn navigation.
- **2x2 size**: Tapping anywhere refreshes data, while tapping the small map-pin glyph opens Google Maps navigation.

### Auto-Refresh

- Scheduled refreshes run on weekdays at 8:00 AM and 5:00 PM by default.
- Both schedule times are configurable inside the application settings.
- Refreshes do not run automatically on weekends.
- Background execution is managed by Android WorkManager, ensuring schedules survive device restarts.
- Timing can slip by a few minutes under Android battery Doze mode.

### Failure Behavior

- On fetch failure, the widget keeps showing the last good data.
- The widget displays an "updated X ago" line and a warning glyph.
- Tapping the widget initiates a manual retry.

## Troubleshooting

| Issue | Cause | Solution |
| --- | --- | --- |
| Widget says "Open to set up" | Application setup is incomplete. | Open the CommuteWidget app, enter your API key, and save addresses. |
| "API key invalid" | Required APIs are not enabled or key restrictions are misconfigured. | Verify Routes API, Maps Static API, and Geocoding API are enabled and included in key restrictions. |
| "No route found" | Destination or origin address cannot be routed. | Check saved home and work locations in app settings. |
| Stale data with warning glyph | Network request failed or timed out. | Tap the widget to retry, and verify internet connectivity. |
| Weekend shows wrong origin | Background location permission is missing. | Grant "Allow all the time" location permission in app settings. |

## Testing

Unit tests run with `./gradlew test`.

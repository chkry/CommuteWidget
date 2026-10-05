package com.crpakala.commutewidget.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.crpakala.commutewidget.api.ApiResult
import com.crpakala.commutewidget.api.GeocodeHit
import com.crpakala.commutewidget.api.GeocodingClient
import com.crpakala.commutewidget.data.AppSettings
import com.crpakala.commutewidget.data.Favourite
import com.crpakala.commutewidget.data.Place
import com.crpakala.commutewidget.data.SettingsRepository
import com.crpakala.commutewidget.data.TravelMode
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** "Places & Maps" category: the Google Maps API key and saved places (formerly "favourites"). */
@Composable
fun PlacesMapsScreen(
    settings: AppSettings,
    repository: SettingsRepository,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    applicationContext: Context,
    padding: PaddingValues,
) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            top = padding.calculateTopPadding() + 16.dp,
            end = 16.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ApiKeySection(
                savedKey = settings.apiKey,
                onSave = { apiKey ->
                    scope.launch {
                        repository.setApiKey(apiKey)
                        refreshWidget(applicationContext)
                        snackbarHostState.showSnackbar("API key saved")
                    }
                },
            )
        }
        item {
            FavouritesSection(
                favourites = settings.favourites,
                travelMode = settings.travelMode,
                apiKey = settings.apiKey,
                onSaveFavourites = { favourites ->
                    scope.launch {
                        repository.setFavourites(favourites)
                        refreshWidget(applicationContext)
                    }
                },
            )
        }
    }
}

@Composable
internal fun ApiKeySection(savedKey: String, onSave: (String) -> Unit) {
    var apiKey by remember(savedKey) { mutableStateOf(savedKey) }
    var visible by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("API key", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("Google Maps API key") },
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { visible = !visible }) {
                    Text(if (visible) "Hide" else "Show")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Google Maps Platform key with Routes, Static Maps and Geocoding enabled",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { onSave(apiKey.trim()) }) {
            Text("Save key")
        }
    }
}

@Composable
internal fun FavouritesSection(
    favourites: List<Favourite>,
    travelMode: TravelMode,
    apiKey: String,
    onSaveFavourites: (List<Favourite>) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Saved places", style = MaterialTheme.typography.titleMedium)
        Text(
            "Saved places for quick navigation from here.",
            style = MaterialTheme.typography.bodySmall,
        )
        favourites.forEach { favourite ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(favourite.label)
                    Text(favourite.place.address, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = {
                    launchNavigation(context, favourite.place, travelMode)
                }) { Text("Navigate") }
                TextButton(onClick = {
                    onSaveFavourites(favourites.filterNot { it.label == favourite.label })
                }) { Text("Remove") }
            }
        }
        if (favourites.size < 4) {
            TextButton(onClick = { adding = !adding }) {
                Text(if (adding) "Cancel adding place" else "Add saved place")
            }
        }
        if (adding) {
            AddFavouriteForm(
                apiKey = apiKey,
                existingLabels = favourites.map { it.label }.toSet(),
                onAdd = {
                    onSaveFavourites(favourites + it)
                    adding = false
                },
            )
        }
    }
}

@Composable
private fun AddFavouriteForm(
    apiKey: String,
    existingLabels: Set<String>,
    onAdd: (Favourite) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeocodeHit>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val normalized = label.trim()
    val labelError = when {
        normalized.isBlank() -> "Label is required"
        normalized.length > 12 -> "Label must be 12 characters or fewer"
        existingLabels.any { it.equals(normalized, ignoreCase = true) } -> "Label already exists"
        else -> null
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Short label") },
                isError = label.isNotBlank() && labelError != null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Address") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (apiKey.isBlank()) Text("Enter API key first", color = MaterialTheme.colorScheme.error)
            Button(
                enabled = apiKey.isNotBlank() && address.isNotBlank() && labelError == null,
                onClick = {
                    error = null
                    results = emptyList()
                    scope.launch {
                        when (val response = GeocodingClient(apiKey).geocode(address.trim())) {
                            is ApiResult.Success -> results = response.value.take(5)
                            is ApiResult.Failure -> error = response.message
                        }
                    }
                },
            ) { Text("Search") }
            labelError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            results.forEach { hit ->
                Text(
                    hit.formattedAddress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onAdd(Favourite(normalized, Place(hit.formattedAddress, hit.location.lat, hit.location.lng)))
                        }
                        .padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

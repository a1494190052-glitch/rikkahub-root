/**
 * Weather Plugin for RikkaHub
 * Uses Open-Meteo API (free, no API key required)
 * ES5 compatible syntax
 */

// Weather code mapping
var weatherCodes = {
    0: 'Clear sky',
    1: 'Mainly clear',
    2: 'Partly cloudy',
    3: 'Overcast',
    45: 'Fog',
    48: 'Depositing rime fog',
    51: 'Light drizzle',
    53: 'Moderate drizzle',
    55: 'Dense drizzle',
    56: 'Light freezing drizzle',
    57: 'Dense freezing drizzle',
    61: 'Slight rain',
    63: 'Moderate rain',
    65: 'Heavy rain',
    66: 'Light freezing rain',
    67: 'Heavy freezing rain',
    71: 'Slight snow fall',
    73: 'Moderate snow fall',
    75: 'Heavy snow fall',
    77: 'Snow grains',
    80: 'Slight rain showers',
    81: 'Moderate rain showers',
    82: 'Violent rain showers',
    85: 'Slight snow showers',
    86: 'Heavy snow showers',
    95: 'Thunderstorm',
    96: 'Thunderstorm with slight hail',
    99: 'Thunderstorm with heavy hail'
};

/**
 * Get current weather for a location
 */
exports.get_weather = function(params) {
    var lat = params.latitude;
    var lon = params.longitude;
    var unit = params.unit || config.default_unit || 'celsius';

    if (lat === undefined || lon === undefined) {
        return { error: 'latitude and longitude are required' };
    }

    var tempUnit = unit === 'fahrenheit' ? 'fahrenheit' : 'celsius';
    var windUnit = unit === 'fahrenheit' ? 'mph' : 'kmh';

    var url = 'https://api.open-meteo.com/v1/forecast' +
        '?latitude=' + lat +
        '&longitude=' + lon +
        '&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m' +
        '&temperature_unit=' + tempUnit +
        '&wind_speed_unit=' + windUnit +
        '&timezone=auto';

    console.log('Fetching weather for lat=' + lat + ', lon=' + lon);

    var response = fetch(url);

    if (!response.ok) {
        return { error: 'Weather API returned status ' + response.status };
    }

    var data = response.json();

    if (!data.current) {
        return { error: 'No weather data available for this location' };
    }

    var current = data.current;
    var weatherCode = current.weather_code;
    var condition = weatherCodes[weatherCode] || ('Unknown (code ' + weatherCode + ')');

    var result = {
        location: {
            latitude: lat,
            longitude: lon,
            timezone: data.timezone || 'unknown'
        },
        current: {
            temperature: current.temperature_2m,
            unit: tempUnit === 'fahrenheit' ? '\u00B0F' : '\u00B0C',
            feels_like: current.apparent_temperature,
            condition: condition,
            weather_code: weatherCode,
            humidity: current.relative_humidity_2m + '%',
            wind_speed: current.wind_speed_10m + (windUnit === 'mph' ? ' mph' : ' km/h')
        }
    };

    // Store last query in dataStore
    dataStore.set('last_lat', String(lat));
    dataStore.set('last_lon', String(lon));
    dataStore.set('last_query_time', new Date().toISOString());

    return result;
};

/**
 * Geocode a city name to coordinates
 */
exports.geocode = function(params) {
    var city = params.city;

    if (!city) {
        return { error: 'city parameter is required' };
    }

    var url = 'https://geocoding-api.open-meteo.com/v1/search' +
        '?name=' + encodeURIComponent(city) +
        '&count=3' +
        '&language=en' +
        '&format=json';

    console.log('Geocoding city: ' + city);

    var response = fetch(url);

    if (!response.ok) {
        return { error: 'Geocoding API returned status ' + response.status };
    }

    var data = response.json();

    if (!data.results || data.results.length === 0) {
        return { error: 'No results found for: ' + city };
    }

    var results = [];
    for (var i = 0; i < data.results.length; i++) {
        var r = data.results[i];
        results.push({
            name: r.name,
            country: r.country || '',
            admin1: r.admin1 || '',
            latitude: r.latitude,
            longitude: r.longitude,
            timezone: r.timezone || 'auto'
        });
    }

    return {
        query: city,
        results: results,
        hint: 'Use the latitude and longitude with get_weather tool'
    };
};

/**
 * Event hook: called when a message is sent
 */
exports.onMessageSent = function(params) {
    // Log message activity
    var count = dataStore.get('message_count');
    count = count ? parseInt(count) + 1 : 1;
    dataStore.set('message_count', String(count));
    console.log('Message sent event received. Total: ' + count);
    return { success: true };
};

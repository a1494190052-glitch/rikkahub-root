/**
 * Weather Plugin for RikkaHub
 * Uses Open-Meteo API (free, no API key required)
 * ES2020+ syntax with async/await
 */

// Weather code mapping
const weatherCodes = {
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
    99: 'Thunderstorm with heavy hail',
};

/**
 * Get current weather for a location
 */
exports.get_weather = async (params) => {
    const { latitude: lat, longitude: lon } = params;
    const unit = params.unit || config.default_unit || 'celsius';

    if (lat === undefined || lon === undefined) {
        return { error: 'latitude and longitude are required' };
    }

    const tempUnit = unit === 'fahrenheit' ? 'fahrenheit' : 'celsius';
    const windUnit = unit === 'fahrenheit' ? 'mph' : 'kmh';

    const url = `https://api.open-meteo.com/v1/forecast`
        + `?latitude=${lat}&longitude=${lon}`
        + `&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m`
        + `&temperature_unit=${tempUnit}&wind_speed_unit=${windUnit}&timezone=auto`;

    console.log(`Fetching weather for lat=${lat}, lon=${lon}`);

    const response = await fetch(url);

    if (!response.ok) {
        return { error: `Weather API returned status ${response.status}` };
    }

    const data = await response.json();

    if (!data.current) {
        return { error: 'No weather data available for this location' };
    }

    const current = data.current;
    const weatherCode = current.weather_code;
    const condition = weatherCodes[weatherCode] || `Unknown (code ${weatherCode})`;

    const result = {
        location: {
            latitude: lat,
            longitude: lon,
            timezone: data.timezone || 'unknown',
        },
        current: {
            temperature: current.temperature_2m,
            unit: tempUnit === 'fahrenheit' ? '\u00B0F' : '\u00B0C',
            feels_like: current.apparent_temperature,
            condition,
            weather_code: weatherCode,
            humidity: `${current.relative_humidity_2m}%`,
            wind_speed: `${current.wind_speed_10m}${windUnit === 'mph' ? ' mph' : ' km/h'}`,
        },
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
exports.geocode = async (params) => {
    const { city } = params;

    if (!city) {
        return { error: 'city parameter is required' };
    }

    const url = `https://geocoding-api.open-meteo.com/v1/search`
        + `?name=${encodeURIComponent(city)}&count=3&language=en&format=json`;

    console.log(`Geocoding city: ${city}`);

    const response = await fetch(url);

    if (!response.ok) {
        return { error: `Geocoding API returned status ${response.status}` };
    }

    const data = await response.json();

    if (!data.results || data.results.length === 0) {
        return { error: `No results found for: ${city}` };
    }

    const results = data.results.map((r) => ({
        name: r.name,
        country: r.country || '',
        admin1: r.admin1 || '',
        latitude: r.latitude,
        longitude: r.longitude,
        timezone: r.timezone || 'auto',
    }));

    return {
        query: city,
        results,
        hint: 'Use the latitude and longitude with get_weather tool',
    };
};

/**
 * Event hook: called when a message is sent
 */
exports.onMessageSent = (params) => {
    const count = parseInt(dataStore.get('message_count') || '0', 10) + 1;
    dataStore.set('message_count', String(count));
    console.log(`Message sent event received. Total: ${count}`);
    return { success: true };
};

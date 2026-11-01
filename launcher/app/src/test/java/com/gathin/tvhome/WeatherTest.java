package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WeatherTest {

    @Test
    public void wmoCodesMapToTheirGroup() {
        assertEquals(R.string.weather_clear, Weather.description(0));
        assertEquals(R.string.weather_partly_cloudy, Weather.description(2));
        assertEquals(R.string.weather_cloudy, Weather.description(3));
        assertEquals(R.string.weather_fog, Weather.description(48));
        assertEquals(R.string.weather_drizzle, Weather.description(55));
        assertEquals(R.string.weather_rain, Weather.description(63));
        assertEquals(R.string.weather_rain, Weather.description(81));
        assertEquals(R.string.weather_snow, Weather.description(75));
        assertEquals(R.string.weather_snow, Weather.description(86));
        assertEquals(R.string.weather_storm, Weather.description(95));
    }

    @Test
    public void clearSkyShowsSunByDayAndMoonByNight() {
        assertEquals(R.drawable.ic_weather_sun, Weather.icon(0, true));
        assertEquals(R.drawable.ic_weather_moon, Weather.icon(0, false));
        assertEquals(R.drawable.ic_weather_partly, Weather.icon(2, true));
        assertEquals(R.drawable.ic_weather_cloud, Weather.icon(2, false));
        assertEquals(Weather.icon(63, true), Weather.icon(63, false));
    }

    @Test
    public void urlCarriesTheThemesPlaceWithADecimalPoint() {
        String url = Weather.url(-15.7939, -47.8828);
        assertTrue(url, url.contains("latitude=-15.7939&longitude=-47.8828&"));
    }

    @Test
    public void metarConditionsMapToTheCardsCodes() {
        assertEquals(95, Weather.metarCode("TS", "OVC"));
        assertEquals(95, Weather.metarCode("-TSRA", "BKN"));
        assertEquals(61, Weather.metarCode("-SHRA", "BKN"));
        assertEquals(65, Weather.metarCode("+RA", "OVC"));
        assertEquals(45, Weather.metarCode("BR", "SCT"));
        assertEquals(3, Weather.metarCode("", "BKN"));
        assertEquals(2, Weather.metarCode("", "FEW"));
        assertEquals(0, Weather.metarCode("", "CAVOK"));
    }

    @Test
    public void freshMetarReplacesNowAndKeepsTheModelsRange() {
        Weather.Report model = new Weather.Report(21, 28, 20, 0, false);
        Weather.Report r = Weather.withObservation(model, 1000, 20.4, "TS", "OVC", 1000 + 30 * 60);
        assertEquals(20, r.temp);
        assertEquals(95, r.code);
        assertEquals(28, r.high);
        assertEquals(20, r.low);
        assertSame(model, Weather.withObservation(model, 1000, 20, "TS", "OVC", 1000 + 2 * 3600));   // stale
    }
}

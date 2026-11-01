package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

public class AgendaTest {

    @Test
    public void readsFoldedEventsAndSkipsIncompleteOnes() {
        String ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nDTSTART:20261101T130000Z\r\nSUMMARY:F1: Grand Prix (São\r\n  Paulo\\, Brazil)\r\n"
                + "BEGIN:VALARM\r\nDESCRIPTION:x\r\nEND:VALARM\r\nEND:VEVENT\r\n"
                + "BEGIN:VEVENT\r\nSUMMARY:No start\r\nEND:VEVENT\r\n"
                + "BEGIN:VEVENT\r\nDTSTART;TZID=UTC:20261102T120000Z\r\nSUMMARY:Show - S01E01\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        List<Agenda.Event> events = Agenda.parse(ics);
        assertEquals(2, events.size());
        assertEquals("F1: Grand Prix (São Paulo, Brazil)", events.get(0).title);
        assertEquals(Instant.parse("2026-11-01T13:00:00Z"), events.get(0).start);
        assertEquals(Instant.parse("2026-11-02T12:00:00Z"), events.get(1).start);
    }

    private static Agenda.Event ev(String start, String title) {
        return new Agenda.Event(Instant.parse(start), title);
    }

    @Test
    public void f1ShowsOnlyTheNearWeekendAndSeriesOnlyTheirNextEpisode() {
        Agenda.Event done = ev("2026-10-31T08:00:00Z", "Show - S01E01");
        Agenda.Event on = ev("2026-10-31T11:00:00Z", "Dexter - S02E01 (Paramount+)");
        Agenda.Event quali = ev("2026-10-31T21:00:00Z", "F1: Qualifying (Mexico City Grand Prix)");
        Agenda.Event race = ev("2026-11-01T20:00:00Z", "F1: Grand Prix (Mexico City Grand Prix)");
        Agenda.Event dexterAgain = ev("2026-11-07T11:00:00Z", "Dexter - S02E02 (Paramount+)");
        Agenda.Event nextGp = ev("2026-11-07T18:00:00Z", "F1: Qualifying (Brazilian Grand Prix)");
        Agenda.Event other = ev("2026-11-20T12:00:00Z", "Show - S01E02");
        Agenda.Event tooFar = ev("2026-12-15T12:00:00Z", "Ted Lasso - S04E01");
        List<Agenda.Event> all = Arrays.asList(done, on, quali, race, dexterAgain, nextGp, other, tooFar);
        ZoneId zone = ZoneId.of("America/Sao_Paulo");
        Instant now = Instant.parse("2026-10-31T12:00:00Z");
        assertEquals(Arrays.asList(on, quali, race, other), Agenda.select(all, now, zone, 9));
        assertEquals(Arrays.asList(on, quali), Agenda.select(all, now, zone, 2));
        // The next weekend (Sat 7 Nov) shows from Monday 2 Nov, local time.
        assertEquals(Arrays.asList(dexterAgain, other), Agenda.select(all, Instant.parse("2026-11-02T02:00:00Z"), zone, 9));
        assertEquals(Arrays.asList(dexterAgain, nextGp, other),
                Agenda.select(all, Instant.parse("2026-11-02T03:30:00Z"), zone, 9));
    }

    @Test
    public void titlesAreShortened() {
        assertEquals("Brazilian GP · Quali", Agenda.shortTitle("F1: Qualifying (Brazilian Grand Prix)", "Corrida"));
        assertEquals("Qatar GP · Sprint Quali", Agenda.shortTitle("F1: Sprint Qualification (Qatar Grand Prix)", "Corrida"));
        assertEquals("Brazilian GP · Corrida", Agenda.shortTitle("F1: Grand Prix (Brazilian Grand Prix)", "Corrida"));
        assertEquals("Dexter: Resurrection S02E02", Agenda.shortTitle("Dexter: Resurrection - S02E02 (Paramount+)", "Race"));
        assertEquals("Something else", Agenda.shortTitle("Something else", "Race"));
    }
}

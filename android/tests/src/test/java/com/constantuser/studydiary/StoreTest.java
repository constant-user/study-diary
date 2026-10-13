package com.constantuser.studydiary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Calendar;
import java.util.List;

/** Тесты хранилища: заметки, отметки, статистика, серия, экспорт/импорт. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class StoreTest {

    private Store store;

    @Before
    public void setUp() {
        store = new Store(RuntimeEnvironment.getApplication());
    }

    @Test
    public void notesCrud() {
        assertEquals(-1, store.addNote("плохая-дата", "текст"));
        assertEquals(-1, store.addNote("2026-10-13", "   "));
        assertEquals(-1, store.addNote("2026-10-13", "х".repeat(5001)));
        assertTrue(store.addNote("2026-10-13", "первая") > 0);
        assertTrue(store.addNote("2026-10-13", "вторая") > 0);
        assertEquals(2, store.noteCountFor("2026-10-13"));
        assertEquals(2, store.notesFor("2026-10-13").size());

        List<Store.Note> notes = store.notesFor("2026-10-13");
        long pinId = notes.get(0).id;
        store.togglePin(pinId);
        assertTrue(store.notesFor("2026-10-13").get(0).pinned);
        assertEquals("первая", store.notesFor("2026-10-13").get(0).text);

        store.updateNote(pinId, "изменена");
        assertEquals("изменена", store.notesFor("2026-10-13").get(0).text);
        store.updateNote(pinId, "   ");
        assertEquals("изменена", store.notesFor("2026-10-13").get(0).text);

        store.deleteNote(pinId);
        assertEquals(1, store.noteCountFor("2026-10-13"));
    }

    @Test
    public void marksAreClampedAndValidated() {
        store.writeMark("2026-10-13", 99999, "green");
        assertEquals(1440, store.markFor("2026-10-13").minutes);
        assertEquals("green", store.markFor("2026-10-13").status);

        store.writeMark("2026-10-13", -50, "purple");
        assertEquals(0, store.markFor("2026-10-13").minutes);
        assertEquals("", store.markFor("2026-10-13").status);

        int before = store.allMarks().size();
        store.writeMark("не-дата", 100, "green");
        assertEquals("невалидный день не создаёт отметку", before, store.allMarks().size());
    }

    @Test
    public void statsFilterByYear() {
        store.writeMark("2025-06-10", 120, "green");
        store.writeMark("2026-03-05", 60, "red");
        store.addNote("2026-03-05", "заметка");

        Store.DayStat s26 = store.statsForYear(2026);
        assertEquals("итоги — только за 2026 год", 60, s26.totalMinutes);
        assertEquals(60, s26.monthMinutes[2]);      /* март */
        assertEquals("июнь другого года не должен попадать", 0, s26.monthMinutes[5]);
        assertEquals(1, s26.markedDays);
        assertEquals(1, s26.red);
        assertEquals(0, s26.green);
        assertEquals(1, s26.noteCount);

        Store.DayStat s25 = store.statsForYear(2025);
        assertEquals(120, s25.totalMinutes);
        assertEquals(120, s25.monthMinutes[5]);
        assertEquals(0, s25.noteCount);
    }

    @Test
    public void streakCountsConsecutiveMarkedDays() {
        Calendar today = Calendar.getInstance();
        assertEquals(0, store.streak(today));

        store.writeMark(Store.formatDay(today), 30, "");
        assertEquals(1, store.streak(today));

        Calendar y = (Calendar) today.clone();
        y.add(Calendar.DAY_OF_MONTH, -1);
        store.writeMark(Store.formatDay(y), 45, "");
        assertEquals(2, store.streak(today));

        /* разрыв: вчера есть, позавчера нет — серия = 2; добавили позавчера → 3 */
        Calendar d2 = (Calendar) today.clone();
        d2.add(Calendar.DAY_OF_MONTH, -2);
        store.writeMark(Store.formatDay(d2), 10, "");
        assertEquals(3, store.streak(today));
    }

    @Test
    public void searchFindsNotesCaseInsensitively() {
        store.addNote("2026-10-13", "Апельсин на обед");
        store.addNote("2026-10-14", "ничего особенного");
        assertTrue(store.search("апельсин").size() == 1);
        assertTrue(store.search("АПЕЛЬСИН").size() == 1);
        assertEquals("2026-10-13", store.search("апельсин").get(0).day);
        assertEquals(0, store.search("банан").size());
        assertEquals(0, store.search("  ").size());
    }

    @Test
    public void exportImportRoundtrip() throws Exception {
        store.addNote("2026-10-13", "первая мысль");
        store.addNote("2026-10-13", "вторая");
        store.writeMark("2026-10-13", 95, "green");
        store.setGoalMinutes(180);
        store.setTheme("ocean");

        String exported = store.exportJson().toString();
        store.clearDiary();
        assertEquals(0, store.noteCountAll());
        assertEquals(0, store.allMarks().size());
        assertEquals("настройки переживают очистку", 180, store.goalMinutes());
        assertEquals("ocean", store.theme());

        store.importJson(exported);
        assertEquals(2, store.noteCountAll());
        assertEquals(95, store.markFor("2026-10-13").minutes);
        assertEquals("green", store.markFor("2026-10-13").status);
        assertEquals(180, store.goalMinutes());
        assertEquals("ocean", store.theme());
    }

    @Test
    public void importValidatesHostileData() throws Exception {
        String evil = "{"
                + "\"notes\":{\"'; DROP TABLE notes;--\":[{\"text\":\"взлом\"}],"
                + "\"2026-10-13\":[{\"text\":\"<script>alert(1)</script>\"}]},"
                + "\"marks\":{\"2026-10-13\":{\"minutes\":999999,\"status\":\"purple\"}},"
                + "\"settings\":{\"goalMinutes\":-100,\"theme\":\"hacker\"}"
                + "}";
        store.importJson(evil);
        assertEquals("опасный ключ дня отброшен, валидный принят",
                1, store.noteCountAll());
        assertEquals(0, store.noteCountFor("'; DROP TABLE notes;--"));
        assertEquals(1440, store.markFor("2026-10-13").minutes);
        assertEquals("", store.markFor("2026-10-13").status);
        assertEquals(0, store.goalMinutes());
        assertEquals("неизвестная тема отброшена", "mono", store.theme());
    }

    @Test
    public void themeWhitelist() {
        store.setTheme("ocean");
        assertEquals("ocean", store.theme());
        store.setTheme("hacker");
        assertEquals("mono", store.theme());
        store.setTheme(null);
        assertEquals("mono", store.theme());
        /* каждая из 9 тем выборочна */
        for (String id : Palettes.IDS) {
            store.setTheme(id);
            assertEquals(id, store.theme());
        }
    }

    @Test
    public void timerStateRoundtrip() {
        Store.TimerState t = new Store.TimerState();
        t.day = "2026-10-13";
        t.running = true;
        t.startedAt = 12345;
        t.baseSec = 61;
        t.committedMin = 1;
        store.saveTimer(t);

        Store.TimerState back = store.timer();
        assertEquals("2026-10-13", back.day);
        assertTrue(back.running);
        assertEquals(12345, back.startedAt);
        assertEquals(61, back.baseSec);
        assertEquals(1, back.committedMin);

        store.saveTimer(null);
        assertEquals("", store.timer().day);
        assertFalse(store.timer().running);

        /* повреждённый день отбрасывается */
        t.day = "99-99-99";
        store.saveTimer(t);
        assertEquals("", store.timer().day);
    }
}

package com.constantuser.studydiary;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Хранилище данных дневника на SQLite.
 * Модель данных повторяет веб-версию: заметки по дням, отметки
 * (минуты + статус), настройки (цель, тема), состояние таймера.
 */
final class Store {

    static final class Note {
        long id;
        String day;      // YYYY-MM-DD
        String text;
        boolean pinned;
        long created;
        long updated;

        Note() {
            this.id = -1;
            this.day = "";
            this.text = "";
            this.pinned = false;
            this.created = System.currentTimeMillis();
            this.updated = 0;
        }
    }

    static final class Mark {
        int minutes;     // 0..1440
        String status;   // "" | green | yellow | red

        Mark() {
            this.minutes = 0;
            this.status = "";
        }
    }

    private static final Pattern DAY_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final int MAX_MINUTES = 24 * 60;
    private static final String[] STATUSES = {"", "green", "yellow", "red"};

    private final Helper helper;
    private final Context context;

    Store(Context context) {
        this.context = context.getApplicationContext();
        this.helper = new Helper(this.context);
    }

    static boolean isValidDay(String day) {
        return day != null && DAY_PATTERN.matcher(day).matches();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /* ------------------------- заметки ---------------------------- */

    List<Note> notesFor(String day) {
        List<Note> out = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.query("notes", null, "day = ?", new String[]{day},
                null, null, "pinned DESC, created ASC");
        try {
            while (c.moveToNext()) {
                Note n = new Note();
                n.id = c.getLong(c.getColumnIndexOrThrow("id"));
                n.day = c.getString(c.getColumnIndexOrThrow("day"));
                n.text = c.getString(c.getColumnIndexOrThrow("text"));
                n.pinned = c.getInt(c.getColumnIndexOrThrow("pinned")) == 1;
                n.created = c.getLong(c.getColumnIndexOrThrow("created"));
                n.updated = c.getLong(c.getColumnIndexOrThrow("updated"));
                out.add(n);
            }
        } finally {
            c.close();
        }
        return out;
    }

    long addNote(String day, String text) {
        if (!isValidDay(day) || text == null) return -1;
        String clean = text.replace("\r\n", "\n").trim();
        if (clean.isEmpty() || clean.length() > 5000) return -1;
        if (notesFor(day).size() >= 200) return -1;

        ContentValues v = new ContentValues();
        v.put("day", day);
        v.put("text", clean);
        v.put("pinned", 0);
        v.put("created", System.currentTimeMillis());
        v.put("updated", 0);
        return helper.getWritableDatabase().insert("notes", null, v);
    }

    void updateNote(long id, String text) {
        if (text == null) return;
        String clean = text.replace("\r\n", "\n").trim();
        if (clean.isEmpty()) return;
        ContentValues v = new ContentValues();
        v.put("text", clean);
        v.put("updated", System.currentTimeMillis());
        helper.getWritableDatabase().update("notes", v, "id = ?", new String[]{String.valueOf(id)});
    }

    void togglePin(long id) {
        SQLiteDatabase db = helper.getWritableDatabase();
        Cursor c = db.query("notes", new String[]{"pinned"}, "id = ?",
                new String[]{String.valueOf(id)}, null, null, null);
        try {
            if (!c.moveToFirst()) return;
            ContentValues v = new ContentValues();
            v.put("pinned", c.getInt(0) == 1 ? 0 : 1);
            db.update("notes", v, "id = ?", new String[]{String.valueOf(id)});
        } finally {
            c.close();
        }
    }

    void deleteNote(long id) {
        helper.getWritableDatabase().delete("notes", "id = ?", new String[]{String.valueOf(id)});
    }

    int noteCountAll() {
        return count("notes", null, null);
    }

    int noteCountFor(String day) {
        if (!isValidDay(day)) return 0;
        return count("notes", "day = ?", day);
    }

    /* ------------------------- отметки ---------------------------- */

    Mark markFor(String day) {
        Mark out = new Mark();
        if (!isValidDay(day)) return out;
        Cursor c = helper.getReadableDatabase().query("marks",
                new String[]{"minutes", "status"}, "day = ?", new String[]{day},
                null, null, null);
        try {
            if (c.moveToFirst()) {
                out.minutes = clamp(c.getInt(0), 0, MAX_MINUTES);
                String s = c.getString(1);
                out.status = s == null ? "" : s;
            }
        } finally {
            c.close();
        }
        return out;
    }

    void writeMark(String day, int minutes, String status) {
        if (!isValidDay(day)) return;
        ContentValues v = new ContentValues();
        v.put("day", day);
        v.put("minutes", clamp(minutes, 0, MAX_MINUTES));
        v.put("status", STATUSES != null && isKnownStatus(status) ? status : "");
        helper.getWritableDatabase().insertWithOnConflict("marks", null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static boolean isKnownStatus(String status) {
        for (String s : STATUSES) {
            if (s.equals(status)) return true;
        }
        return false;
    }

    void clearMarkIfEmpty(String day) {
        Mark m = markFor(day);
        if (m.minutes == 0 && m.status.isEmpty()) {
            helper.getWritableDatabase().delete("marks", "day = ?", new String[]{day});
        }
    }

    Map<String, Mark> allMarks() {
        Map<String, Mark> out = new HashMap<>();
        Cursor c = helper.getReadableDatabase().query("marks",
                new String[]{"day", "minutes", "status"}, null, null, null, null, null);
        try {
            while (c.moveToNext()) {
                Mark m = new Mark();
                m.minutes = clamp(c.getInt(1), 0, MAX_MINUTES);
                String s = c.getString(2);
                m.status = s == null ? "" : s;
                out.put(c.getString(0), m);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /* ------------------------- настройки -------------------------- */

    int goalMinutes() {
        String v = getSetting("goal_minutes");
        try {
            int goal = v == null ? 120 : Integer.parseInt(v);
            return clamp(goal, 0, MAX_MINUTES);
        } catch (NumberFormatException e) {
            return 120;
        }
    }

    void setGoalMinutes(int minutes) {
        setSetting("goal_minutes", String.valueOf(clamp(minutes, 0, MAX_MINUTES)));
    }

    String theme() {
        String v = getSetting("theme");
        return v == null ? "mono" : v;
    }

    void setTheme(String id) {
        setSetting("theme", Palettes.IDS[Palettes.indexOf(id)]);
    }

    String getSetting(String key) {
        Cursor c = helper.getReadableDatabase().query("settings",
                new String[]{"value"}, "key = ?", new String[]{key}, null, null, null);
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    void setSetting(String key, String value) {
        ContentValues v = new ContentValues();
        v.put("key", key);
        v.put("value", value);
        helper.getWritableDatabase().insertWithOnConflict("settings", null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /* ------------------------- таймер ----------------------------- */

    /* Одна строка состояния: day, running, startedAt, baseSec, committedMin */
    static final class TimerState {
        String day = "";
        boolean running = false;
        long startedAt = 0;
        int baseSec = 0;
        int committedMin = 0;
    }

    TimerState timer() {
        TimerState t = new TimerState();
        Cursor c = helper.getReadableDatabase().query("timer",
                new String[]{"day", "running", "startedAt", "baseSec", "committedMin"},
                null, null, null, null, null);
        try {
            if (c.moveToFirst()) {
                String day = c.getString(0);
                if (isValidDay(day)) {
                    t.day = day;
                    t.running = c.getInt(1) == 1;
                    t.startedAt = Math.max(0, c.getLong(2));
                    t.baseSec = clamp(c.getInt(3), 0, MAX_MINUTES * 60);
                    t.committedMin = clamp(c.getInt(4), 0, MAX_MINUTES);
                }
            }
        } finally {
            c.close();
        }
        return t;
    }

    void saveTimer(TimerState t) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete("timer", null, null);
        if (t == null || !isValidDay(t.day)) return;
        ContentValues v = new ContentValues();
        v.put("day", t.day);
        v.put("running", t.running ? 1 : 0);
        v.put("startedAt", t.startedAt);
        v.put("baseSec", clamp(t.baseSec, 0, MAX_MINUTES * 60));
        v.put("committedMin", clamp(t.committedMin, 0, MAX_MINUTES));
        db.insert("timer", null, v);
    }

    /* ------------------------- поиск ------------------------------ */

    static final class SearchHit {
        String day;
        String snippet;
    }

    List<SearchHit> search(String query) {
        List<SearchHit> out = new ArrayList<>();
        String q = query == null ? "" : query.trim().toLowerCase();
        if (q.isEmpty()) return out;

        Cursor c = helper.getReadableDatabase().query("notes",
                new String[]{"day", "text", "pinned"}, null, null, null, null,
                "day DESC, pinned DESC, created ASC");
        try {
            while (c.moveToNext()) {
                String text = c.getString(1);
                if (text == null || !text.toLowerCase().contains(q)) continue;
                SearchHit hit = new SearchHit();
                hit.day = c.getString(0);
                String flat = text.replace('\n', ' ');
                hit.snippet = flat.length() > 60 ? flat.substring(0, 60) + "…" : flat;
                out.add(hit);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /* ------------------------- статистика ------------------------- */

    static final class DayStat {
        int totalMinutes;
        int markedDays;
        int noteCount;
        int green, yellow, red;
        int[] monthMinutes = new int[12];
        int[] monthDays = new int[12];
    }

    DayStat statsForYear(int year) {
        DayStat s = new DayStat();
        String prefix = year + "-";

        Map<String, Mark> marks = allMarks();
        for (Map.Entry<String, Mark> e : marks.entrySet()) {
            String day = e.getKey();
            Mark m = e.getValue();
            if (!day.startsWith(prefix)) continue;   /* только дни этого года */
            s.totalMinutes += m.minutes;
            if (m.minutes > 0) s.markedDays++;
            if ("green".equals(m.status)) s.green++;
            else if ("yellow".equals(m.status)) s.yellow++;
            else if ("red".equals(m.status)) s.red++;

            if (m.minutes > 0 && day.length() >= 7) {
                try {
                    int month = Integer.parseInt(day.substring(5, 7)) - 1;
                    if (month >= 0 && month < 12) {
                        s.monthMinutes[month] += m.minutes;
                        s.monthDays[month]++;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        s.noteCount = count("notes", "day LIKE ?", prefix + "%");
        return s;
    }

    /** Серия дней подряд с отметкой времени, заканчиваясь сегодня или вчера. */
    int streak(java.util.Calendar today) {
        java.util.Calendar c = (java.util.Calendar) today.clone();
        int streak = 0;
        /* если сегодня ещё не отмечено — начинаем со вчера */
        if (markFor(formatDay(c)).minutes <= 0) {
            c.add(java.util.Calendar.DAY_OF_MONTH, -1);
        }
        /* предохранитель: не уходить дальше 10 лет назад */
        while (streak < 3660 && markFor(formatDay(c)).minutes > 0) {
            streak++;
            c.add(java.util.Calendar.DAY_OF_MONTH, -1);
        }
        return streak;
    }

    static String formatDay(java.util.Calendar c) {
        return c.get(java.util.Calendar.YEAR) + "-"
                + twoDigits(c.get(java.util.Calendar.MONTH) + 1) + "-"
                + twoDigits(c.get(java.util.Calendar.DAY_OF_MONTH));
    }

    static String twoDigits(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    /* ------------------------- экспорт / импорт ------------------- */

    JSONObject exportJson() throws Exception {
        JSONObject out = new JSONObject();
        out.put("app", "diary");
        out.put("version", 3);
        out.put("exportedAt", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ",
                java.util.Locale.US).format(new java.util.Date()));

        JSONObject notes = new JSONObject();
        Cursor c = helper.getReadableDatabase().query("notes", null, null, null, null, null,
                "day ASC, pinned DESC, created ASC");
        try {
            while (c.moveToNext()) {
                String day = c.getString(c.getColumnIndexOrThrow("day"));
                JSONObject item = new JSONObject();
                item.put("id", c.getString(c.getColumnIndexOrThrow("id")));
                item.put("text", c.getString(c.getColumnIndexOrThrow("text")));
                item.put("pinned", c.getInt(c.getColumnIndexOrThrow("pinned")) == 1);
                item.put("createdAt", c.getLong(c.getColumnIndexOrThrow("created")));
                item.put("updatedAt", c.getLong(c.getColumnIndexOrThrow("updated")));
                JSONArray arr = notes.isNull(day) ? new JSONArray() : notes.getJSONArray(day);
                arr.put(item);
                notes.put(day, arr);
            }
        } finally {
            c.close();
        }
        out.put("notes", notes);

        JSONObject marks = new JSONObject();
        for (Map.Entry<String, Mark> e : allMarks().entrySet()) {
            JSONObject m = new JSONObject();
            m.put("minutes", e.getValue().minutes);
            m.put("status", e.getValue().status);
            marks.put(e.getKey(), m);
        }
        out.put("marks", marks);

        JSONObject settings = new JSONObject();
        settings.put("goalMinutes", goalMinutes());
        settings.put("theme", theme());
        out.put("settings", settings);

        return out;
    }

    /** Импорт из JSON той же структуры, что экспортирует веб-версия. */
    void importJson(String json) throws Exception {
        JSONObject root = new JSONObject(json);

        JSONObject notes = root.optJSONObject("notes");
        if (notes != null) {
            JSONArray keys = notes.names();
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
                    String day = keys.optString(i, "");
                    if (!isValidDay(day)) continue;
                    JSONArray arr = notes.optJSONArray(day);
                    if (arr == null) continue;
                    for (int j = 0; j < arr.length(); j++) {
                        JSONObject item = arr.optJSONObject(j);
                        if (item == null) continue;
                        String text = item.optString("text", "");
                        if (text.isEmpty()) continue;
                        Note n = new Note();
                        n.day = day;
                        n.text = text.replace("\r\n", "\n");
                        n.pinned = item.optBoolean("pinned", false);
                        n.created = item.optLong("createdAt", System.currentTimeMillis());
                        insertImportedNote(n);
                    }
                }
            }
        }

        JSONObject marks = root.optJSONObject("marks");
        if (marks != null) {
            JSONArray keys = marks.names();
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
                    String day = keys.optString(i, "");
                    if (!isValidDay(day)) continue;
                    JSONObject m = marks.optJSONObject(day);
                    if (m == null) continue;
                    int minutes = clamp(m.optInt("minutes", 0), 0, MAX_MINUTES);
                    String status = m.optString("status", "");
                    if (!isKnownStatus(status)) status = "";
                    if (minutes > 0 || !status.isEmpty()) {
                        writeMark(day, minutes, status);
                    }
                }
            }
        }

        JSONObject settings = root.optJSONObject("settings");
        if (settings != null) {
            int goal = clamp(settings.optInt("goalMinutes", goalMinutes()), 0, MAX_MINUTES);
            setGoalMinutes(goal);
            String theme = settings.optString("theme", "");
            if (Palettes.indexOf(theme) == 0 && !"mono".equals(theme)) {
                /* неизвестная тема — оставляем текущую */
            } else {
                setTheme(theme);
            }
        }
    }

    private void insertImportedNote(Note n) {
        ContentValues v = new ContentValues();
        v.put("day", n.day);
        v.put("text", n.text);
        v.put("pinned", n.pinned ? 1 : 0);
        v.put("created", n.created);
        v.put("updated", n.updated);
        helper.getWritableDatabase().insert("notes", null, v);
    }

    /* ------------------------- очистка ---------------------------- */

    void clearDiary() {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete("notes", null, null);
        db.delete("marks", null, null);
        db.delete("timer", null, null);
        /* настройки (цель, тема) сохраняются — как в веб-версии */
    }

    private int count(String table, String where, String arg) {
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + table + (where == null ? "" : " WHERE " + where),
                arg == null ? null : new String[]{arg});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /* ------------------------- схема ------------------------------ */

    private static final class Helper extends SQLiteOpenHelper {

        Helper(Context context) {
            super(context, "diary.db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE notes ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "day TEXT NOT NULL,"
                    + "text TEXT NOT NULL,"
                    + "pinned INTEGER NOT NULL DEFAULT 0,"
                    + "created INTEGER NOT NULL,"
                    + "updated INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX idx_notes_day ON notes(day)");
            db.execSQL("CREATE TABLE marks ("
                    + "day TEXT PRIMARY KEY,"
                    + "minutes INTEGER NOT NULL DEFAULT 0,"
                    + "status TEXT NOT NULL DEFAULT '')");
            db.execSQL("CREATE TABLE settings ("
                    + "key TEXT PRIMARY KEY,"
                    + "value TEXT NOT NULL)");
            db.execSQL("CREATE TABLE timer ("
                    + "day TEXT NOT NULL,"
                    + "running INTEGER NOT NULL DEFAULT 0,"
                    + "startedAt INTEGER NOT NULL DEFAULT 0,"
                    + "baseSec INTEGER NOT NULL DEFAULT 0,"
                    + "committedMin INTEGER NOT NULL DEFAULT 0)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            /* первая версия — пусто */
        }
    }

    /* Для обратной совместимости с веб-экспортом: карта дней -> список заметок */
    LinkedHashMap<String, List<Note>> allNotesByDay() {
        LinkedHashMap<String, List<Note>> out = new LinkedHashMap<>();
        Cursor c = helper.getReadableDatabase().query("notes", null, null, null, null, null,
                "day ASC, pinned DESC, created ASC");
        try {
            while (c.moveToNext()) {
                Note n = new Note();
                n.id = c.getLong(c.getColumnIndexOrThrow("id"));
                n.day = c.getString(c.getColumnIndexOrThrow("day"));
                n.text = c.getString(c.getColumnIndexOrThrow("text"));
                n.pinned = c.getInt(c.getColumnIndexOrThrow("pinned")) == 1;
                n.created = c.getLong(c.getColumnIndexOrThrow("created"));
                n.updated = c.getLong(c.getColumnIndexOrThrow("updated"));
                List<Note> list = out.get(n.day);
                if (list == null) {
                    list = new ArrayList<>();
                    out.put(n.day, list);
                }
                list.add(n);
            }
        } finally {
            c.close();
        }
        return out;
    }
}

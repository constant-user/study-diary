package com.constantuser.studydiary;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Дневник — нативное Android-приложение.
 * Календарь, заметки по дням, таймер учёбы, цели, статусы, серия дней,
 * статистика года, поиск, 9 тем, экспорт/импорт JSON.
 * Данные — только в SQLite приложения; разрешений приложение не запрашивает.
 */
public class MainActivity extends Activity {

    private static final String[] MONTHS = {
            "ЯНВАРЬ", "ФЕВРАЛЬ", "МАРТ", "АПРЕЛЬ", "МАЙ", "ИЮНЬ",
            "ИЮЛЬ", "АВГУСТ", "СЕНТЯБРЬ", "ОКТЯБРЬ", "НОЯБРЬ", "ДЕКАБРЬ"
    };
    private static final String[] MONTHS_GEN = {
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря"
    };
    private static final String[] WEEKDAYS = {"пн", "вт", "ср", "чт", "пт", "сб", "вс"};
    private static final String[] STATUS_IDS = {"", "green", "yellow", "red"};
    private static final String[] STATUS_NAMES = {"Нет", "Зелёный", "Жёлтый", "Красный"};

    private Store store;
    private Palettes pal;

    private int viewYear;
    private int viewMonth;          // 0..11
    private String selectedDay;     // YYYY-MM-DD или null

    private LinearLayout root;
    private TextView monthLabel;
    private TextView statsLine;
    private GridView daysGrid;
    private final DaysAdapter daysAdapter = new DaysAdapter();

    private Handler timerHandler;
    private Runnable timerTick;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        store = new Store(this);
        pal = Palettes.get(store.theme());

        Calendar now = Calendar.getInstance();
        viewYear = now.get(Calendar.YEAR);
        viewMonth = now.get(Calendar.MONTH);
        selectedDay = Store.formatDay(now);

        timerHandler = new Handler(Looper.getMainLooper());

        buildUi();
        applyPalette();
        refresh();
    }

    @Override
    protected void onDestroy() {
        if (timerHandler != null && timerTick != null) {
            timerHandler.removeCallbacks(timerTick);
        }
        super.onDestroy();
    }

    /* ====================== Построение интерфейса ================= */

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        setContentView(root);

        /* --- шапка: название и меню --- */
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView brand = new TextView(this);
        brand.setText("ДНЕВНИК");
        brand.setTextSize(16);
        brand.setLetterSpacing(0.4f);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.setId(View.generateViewId());
        header.addView(brand, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button menuBtn = new Button(this);
        menuBtn.setText("⋮");
        menuBtn.setTextSize(20);
        menuBtn.setId(View.generateViewId());
        menuBtn.setPadding(dp(14), 0, dp(8), 0);
        menuBtn.setOnClickListener(v -> showMenu(v));
        header.addView(menuBtn, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* --- навигация по месяцам --- */
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(0, dp(12), 0, dp(4));

        Button prev = smallButton("‹");
        prev.setOnClickListener(v -> shiftMonth(-1));
        nav.addView(prev, ViewGroup.LayoutParams.WRAP_CONTENT);

        monthLabel = new TextView(this);
        monthLabel.setTextSize(18);
        monthLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        monthLabel.setGravity(Gravity.CENTER);
        monthLabel.setOnClickListener(v -> showYearStats());
        LinearLayout.LayoutParams monthLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nav.addView(monthLabel, monthLp);

        Button next = smallButton("›");
        next.setOnClickListener(v -> shiftMonth(1));
        nav.addView(next, ViewGroup.LayoutParams.WRAP_CONTENT);

        root.addView(nav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button todayBtn = new Button(this);
        todayBtn.setText("СЕГОДНЯ");
        todayBtn.setTextSize(12);
        todayBtn.setOnClickListener(v -> {
            Calendar now = Calendar.getInstance();
            viewYear = now.get(Calendar.YEAR);
            viewMonth = now.get(Calendar.MONTH);
            selectedDay = Store.formatDay(now);
            refresh();
        });
        LinearLayout.LayoutParams todayLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        todayLp.bottomMargin = dp(8);
        root.addView(todayBtn, todayLp);

        /* --- строка статистики --- */
        statsLine = new TextView(this);
        statsLine.setTextSize(12);
        statsLine.setPadding(dp(2), dp(4), dp(2), dp(10));
        root.addView(statsLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* --- дни недели --- */
        LinearLayout weekdays = new LinearLayout(this);
        weekdays.setPadding(dp(4), 0, dp(4), dp(6));
        for (String wd : WEEKDAYS) {
            TextView t = new TextView(this);
            t.setText(wd.toUpperCase(Locale.ROOT));
            t.setTextSize(11);
            t.setGravity(Gravity.CENTER);
            weekdays.addView(t, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(weekdays, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* --- сетка дней --- */
        daysGrid = new GridView(this);
        daysGrid.setNumColumns(7);
        daysGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        daysGrid.setVerticalSpacing(dp(6));
        daysGrid.setHorizontalSpacing(dp(6));
        daysGrid.setSelector(android.R.color.transparent);
        daysGrid.setAdapter(daysAdapter);
        daysGrid.setOnItemClickListener((parent, view, position, id) -> {
            DayCell cell = (DayCell) view.getTag();
            if (cell == null || !cell.hasDate) return;
            selectedDay = cell.dayKey;
            daysAdapter.notifyDataSetChanged();
            openDayDialog(cell.dayKey);
        });
        root.addView(daysGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        /* --- подсказка --- */
        TextView hint = new TextView(this);
        hint.setText("Коснитесь дня — заметки и таймер\nВсё хранится только на этом устройстве");
        hint.setTextSize(11);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(10), 0, 0);
        root.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(18);
        b.setPadding(dp(16), 0, dp(16), 0);
        return b;
    }

    private void applyPalette() {
        getWindow().setStatusBarColor(pal.bg);
        getWindow().setNavigationBarColor(pal.bg);
        root.setBackgroundColor(pal.bg);
        restyle(root);
    }

    /** Пройтись по дереву и покрасить стандартные виджеты под палитру. */
    private void restyle(View view) {
        if (view instanceof TextView) {
            TextView t = (TextView) view;
            CharSequence text = t.getText();
            boolean isHint = text != null && text.toString().contains("Хранится");
            boolean isBrand = text != null && text.toString().equals("ДНЕВНИК");
            t.setTextColor(isHint || isBrand ? (isBrand ? pal.text : pal.muted) : pal.text);
        }
        if (view instanceof android.widget.Button) {
            Button b = (android.widget.Button) view;
            b.setTextColor(pal.accent);
            b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        }
        if (view instanceof GridView) {
            view.setBackgroundColor(pal.bg);
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                restyle(g.getChildAt(i));
            }
        }
    }

    /* ====================== Обновление данных ===================== */

    private void refresh() {
        monthLabel.setText(MONTHS[viewMonth] + "  " + viewYear);
        daysAdapter.notifyDataSetChanged();

        Calendar now = Calendar.getInstance();
        int streak = store.streak(now);
        Store.DayStat stat = store.statsForYear(viewYear);

        statsLine.setText(String.format(Locale.ROOT,
                "СЕРИЯ %d дн.  ·  ЧАСЫ %s  ·  ЗАМЕТОК %d",
                streak, formatMinutes(stat.totalMinutes), stat.noteCount));
    }

    /* ====================== Сетка дней ============================ */

    private final class DaysAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return 42;
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            DayCell cell = new DayCell(MainActivity.this);

            int weekdayShift = firstWeekdayShift();   // 0 = понедельник в первой клетке
            int dayNumber = position - weekdayShift + 1;
            Calendar probe = Calendar.getInstance();
            probe.set(viewYear, viewMonth, 1);
            int daysInMonth = probe.getActualMaximum(Calendar.DAY_OF_MONTH);

            if (dayNumber >= 1 && dayNumber <= daysInMonth) {
                String key = viewYear + "-" + Store.twoDigits(viewMonth + 1)
                        + "-" + Store.twoDigits(dayNumber);
                cell.bind(key, dayNumber);
            } else {
                cell.bindEmpty();
            }
            return cell;
        }
    }

    /** Понедельник = 0. */
    private int firstWeekdayShift() {
        Calendar c = Calendar.getInstance();
        c.set(viewYear, viewMonth, 1);
        int wd = c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY;
        return wd < 0 ? wd + 7 : wd;
    }

    /** Ячейка календаря: номер дня, время, статусная рамка, точка заметки. */
    private final class DayCell extends LinearLayout {

        boolean hasDate;
        String dayKey = "";
        private final TextView numView;
        private final TextView timeView;
        private final TextView dotView;

        DayCell(android.content.Context context) {
            super(context);
            setOrientation(LinearLayout.VERTICAL);
            setGravity(Gravity.CENTER);
            int pad = dp(6);
            setPadding(pad, dp(10), pad, dp(14));

            numView = new TextView(context);
            numView.setTextSize(16);
            numView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            numView.setGravity(Gravity.CENTER);
            addView(numView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            timeView = new TextView(context);
            timeView.setTextSize(10);
            timeView.setGravity(Gravity.CENTER);
            LayoutParams timeLp = new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            timeLp.topMargin = dp(2);
            addView(timeView, timeLp);

            dotView = new TextView(context);
            dotView.setTextSize(9);
            dotView.setGravity(Gravity.CENTER);
            LayoutParams dotLp = new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dotLp.topMargin = dp(1);
            addView(dotView, dotLp);
        }

        void bindEmpty() {
            hasDate = false;
            dayKey = "";
            numView.setText("");
            timeView.setText("");
            dotView.setText("");
            setBackground(rounded(pal.bg, pal.bg, 0));
        }

        void bind(String key, int dayNumber) {
            hasDate = true;
            dayKey = key;

            Store.Mark mark = store.markFor(key);
            int noteCount = store.noteCountFor(key);
            Calendar today = Calendar.getInstance();
            String todayKey = Store.formatDay(today);
            boolean isToday = todayKey.equals(key);
            boolean isSelected = key.equals(selectedDay);

            numView.setText(String.valueOf(dayNumber));
            timeView.setText(mark.minutes > 0 ? formatMinutes(mark.minutes) : "—");
            dotView.setText(noteCount > 0 ? "●" : "");

            int frameColor = pal.line;
            if (!mark.status.isEmpty()) frameColor = pal.statusColor(mark.status);
            else if (store.goalMinutes() > 0 && mark.minutes >= store.goalMinutes()) frameColor = pal.green;
            else if (isToday) frameColor = pal.text;

            if (isSelected) {
                /* инверсия, как в веб-версии */
                numView.setTextColor(pal.onText);
                timeView.setTextColor(pal.onText);
                dotView.setTextColor(pal.onText);
                setBackground(rounded(pal.text, frameColor, dp(2)));
            } else {
                numView.setTextColor(isToday ? pal.accent : pal.text);
                timeView.setTextColor(mark.minutes > 0 ? pal.text : pal.muted);
                dotView.setTextColor(pal.text);
                int fill = mark.status.isEmpty() ? pal.surface : tint(pal.statusColor(mark.status));
                setBackground(rounded(fill, frameColor, isToday ? dp(2) : dp(1)));
            }
        }
    }

    /** Полупрозрачная подложка под рамкой статуса. */
    private int tint(int color) {
        int alpha = pal.light ? 40 : 28;
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private GradientDrawable rounded(int fill, int stroke, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(12));
        d.setStroke(strokeWidth, stroke);
        return d;
    }

    /* ====================== Диалог дня ============================ */

    private Dialog dayDialog;
    private Handler dialogTimerHandler;
    private Runnable dialogTimerTick;

    private void openDayDialog(final String dayKey) {
        if (dayDialog != null) dayDialog.dismiss();

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, pad);
        scroll.addView(box);
        dialog.setContentView(scroll);

        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(rounded(pal.surface, pal.line, dp(1)));
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        /* --- заголовок --- */
        String[] parts = dayKey.split("-");
        int dayNum = Integer.parseInt(parts[2]);
        int monthIdx = Integer.parseInt(parts[1]) - 1;
        TextView title = new TextView(this);
        title.setText(dayNum + " " + MONTHS_GEN[monthIdx] + " " + parts[0]);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(pal.text);
        box.addView(title, matchWrap());

        /* --- учёба: часы и минуты --- */
        box.addView(sectionLabel("УЧЁБА"));
        LinearLayout studyRow = new LinearLayout(this);
        studyRow.setGravity(Gravity.CENTER_VERTICAL);

        final EditText hoursInput = numberInput();
        final EditText minutesInput = numberInput();
        studyRow.addView(hoursInput, wrapContent());
        studyRow.addView(unitLabel("ч"), wrapContent());
        studyRow.addView(minutesInput, wrapContent());
        studyRow.addView(unitLabel("м"), wrapContent());

        Button resetStudy = new Button(this);
        resetStudy.setText("Сброс");
        resetStudy.setTextSize(12);
        resetStudy.setTextColor(pal.muted);
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetLp.leftMargin = dp(12);
        studyRow.addView(resetStudy, resetLp);
        box.addView(studyRow, matchWrap());

        /* --- таймер --- */
        LinearLayout timerRow = new LinearLayout(this);
        timerRow.setGravity(Gravity.CENTER_VERTICAL);
        timerRow.setPadding(0, dp(10), 0, 0);

        final TextView timerText = new TextView(this);
        timerText.setTextSize(28);
        timerText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        timerText.setTextColor(pal.accent);
        timerRow.addView(timerText, wrapContent());

        final Button timerBtn = new Button(this);
        timerBtn.setTextSize(12);
        timerRow.addView(timerBtn, wrapContent());

        Button timerMinus = new Button(this);
        timerMinus.setText("−5 с");
        timerMinus.setTextSize(12);
        timerMinus.setTextColor(pal.muted);
        timerRow.addView(timerMinus, wrapContent());

        Button timerPlus = new Button(this);
        timerPlus.setText("+5 с");
        timerPlus.setTextSize(12);
        timerPlus.setTextColor(pal.muted);
        timerRow.addView(timerPlus, wrapContent());

        Button timerReset = new Button(this);
        timerReset.setText("Обнулить");
        timerReset.setTextSize(12);
        timerReset.setTextColor(pal.muted);
        timerRow.addView(timerReset, wrapContent());
        box.addView(timerRow, matchWrap());

        /* --- цель в день --- */
        LinearLayout goalRow = new LinearLayout(this);
        goalRow.setGravity(Gravity.CENTER_VERTICAL);
        goalRow.setPadding(0, dp(12), 0, 0);

        TextView goalLabel = new TextView(this);
        goalLabel.setText("ЦЕЛЬ В ДЕНЬ");
        goalLabel.setTextSize(12);
        goalLabel.setTextColor(pal.muted);
        goalRow.addView(goalLabel, wrapContent());

        final EditText goalHours = numberInput();
        final EditText goalMinutes = numberInput();
        goalRow.addView(space(dp(10)));
        goalRow.addView(goalHours, wrapContent());
        goalRow.addView(unitLabel("ч"), wrapContent());
        goalRow.addView(goalMinutes, wrapContent());
        goalRow.addView(unitLabel("м"), wrapContent());
        box.addView(goalRow, matchWrap());

        final TextView progressText = new TextView(this);
        progressText.setTextSize(12);
        progressText.setPadding(0, dp(8), 0, 0);
        box.addView(progressText, matchWrap());

        /* --- статус дня --- */
        box.addView(sectionLabel("СТАТУС ДНЯ"));
        final LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(statusRow, matchWrap());

        /* --- заметки --- */
        box.addView(sectionLabel("ЗАМЕТКИ"));
        final EditText noteInput = new EditText(this);
        noteInput.setHint("Записать мысль на этот день…");
        noteInput.setHintTextColor(pal.muted);
        noteInput.setTextColor(pal.text);
        noteInput.setBackgroundTintList(android.content.res.ColorStateList.valueOf(pal.line));
        noteInput.setMinLines(2);
        box.addView(noteInput, matchWrap());

        final LinearLayout editorRow = new LinearLayout(this);
        editorRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        final Button cancelEdit = new Button(this);
        cancelEdit.setText("Отмена");
        cancelEdit.setTextSize(12);
        cancelEdit.setTextColor(pal.muted);
        cancelEdit.setVisibility(View.GONE);
        editorRow.addView(cancelEdit, wrapContent());
        final Button saveNote = new Button(this);
        saveNote.setText("СОХРАНИТЬ");
        saveNote.setTextSize(12);
        saveNote.setTextColor(pal.accent);
        editorRow.addView(saveNote, wrapContent());
        box.addView(editorRow, matchWrap());

        final LinearLayout notesList = new LinearLayout(this);
        notesList.setOrientation(LinearLayout.VERTICAL);
        box.addView(notesList, matchWrap());

        final long[] editingId = {-1};

        /* ---------- логика диалога ---------- */

        final Runnable[] reloadUi = new Runnable[1];

        reloadUi[0] = () -> {
            Store.Mark mark = store.markFor(dayKey);

            hoursInput.setText(String.valueOf(mark.minutes / 60));
            minutesInput.setText(String.valueOf(mark.minutes % 60));

            int goal = store.goalMinutes();
            goalHours.setText(String.valueOf(goal / 60));
            goalMinutes.setText(String.valueOf(goal % 60));

            int pct = goal > 0 ? Math.min(100, mark.minutes * 100 / goal) : 0;
            progressText.setText(String.format(Locale.ROOT, "%s из %s · %d%%",
                    formatMinutes(mark.minutes), formatMinutes(goal), pct));
            progressText.setTextColor(mark.minutes >= goal && goal > 0 ? pal.green : pal.muted);

            /* статусы */
            statusRow.removeAllViews();
            for (int i = 0; i < STATUS_IDS.length; i++) {
                final String status = STATUS_IDS[i];
                Button chip = new Button(this);
                chip.setText(STATUS_NAMES[i]);
                chip.setTextSize(12);
                boolean active = status.equals(mark.status);
                int chipColor = status.isEmpty() ? pal.muted : pal.statusColor(status);
                chip.setTextColor(active ? (status.isEmpty() ? pal.bg : chipTextColor(status)) : chipColor);
                chip.setBackground(chipBackground(active ? chipColor : pal.line, active));
                chip.setOnClickListener(v -> {
                    store.writeMark(dayKey, store.markFor(dayKey).minutes, status);
                    reloadUi[0].run();
                    refresh();
                });
                LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                chipLp.rightMargin = dp(6);
                statusRow.addView(chip, chipLp);
            }

            /* таймер */
            Store.TimerState t = store.timer();
            boolean timerOnThisDay = t.day.equals(dayKey) && (t.baseSec > 0 || t.running);
            timerText.setText(formatClock(timerOnThisDay ? totalSeconds(t) : 0));
            timerBtn.setText(timerOnThisDay && t.running ? "Пауза" : "Старт");

            /* заметки */
            notesList.removeAllViews();
            List<Store.Note> notes = store.notesFor(dayKey);
            if (notes.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("Заметок пока нет");
                empty.setTextColor(pal.muted);
                empty.setTextSize(13);
                empty.setPadding(0, dp(8), 0, 0);
                notesList.addView(empty, matchWrap());
            }
            for (final Store.Note note : notes) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, dp(6), 0, dp(6));

                TextView text = new TextView(this);
                text.setText((note.pinned ? "★ " : "") + note.text);
                text.setTextColor(note.pinned ? pal.accent : pal.text);
                text.setTextSize(14);
                row.addView(text, matchWrap());

                LinearLayout actions = new LinearLayout(this);
                actions.setGravity(Gravity.END);
                actions.addView(smallAction(note.pinned ? "Снять ★" : "★ Важное", v -> {
                    store.togglePin(note.id);
                    reloadUi[0].run();
                    refresh();
                }));
                actions.addView(smallAction("Изменить", v -> {
                    noteInput.setText(note.text);
                    editingId[0] = note.id;
                    cancelEdit.setVisibility(View.VISIBLE);
                }));
                actions.addView(smallAction("Удалить", v -> {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Удалить заметку?")
                            .setMessage("Действие нельзя отменить.")
                            .setPositiveButton("Удалить", (d, w) -> {
                                store.deleteNote(note.id);
                                reloadUi[0].run();
                                refresh();
                            })
                            .setNegativeButton("Отмена", null)
                            .show();
                }));
                row.addView(actions, matchWrap());
                notesList.addView(row, matchWrap());
            }
        };

        /* кнопки действий */
        View.OnClickListener applyStudy = v -> {
            int h = safeInt(hoursInput.getText().toString(), 0, 23);
            int m = safeInt(minutesInput.getText().toString(), 0, 59);
            store.writeMark(dayKey, h * 60 + m, store.markFor(dayKey).status);
            reloadUi[0].run();
            refresh();
        };
        hoursInput.setOnFocusChangeListener((v, has) -> { if (!has) applyStudy.onClick(v); });
        minutesInput.setOnFocusChangeListener((v, has) -> { if (!has) applyStudy.onClick(v); });
        hoursInput.setOnEditorActionListener((v, a, e) -> { applyStudy.onClick(v); return true; });
        minutesInput.setOnEditorActionListener((v, a, e) -> { applyStudy.onClick(v); return true; });

        resetStudy.setOnClickListener(v -> {
            Store.TimerState t = store.timer();
            if (t.day.equals(dayKey)) store.saveTimer(null);
            store.writeMark(dayKey, 0, store.markFor(dayKey).status);
            reloadUi[0].run();
            refresh();
        });

        timerBtn.setOnClickListener(v -> {
            Store.TimerState t = store.timer();
            if (t.day.equals(dayKey) && (t.running || t.baseSec > 0)) {
                if (t.running) {
                    commitTimer(t);
                    t.running = false;
                    t.startedAt = 0;
                } else {
                    t.running = true;
                    t.startedAt = System.currentTimeMillis();
                }
                store.saveTimer(t);
            } else {
                store.saveTimer(null);
                Store.TimerState fresh = new Store.TimerState();
                fresh.day = dayKey;
                fresh.running = true;
                fresh.startedAt = System.currentTimeMillis();
                store.saveTimer(fresh);
            }
            reloadUi[0].run();
        });

        timerMinus.setOnClickListener(v -> adjustTimer(dayKey, -5, reloadUi[0]));
        timerPlus.setOnClickListener(v -> adjustTimer(dayKey, +5, reloadUi[0]));
        timerReset.setOnClickListener(v -> {
            Store.TimerState t = store.timer();
            if (!t.day.equals(dayKey)) return;
            commitTimer(t);
            t.baseSec = 0;
            t.committedMin = 0;
            t.startedAt = t.running ? System.currentTimeMillis() : 0;
            store.saveTimer(t);
            reloadUi[0].run();
            Toast.makeText(MainActivity.this, "Таймер обнулён, время в цели сохранено",
                    Toast.LENGTH_SHORT).show();
        });

        View.OnClickListener applyGoal = v -> {
            int h = safeInt(goalHours.getText().toString(), 0, 24);
            int m = safeInt(goalMinutes.getText().toString(), 0, 59);
            store.setGoalMinutes(h * 60 + m);
            reloadUi[0].run();
            refresh();
        };
        goalHours.setOnFocusChangeListener((v, has) -> { if (!has) applyGoal.onClick(v); });
        goalMinutes.setOnFocusChangeListener((v, has) -> { if (!has) applyGoal.onClick(v); });

        saveNote.setOnClickListener(v -> {
            String text = noteInput.getText().toString();
            if (editingId[0] >= 0) {
                store.updateNote(editingId[0], text);
                editingId[0] = -1;
                cancelEdit.setVisibility(View.GONE);
            } else {
                if (store.addNote(dayKey, text) < 0 && !TextUtils.isEmpty(text)) {
                    Toast.makeText(MainActivity.this, "Не удалось добавить заметку",
                            Toast.LENGTH_SHORT).show();
                }
            }
            noteInput.setText("");
            reloadUi[0].run();
            refresh();
        });
        cancelEdit.setOnClickListener(v -> {
            editingId[0] = -1;
            noteInput.setText("");
            cancelEdit.setVisibility(View.GONE);
        });

        /* живые секунды таймера */
        dialogTimerHandler = new Handler(Looper.getMainLooper());
        dialogTimerTick = new Runnable() {
            @Override
            public void run() {
                Store.TimerState t = store.timer();
                boolean onThisDay = t.day.equals(dayKey) && (t.baseSec > 0 || t.running);
                timerText.setText(formatClock(onThisDay ? totalSeconds(t) : 0));
                if (t.running) {
                    commitTimer(t);
                    store.saveTimer(t);
                    final Store.Mark mark = store.markFor(dayKey);
                    final int goal = store.goalMinutes();
                    final int pct = goal > 0 ? Math.min(100, mark.minutes * 100 / goal) : 0;
                    progressText.setText(String.format(Locale.ROOT, "%s из %s · %d%%",
                            formatMinutes(mark.minutes), formatMinutes(goal), pct));
                    progressText.setTextColor(mark.minutes >= goal && goal > 0 ? pal.green : pal.muted);
                    hoursInput.setText(String.valueOf(mark.minutes / 60));
                    minutesInput.setText(String.valueOf(mark.minutes % 60));
                    refresh();
                }
                dialogTimerHandler.postDelayed(this, 1000);
            }
        };
        dialogTimerHandler.postDelayed(dialogTimerTick, 1000);

        dialog.setOnDismissListener(d -> {
            if (dialogTimerHandler != null && dialogTimerTick != null) {
                dialogTimerHandler.removeCallbacks(dialogTimerTick);
            }
            dayDialog = null;
            refresh();
        });

        reloadUi[0].run();
        dayDialog = dialog;
        dialog.show();
    }

    private int chipTextColor(String status) {
        /* на цветной подложке читаемый контрастный текст */
        if (pal.light) return Color.WHITE;
        return pal.onText;
    }

    private GradientDrawable chipBackground(int color, boolean filled) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(20));
        if (filled) {
            d.setColor(color);
        } else {
            d.setColor(Color.TRANSPARENT);
            d.setStroke(dp(1), color);
        }
        return d;
    }

    /* ====================== Таймер: помощники ===================== */

    private static int totalSeconds(Store.TimerState t) {
        int total = t.baseSec;
        if (t.running && t.startedAt > 0) {
            total += (int) Math.max(0, (System.currentTimeMillis() - t.startedAt) / 1000);
        }
        return Math.max(0, total);
    }

    /** Записывает целые минуты счётчика в отметку дня (как в веб-версии). */
    private void commitTimer(Store.TimerState t) {
        if (t == null || !Store.isValidDay(t.day)) return;
        int total = totalSeconds(t);
        int committed = total / 60;
        int diff = committed - t.committedMin;
        if (diff != 0) {
            Store.Mark mark = store.markFor(t.day);
            store.writeMark(t.day, mark.minutes + diff, mark.status);
            t.committedMin = committed;
        }
        if (t.running && t.startedAt > 0) {
            /* база остаётся прежней, отсчёт продолжается */
        } else {
            t.baseSec = total;
        }
    }

    private void adjustTimer(String dayKey, int deltaSec, Runnable reload) {
        Store.TimerState t = store.timer();
        if (!Store.isValidDay(t.day) || !t.day.equals(dayKey)) {
            Toast.makeText(this, "Сначала запустите таймер", Toast.LENGTH_SHORT).show();
            return;
        }
        commitTimer(t);
        int total = Math.max(0, Math.min(24 * 60 * 60, totalSeconds(t) + deltaSec));
        t.baseSec = total;
        if (t.running) t.startedAt = System.currentTimeMillis();
        else t.startedAt = 0;

        int newCommitted = total / 60;
        int diff = newCommitted - t.committedMin;
        if (diff != 0) {
            Store.Mark mark = store.markFor(dayKey);
            store.writeMark(dayKey, mark.minutes + diff, mark.status);
            t.committedMin = newCommitted;
        }
        store.saveTimer(t);
        reload.run();
        refresh();
    }

    /* ====================== Меню и действия ======================= */

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Поиск");
        menu.getMenu().add("Статистика года");
        menu.getMenu().add("Тема");
        menu.getMenu().add("Экспорт JSON");
        menu.getMenu().add("Экспорт текста");
        menu.getMenu().add("Импорт");
        menu.getMenu().add("Очистить всё");
        menu.setOnMenuItemClickListener(item -> {
            String title = String.valueOf(item.getTitle());
            switch (title) {
                case "Поиск": showSearch(); return true;
                case "Статистика года": showYearStats(); return true;
                case "Тема": showThemePicker(); return true;
                case "Экспорт JSON": exportFile("json"); return true;
                case "Экспорт текста": exportFile("md"); return true;
                case "Импорт": importFile(); return true;
                case "Очистить всё": confirmClear(); return true;
                default: return false;
            }
        });
        menu.show();
    }

    private void shiftMonth(int delta) {
        viewMonth += delta;
        if (viewMonth < 0) {
            viewMonth = 11;
            viewYear--;
        } else if (viewMonth > 11) {
            viewMonth = 0;
            viewYear++;
        }
        refresh();
    }

    private void showThemePicker() {
        new AlertDialog.Builder(this)
                .setTitle("Тема оформления")
                .setItems(Palettes.NAMES, (dialog, which) -> {
                    store.setTheme(Palettes.IDS[which]);
                    pal = Palettes.get(store.theme());
                    applyPalette();
                    refresh();
                    if (dayDialog != null) dayDialog.dismiss();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showYearStats() {
        Store.DayStat stat = store.statsForYear(viewYear);
        int streak = store.streak(Calendar.getInstance());

        StringBuilder sb = new StringBuilder();
        sb.append("Серия: ").append(streak).append(" дн.\n");
        sb.append("Часов за год: ").append(formatMinutes(stat.totalMinutes)).append("\n");
        sb.append("Дней с отметкой: ").append(stat.markedDays).append("\n");
        sb.append("Заметок: ").append(stat.noteCount).append("\n");
        sb.append("Статусы: ● ").append(stat.green).append("   ● ")
                .append(stat.yellow).append("   ● ").append(stat.red).append("\n\n");
        for (int m = 0; m < 12; m++) {
            if (stat.monthMinutes[m] <= 0) continue;
            sb.append(MONTHS[m].charAt(0))
                    .append(MONTHS[m].substring(1).toLowerCase(Locale.ROOT))
                    .append(" — ").append(formatMinutes(stat.monthMinutes[m]))
                    .append(" (").append(stat.monthDays[m]).append(" дн.)\n");
        }

        new AlertDialog.Builder(this)
                .setTitle("СТАТИСТИКА " + viewYear)
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private void showSearch() {
        final EditText input = new EditText(this);
        input.setHint("Поиск по заметкам…");
        input.setHintTextColor(pal.muted);
        input.setTextColor(pal.text);

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad / 2, pad, pad / 2);
        box.addView(input, matchWrap());

        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        box.addView(results, matchWrap());

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("ПОИСК")
                .setView(box)
                .setNegativeButton("Закрыть", null)
                .create();

        input.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                results.removeAllViews();
                for (final Store.SearchHit hit : store.search(s.toString())) {
                    TextView row = new TextView(MainActivity.this);
                    String[] p = hit.day.split("-");
                    row.setText(p[2] + "." + p[1] + " — " + hit.snippet);
                    row.setTextColor(pal.text);
                    row.setTextSize(14);
                    row.setPadding(0, dp(8), 0, dp(8));
                    row.setOnClickListener(v -> {
                        viewYear = Integer.parseInt(p[0]);
                        viewMonth = Integer.parseInt(p[1]) - 1;
                        selectedDay = hit.day;
                        refresh();
                        dialog.dismiss();
                    });
                    results.addView(row, matchWrap());
                }
            }
        });

        dialog.show();
    }

    /* ====================== Экспорт / импорт ====================== */

    private void exportFile(final String kind) {
        new Thread(() -> {
            try {
                String name = "diary-" + viewYear
                        + Store.twoDigits(Calendar.getInstance().get(Calendar.MONTH) + 1)
                        + Store.twoDigits(Calendar.getInstance().get(Calendar.DAY_OF_MONTH))
                        + (kind.equals("json") ? ".json" : ".md");
                String content = kind.equals("json")
                        ? store.exportJson().toString(2)
                        : buildMarkdown();
                saveToDownloads(name, content, kind.equals("json") ? "application/json" : "text/markdown");
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Сохранено в «Загрузки/Дневник»", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Не удалось экспортировать", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private String buildMarkdown() {
        StringBuilder sb = new StringBuilder("# Дневник\n\n");
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        for (String day : store.allNotesByDay().keySet()) {
            Store.Mark mark = store.markFor(day);
            String[] p = day.split("-");
            sb.append("## ").append(Integer.parseInt(p[2])).append(" ")
                    .append(MONTHS_GEN[Integer.parseInt(p[1]) - 1]).append(" ").append(p[0]);
            if (mark.minutes > 0) sb.append(" · учёба ").append(formatMinutes(mark.minutes));
            if (!mark.status.isEmpty()) sb.append(" · статус: ").append(mark.status);
            sb.append("\n\n");
            for (Store.Note n : store.notesFor(day)) {
                sb.append(n.pinned ? "- ★ " : "- ").append(n.text).append("\n");
            }
            sb.append("\n");
        }
        if (sb.length() < 20) {
            sb.append("Заметок пока нет.\n");
        }
        return sb.toString();
    }

    private void saveToDownloads(String filename, String content, String mime) {
        String clean = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        if (clean.isEmpty() || clean.length() > 120) return;

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, clean);
        values.put(MediaStore.Downloads.MIME_TYPE, mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Дневник");
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        ContentResolver resolver = getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("нет uri");

        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new IllegalStateException("нет потока");
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        } catch (Exception e) {
            resolver.delete(uri, null, null);
            throw new IllegalStateException(e);
        }
    }

    private static final int IMPORT_REQUEST = 77;

    private void importFile() {
        new AlertDialog.Builder(this)
                .setTitle("Импорт")
                .setMessage("Выберите резервную копию JSON (из браузерной версии или этой). Текущие данные будут дополнены.")
                .setPositiveButton("Выбрать файл", (d, w) -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    startActivityForResult(intent, IMPORT_REQUEST);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != IMPORT_REQUEST || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("нет файла");
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, n);
                }
                String json = buffer.toString("UTF-8");
                new JSONObject(json); /* быстрая проверка, что это JSON */
                store.importJson(json);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Импорт завершён", Toast.LENGTH_SHORT).show();
                    refresh();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Не удалось импортировать файл", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Очистить все данные?")
                .setMessage("Заметки, отметки и таймер будут удалены без возможности восстановления. Настройки (цель и тема) сохранятся. Сначала сделайте экспорт.")
                .setPositiveButton("Удалить", (d, w) -> {
                    store.clearDiary();
                    refresh();
                    Toast.makeText(this, "Данные очищены", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /* ====================== Мелкие помощники ===================== */

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(pal.muted);
        t.setLetterSpacing(0.15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        lp.bottomMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView unitLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(pal.muted);
        t.setTextSize(14);
        t.setPadding(dp(4), 0, dp(10), 0);
        return t;
    }

    private EditText numberInput() {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setGravity(Gravity.CENTER);
        e.setTextColor(pal.text);
        e.setBackgroundTintList(android.content.res.ColorStateList.valueOf(pal.line));
        e.setSingleLine();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(52));
        e.setLayoutParams(lp);
        return e;
    }

    private Button smallAction(String text, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11);
        b.setTextColor(pal.muted);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private View space(int width) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(width, 1));
        return v;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapContent() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static int safeInt(String value, int min, int max) {
        try {
            int v = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, v));
        } catch (Exception e) {
            return min;
        }
    }

    static String formatMinutes(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        if (h > 0 && m > 0) return h + "ч " + m + "м";
        if (h > 0) return h + "ч";
        return m + "м";
    }

    static String formatClock(int seconds) {
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        if (h > 0) return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.ROOT, "%02d:%02d", m, s);
    }

    int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

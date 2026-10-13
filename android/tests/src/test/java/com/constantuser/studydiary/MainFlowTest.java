package com.constantuser.studydiary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ListView;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowDialog;

import java.util.Calendar;

/** Главные сценарии: навигация, пустые клетки, поиск, статистика, темы, очистка. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainFlowTest {

    private MainActivity a;
    private Store store;
    private String today;

    @Before
    public void setUp() throws Exception {
        a = Robolectric.buildActivity(MainActivity.class).setup().get();
        store = T.field(a, "store", Store.class);
        today = Store.formatDay(Calendar.getInstance());
    }

    private View content() {
        return a.getWindow().getDecorView();
    }

    private void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void openDay(String day) throws Exception {
        GridView grid = T.field(a, "daysGrid", GridView.class);
        String[] p = day.split("-");
        Calendar c = Calendar.getInstance();
        c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, 1);
        int dow = c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY;
        if (dow < 0) dow += 7;
        int pos = dow + Integer.parseInt(p[2]) - 1;
        View cell = grid.getAdapter().getView(pos, null, grid);
        grid.performItemClick(cell, pos, pos);
        idle();
    }

    @Test
    public void monthNavigation() throws Exception {
        TextView month = T.field(a, "monthLabel", TextView.class);
        String before = month.getText().toString();

        T.button(content(), "‹").performClick();
        String prev = month.getText().toString();
        assertTrue("месяц изменился", !prev.equals(before));
        assertTrue("заголовок месяца не пуст", prev.length() > 4);

        T.button(content(), "›").performClick();
        assertEquals("› возвращает обратно", before, month.getText().toString());

        /* кнопка СЕГОДНЯ */
        T.button(content(), "‹").performClick();
        T.button(content(), "СЕГОДНЯ").performClick();
        assertEquals(before, month.getText().toString());
    }

    @Test
    public void emptyCellClickOpensNothing() throws Exception {
        GridView grid = T.field(a, "daysGrid", GridView.class);
        boolean clickedEmpty = false;
        for (int pos = 0; pos < grid.getAdapter().getCount(); pos++) {
            View cell = grid.getAdapter().getView(pos, null, grid);
            Boolean hasDate = (Boolean) T.field(cell, "hasDate");
            if (!hasDate) {
                grid.performItemClick(cell, pos, pos);
                clickedEmpty = true;
                break;
            }
        }
        assertTrue("в месяце должна быть пустая клетка", clickedEmpty);
        idle();
        assertNull("по пустой клетке диалог не открывается",
                T.field(a, "dayDialog", Dialog.class));
    }

    @Test
    public void reopenDialogAfterClose() throws Exception {
        openDay(today);
        assertNotNull(T.field(a, "dayDialog", Dialog.class));
        Dialog first = T.field(a, "dayDialog", Dialog.class);
        first.dismiss();
        idle();
        assertNull("после закрытия ссылка очищается", T.field(a, "dayDialog", Dialog.class));

        openDay(today);
        Dialog second = T.field(a, "dayDialog", Dialog.class);
        assertNotNull("диалог открывается повторно", second);
        assertTrue(second.isShowing());
    }

    @Test
    public void searchFindsAndJumpsToDay() throws Exception {
        /* заметка в другом месяце — чтобы переход был виден и не совпал
           с исходно выбранным сегодняшним днём */
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MONTH, -1);
        c.set(Calendar.DAY_OF_MONTH, 10);
        String noteDay = Store.formatDay(c);
        store.addNote(noteDay, "апельсин на обед");

        T.method(a, "showSearch", new Class[0]);
        Dialog search = ShadowDialog.getLatestDialog();
        assertNotNull("диалог поиска открыт", search);

        android.widget.EditText input = T.byHint(T.content(search), "Поиск по заметкам…");
        assertNotNull(input);
        input.setText("апельсин");

        /* результат — обычный TextView; поле ввода содержит тот же текст,
           поэтому его исключаем */
        View hit = T.find(T.content(search), v -> v instanceof TextView
                && !(v instanceof android.widget.EditText)
                && ((TextView) v).getText().toString().contains("апельсин"));
        assertNotNull("результат поиска показан", hit);
        hit.performClick();
        idle();

        assertEquals("переход к дню заметки", noteDay, T.field(a, "selectedDay", String.class));
        assertFalse("диалог поиска закрыт", search.isShowing());
    }

    @Test
    public void yearStatsDialog() throws Exception {
        store.writeMark(today, 90, "green");
        T.method(a, "showYearStats", new Class[0]);

        AlertDialog alert = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("диалог статистики открыт", alert);
        View msg = T.find(alert.getWindow().getDecorView(), v -> v instanceof TextView
                && ((TextView) v).getText().toString().contains("Серия"));
        assertNotNull("текст статистики показан", msg);
        View hours = T.find(alert.getWindow().getDecorView(), v -> v instanceof TextView
                && ((TextView) v).getText().toString().contains("Часов за год"));
        assertNotNull("строка часов показана", hours);
        alert.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertFalse(alert.isShowing());
    }

    @Test
    public void themePickerAppliesPalette() throws Exception {
        T.method(a, "showThemePicker", new Class[0]);
        AlertDialog alert = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("диалог тем открыт", alert);
        ListView list = alert.getListView();
        assertNotNull(list);

        int oceanIndex = Palettes.indexOf("ocean");
        View item = list.getAdapter().getView(oceanIndex, null, list);
        list.performItemClick(item, oceanIndex, oceanIndex);
        idle();

        assertEquals("ocean", store.theme());
        Palettes pal = T.field(a, "pal", Palettes.class);
        assertEquals("палитра применена", Palettes.get("ocean").bg, pal.bg);
        assertNotNull(T.field(a, "monthLabel", TextView.class).getText());
    }

    @Test
    public void clearDiaryKeepsSettings() throws Exception {
        store.addNote(today, "ненужное");
        store.writeMark(today, 60, "red");
        store.setGoalMinutes(180);
        store.setTheme("forest");

        T.method(a, "confirmClear", new Class[0]);
        AlertDialog alert = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(alert);
        alert.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();

        assertEquals(0, store.noteCountAll());
        assertEquals(0, store.allMarks().size());
        assertEquals("цель сохраняется", 180, store.goalMinutes());
        assertEquals("тема сохраняется", "forest", store.theme());
    }

    @Test
    public void goalShownInDialog() throws Exception {
        store.setGoalMinutes(150);
        openDay(today);
        Dialog d = T.field(a, "dayDialog", Dialog.class);
        assertNotNull(d);
        View tv = T.find(T.content(d), v -> v instanceof TextView
                && ((TextView) v).getText().toString().contains("2ч 30м"));
        assertNotNull("прогресс показывает цель из настроек", tv);
    }
}

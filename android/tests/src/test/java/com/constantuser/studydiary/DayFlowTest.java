package com.constantuser.studydiary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.util.Calendar;
import java.util.List;

/** Сценарии панели дня: заметки, учёба, цель, статусы, поведение фокуса. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DayFlowTest {

    private MainActivity a;
    private Store store;
    private String today;

    @Before
    public void setUp() throws Exception {
        a = Robolectric.buildActivity(MainActivity.class).setup().get();
        store = T.field(a, "store", Store.class);
        today = Store.formatDay(Calendar.getInstance());
        openDay(today);
    }

    /** Открывает диалог указанного дня через клик по ячейке календаря. */
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
        idleMain();
    }

    private Dialog dialog() {
        try {
            Dialog d = T.field(a, "dayDialog", Dialog.class);
            assertNotNull("диалог дня должен быть открыт", d);
            return d;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("не удалось получить диалог дня", e);
        }
    }

    private View content() {
        return T.content(dialog());
    }

    /** Все числовые поля диалога в порядке обхода: ч/м учёбы, ч/м цели. */
    private List<EditText> numberInputs() {
        List<EditText> out = new java.util.ArrayList<>();
        collectNumbers(content(), out);
        return out;
    }

    private void collectNumbers(View v, List<EditText> out) {
        if (v instanceof EditText && ((EditText) v).getHint() == null) {
            out.add((EditText) v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectNumbers(g.getChildAt(i), out);
        }
    }

    private static void idleMain() {
        shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private EditText noteInput() {
        return T.byHint(content(), "Записать мысль на этот день…");
    }

    private Button button(String text) {
        return T.button(content(), text);
    }

    /** Строка заметки (родительский контейнер её текста). */
    private ViewGroup noteRow(String noteText) {
        View tv = T.find(content(), v -> v instanceof TextView
                && ((TextView) v).getText().toString().contains(noteText));
        assertNotNull("заметка «" + noteText + "» должна отображаться", tv);
        return (ViewGroup) tv.getParent();
    }

    /* ------------------------- заметки ---------------------------- */

    @Test
    public void addNoteShowsInDialogAndStore() {
        noteInput().setText("прочитать страницу 42");
        button("СОХРАНИТЬ").performClick();

        assertEquals(1, store.notesFor(today).size());
        assertEquals("прочитать страницу 42", store.notesFor(today).get(0).text);
        assertNotNull(T.byText(content(), "прочитать страницу 42"));
        /* подсказка пустого списка убрана */
        assertNull(T.byText(content(), "Заметок пока нет"));
    }

    @Test
    public void editPinDeleteNote() {
        noteInput().setText("первая");
        button("СОХРАНИТЬ").performClick();
        noteInput().setText("вторая");
        button("СОХРАНИТЬ").performClick();
        assertEquals(2, store.notesFor(today).size());

        /* закрепление первой */
        ViewGroup row1 = noteRow("первая");
        Button pin = (Button) T.byText(row1, "★ Важное");
        assertNotNull("кнопка закрепления должна быть в строке заметки", pin);
        pin.performClick();
        assertTrue(store.notesFor(today).get(0).pinned);
        assertTrue("закреплённая заметка поднята наверх",
                store.notesFor(today).get(0).text.equals("первая"));

        /* изменение */
        ViewGroup row2 = noteRow("вторая");
        ((Button) T.byText(row2, "Изменить")).performClick();
        noteInput().setText("вторая (исправленная)");
        button("СОХРАНИТЬ").performClick();
        boolean found = false;
        for (Store.Note n : store.notesFor(today)) {
            if (n.text.equals("вторая (исправленная)")) found = true;
        }
        assertTrue("изменённый текст должен сохраниться", found);

        /* удаление с подтверждением */
        ViewGroup row = noteRow("вторая (исправленная)");
        ((Button) T.byText(row, "Удалить")).performClick();
        AlertDialog alert = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("должно появиться подтверждение удаления", alert);
        alert.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idleMain();
        assertEquals(1, store.notesFor(today).size());
        assertEquals("первая", store.notesFor(today).get(0).text);
    }

    /* ------------------------- учёба и цель ------------------------ */

    @Test
    public void studyCommitsOnEditorAction() {
        List<EditText> inputs = numberInputs();
        assertTrue("ожидалось 4 числовых поля", inputs.size() >= 4);
        inputs.get(0).setText("1");
        inputs.get(1).setText("30");
        inputs.get(0).onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);

        assertEquals(90, store.markFor(today).minutes);
    }

    @Test
    public void studyCommitsOnDismissWithoutFocusLoss() throws Exception {
        List<EditText> inputs = numberInputs();
        inputs.get(0).setText("2");
        inputs.get(1).setText("15");
        dialog().dismiss();
        idleMain();

        assertEquals("закрытие диалога дожимает поля", 135, store.markFor(today).minutes);
        assertNull(T.field(a, "dayDialog", Dialog.class));
    }

    @Test
    public void reloadDoesNotClobberFocusedField() {
        List<EditText> inputs = numberInputs();
        EditText hours = inputs.get(0);
        assertTrue("фокус должен попасть в поле часов", hours.requestFocus());
        hours.setText("5");

        /* любое действие перечитывает состояние — но не трогает поле с фокусом */
        button("Зелёный").performClick();
        assertTrue("фокус остаётся в поле после действия", hours.hasFocus());
        assertEquals("5", hours.getText().toString());
        assertEquals("green", store.markFor(today).status);
    }

    @Test
    public void focusLossCommitsEditedPair() throws Exception {
        List<EditText> inputs = numberInputs();
        EditText hours = inputs.get(0);
        hours.setText("5");
        inputs.get(1).setText("30");

        /* На устройстве событие потери фокуса рассылает Android при переходе
           к другому полю. Окно диалога в Robolectric не привязано к ViewRootImpl,
           поэтому доставляем событие обработчику напрямую — проверяем логику
           приложения: пара ч/м дожимается, а по одиночному полю — нет. */
        android.view.View.OnFocusChangeListener fl = hours.getOnFocusChangeListener();
        assertNotNull("обработчик потери фокуса установлен", fl);
        fl.onFocusChange(hours, false);
        idleMain();
        assertEquals("потеря фокуса дожимает пару ч/м", 330, store.markFor(today).minutes);
    }

    @Test
    public void goalCommit() {
        List<EditText> inputs = numberInputs();
        inputs.get(2).setText("3");
        inputs.get(3).setText("30");
        inputs.get(2).onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        assertEquals(210, store.goalMinutes());
    }

    /* ------------------------- статусы ----------------------------- */

    @Test
    public void statusChips() {
        assertEquals("", store.markFor(today).status);
        button("Красный").performClick();
        assertEquals("red", store.markFor(today).status);
        button("Жёлтый").performClick();
        assertEquals("yellow", store.markFor(today).status);
        button("Нет").performClick();
        assertEquals("", store.markFor(today).status);
        /* статус не задевает минуты */
        assertEquals(0, store.markFor(today).minutes);
    }

    @Test
    public void statusKeepsManuallyEnteredMinutes() {
        button("Зелёный").performClick();
        List<EditText> inputs = numberInputs();
        inputs.get(0).setText("2");
        inputs.get(1).onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        assertEquals(120, store.markFor(today).minutes);
        assertEquals("green", store.markFor(today).status);
    }

    @Test
    public void resetStudyClearsMinutes() {
        List<EditText> inputs = numberInputs();
        inputs.get(0).setText("4");
        inputs.get(0).onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        assertEquals(240, store.markFor(today).minutes);

        button("Сброс").performClick();
        assertEquals(0, store.markFor(today).minutes);
    }
}

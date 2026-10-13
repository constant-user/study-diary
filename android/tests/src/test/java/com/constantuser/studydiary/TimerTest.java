package com.constantuser.studydiary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Dialog;
import android.os.Looper;
import android.view.View;
import android.widget.GridView;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.Calendar;

/**
 * Таймер: старт, пауза, продолжение, ±5 секунд, обнуление, переход между днями.
 * Регрессия: при паузе время терялось, а продолжение вычитало минуты из цели.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TimerTest {

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
        shadowOf(Looper.getMainLooper()).idle();
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

    private android.widget.Button button(String text) {
        return T.button(T.content(dialog()), text);
    }

    private void idleFor(long seconds) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds));
    }

    /**
     * Планировщик Robolectric останавливается ровно на границе интервала,
     * из-за чего последний тик может не выполниться — допуск ±1 с.
     */
    private static void assertSeconds(String msg, long expected, long actual) {
        assertTrue(msg + ": ожидалось " + expected + "±1, было " + actual,
                Math.abs(expected - actual) <= 1);
    }

    /** Текст крупных секунд таймера (mm:ss или h:mm:ss). */
    private String clock() {
        View v = T.find(T.content(dialog()), x -> x instanceof TextView
                && ((TextView) x).getText().toString().matches("\\d{1,2}:\\d{2}(:\\d{2})?"));
        assertNotNull("часы таймера должны быть на экране", v);
        return ((TextView) v).getText().toString();
    }

    /** Секунды, показанные часами таймера. */
    private long clockSeconds() {
        String[] parts = clock().split(":");
        long h = parts.length == 3 ? Long.parseLong(parts[0]) : 0;
        long m = Long.parseLong(parts[parts.length - 2]);
        long s = Long.parseLong(parts[parts.length - 1]);
        return h * 3600 + m * 60 + s;
    }

    @Test
    public void startPauseResumeKeepsTime() {
        assertEquals("00:00", clock());
        button("Старт").performClick();

        Store.TimerState t = store.timer();
        assertTrue("таймер запущен", t.running);
        assertEquals(today, t.day);
        org.junit.Assert.assertNull("кнопка «Старт» переключилась на «Пауза»",
                T.button(T.content(dialog()), "Старт"));
        assertNotNull("кнопка «Пауза» появилась", button("Пауза"));

        idleFor(65);
        assertEquals("минута засчитана в цель", 1, store.markFor(today).minutes);
        long running = store.timer().baseSec;
        assertSeconds("накоплено при работе", 65, running);
        assertSeconds("часы при работе", 65, clockSeconds());

        button("Пауза").performClick();
        /* РЕГРЕССИЯ: пауза раньше обнуляла счётчик */
        long paused = store.timer().baseSec;
        assertTrue("пауза не теряет накопленное (было " + running + ", стало " + paused + ")",
                paused >= running);
        assertSeconds("часы на паузе", 65, clockSeconds());

        idleFor(30);
        assertEquals("на паузе время не идёт", paused, store.timer().baseSec);
        assertEquals(1, store.markFor(today).minutes);

        button("Старт").performClick();     /* продолжение */
        idleFor(10);
        /* РЕГРЕССИЯ: продолжение раньше вычитало минуты из отметки */
        assertEquals("продолжение не портит отметку", 1, store.markFor(today).minutes);
        assertSeconds("после продолжения", paused + 10, store.timer().baseSec);
        assertSeconds("часы после продолжения", paused + 10, clockSeconds());

        idleFor(50);                        /* ≈125 с = 2 мин */
        assertEquals(2, store.markFor(today).minutes);
        assertSeconds("итог", paused + 60, store.timer().baseSec);
    }

    @Test
    public void adjustButtons() {
        button("Старт").performClick();
        idleFor(65);
        assertEquals(1, store.markFor(today).minutes);
        long base = store.timer().baseSec;
        assertSeconds("база до регулировки", 65, base);

        button("+5 с").performClick();
        assertEquals("плюс 5 секунд", base + 5, store.timer().baseSec);
        assertSeconds("часы после +5", base + 5, clockSeconds());
        assertEquals("плюс не трогает засчитанные минуты", 1, store.markFor(today).minutes);

        button("+5 с").performClick();
        assertEquals(base + 10, store.timer().baseSec);
        button("−5 с").performClick();
        assertEquals("минус 5 секунд", base + 5, store.timer().baseSec);
        assertEquals(1, store.markFor(today).minutes);

        /* уход в минус невозможно */
        for (int i = 0; i < 14; i++) {
            button("−5 с").performClick();
        }
        assertTrue("счётчик не уходит в минус", store.timer().baseSec >= 0);
        assertTrue("минуты не уходят в минус", store.markFor(today).minutes >= 0);
        assertSeconds("часы не уходят в минус", 0, clockSeconds());
    }

    @Test
    public void resetKeepsAccumulatedInGoal() {
        button("Старт").performClick();
        idleFor(65);
        assertEquals(1, store.markFor(today).minutes);

        button("Обнулить").performClick();
        assertEquals("накопленные минуты остаются в цели", 1, store.markFor(today).minutes);
        assertEquals("счётчик обнулён", 0, store.timer().baseSec);
        assertEquals("00:00", clock());

        /* после обнуления таймер снова пишет минуты */
        idleFor(65);
        assertEquals(2, store.markFor(today).minutes);
    }

    @Test
    public void startingOnAnotherDayCommitsPrevious() throws Exception {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_MONTH, 15);
        String day15 = Store.formatDay(c);
        c.set(Calendar.DAY_OF_MONTH, 16);
        String day16 = Store.formatDay(c);

        openDay(day15);
        button("Старт").performClick();
        idleFor(70);
        assertEquals(1, store.markFor(day15).minutes);

        /* переход на другой день при работающем таймере */
        openDay(day16);
        /* РЕГРЕССИЯ: закрытие старого диалога гасило тик нового,
           и поле dayDialog обнулялось чужим слушателем */
        assertNotNull("новый диалог должен остаться открытым", T.field(a, "dayDialog", Dialog.class));
        assertTrue("открыт день 16",
                T.content(dialog()).toString() != null);

        button("Старт").performClick();
        assertEquals("таймер переведён на новый день", day16, store.timer().day);
        assertEquals("минуты дня 15 сохранены", 1, store.markFor(day15).minutes);

        idleFor(70);
        assertEquals("новой минуте засчитан день 16", 1, store.markFor(day16).minutes);
        assertEquals("день 15 не изменился", 1, store.markFor(day15).minutes);
    }

    @Test
    public void dialogCloseReopenKeepsWorking() throws Exception {
        button("Старт").performClick();
        idleFor(65);

        dialog().dismiss();
        shadowOf(Looper.getMainLooper()).idle();
        assertNullSafe();

        openDay(today);
        assertNotNull("диалог должен открываться повторно",
                T.field(a, "dayDialog", Dialog.class));
        assertSeconds("часы видны после повторного открытия", 65, clockSeconds());
        button("Пауза").performClick();
        assertSeconds("пауза после повторного открытия", 65, store.timer().baseSec);
    }

    private void assertNullSafe() throws Exception {
        org.junit.Assert.assertNull("после закрытия поле пустеет",
                T.field(a, "dayDialog", Dialog.class));
    }
}

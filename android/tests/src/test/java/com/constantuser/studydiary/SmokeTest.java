package com.constantuser.studydiary;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Dialog;
import android.view.View;
import android.widget.GridView;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Calendar;

/**
 * Дымовой тест: приложение запускается, календарь кликабелен,
 * день открывается в диалоге. Регрессия на главный баг первой сборки,
 * когда клик по дню ничего не делал.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SmokeTest {

    /** Позиция дня месяца в сетке 42 ячеек (понедельник = 0). */
    static int positionOfDay(Calendar c) {
        int dow = c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY;
        if (dow < 0) dow += 7;
        return dow + c.get(Calendar.DAY_OF_MONTH) - 1;
    }

    @Test
    public void launchesWithCalendar() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();

        GridView grid = T.field(a, "daysGrid", GridView.class);
        assertNotNull("сетка календаря должна существовать", grid);
        assertTrue("ячеек должно быть 42", grid.getAdapter().getCount() == 42);

        TextView month = T.field(a, "monthLabel", TextView.class);
        assertTrue("заголовок месяца непуст", month.getText().length() > 4);
    }

    @Test
    public void tapOnDayOpensDialog() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        GridView grid = T.field(a, "daysGrid", GridView.class);

        int pos = positionOfDay(Calendar.getInstance());
        View cell = grid.getAdapter().getView(pos, null, grid);
        assertTrue("клик по дню должен обрабатываться", grid.performItemClick(cell, pos, pos));

        Dialog dlg = T.field(a, "dayDialog", Dialog.class);
        assertNotNull("диалог дня должен открыться", dlg);
        assertTrue("диалог должен быть видимым", dlg.isShowing());
    }
}

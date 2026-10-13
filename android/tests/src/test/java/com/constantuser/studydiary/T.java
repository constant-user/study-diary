package com.constantuser.studydiary;

import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.function.Predicate;

/** Помощники для тестов: рефлексия к полям Activity и поиск виджетов. */
final class T {

    private T() {
    }

    /** Достаёт приватное поле, поднимаясь по иерархии суперклассов. */
    static Object field(Object o, String name) throws Exception {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    @SuppressWarnings("unchecked")
    static <R> R field(Object o, String name, Class<R> type) throws Exception {
        Object v = field(o, name);
        if (v != null && !type.isInstance(v)) {
            throw new ClassCastException(name + ": ожидалось " + type.getName());
        }
        return (R) v;
    }

    /** Вызывает приватный метод (например, private-хелперы Activity). */
    static Object method(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(o, args);
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }

    /** Первый виджет, удовлетворяющий предикату, включая корень. */
    static View find(View root, Predicate<View> pred) {
        if (root == null) return null;
        if (pred.test(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = find(g.getChildAt(i), pred);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** Виджет с точным текстом (Button или TextView). */
    static View byText(View root, String text) {
        return find(root, v -> v instanceof TextView
                && text.contentEquals(((TextView) v).getText()));
    }

    static Button button(View root, String text) {
        return (Button) byText(root, text);
    }

    static EditText byHint(View root, String hint) {
        return (EditText) find(root,
                v -> v instanceof EditText && hint.equals(((EditText) v).getHint()));
    }

    static TextView textView(View root, Predicate<TextView> pred) {
        return (TextView) find(root, v -> v instanceof TextView && pred.test((TextView) v));
    }

    /** Корневое содержимое диалога. */
    static View content(Dialog dialog) {
        return dialog.getWindow().getDecorView();
    }
}

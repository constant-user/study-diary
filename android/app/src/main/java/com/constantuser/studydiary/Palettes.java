package com.constantuser.studydiary;

/**
 * Девять палитр дневника — те же, что в веб-версии.
 * Каждая палитра задаёт полный набор цветов интерфейса.
 */
final class Palettes {

    static final String[] IDS = {
            "mono", "night", "ocean", "forest", "sunset",
            "sakura", "lavender", "paper", "mist"
    };

    static final String[] NAMES = {
            "Чёрно-белый", "Ночь", "Океан", "Лес", "Закат",
            "Сакура", "Лаванда", "Бумага", "Мгла"
    };

    final int bg;          // фон приложения
    final int surface;     // панели и карточки
    final int surface2;    // поля ввода
    final int text;        // основной текст
    final int muted;       // приглушённый текст
    final int line;        // рамки
    final int lineStrong;  // акцентные рамки
    final int accent;      // кнопки «Старт», «Сохранить», полоски
    final int onAccent;    // текст поверх акцента
    final int green;       // статусы дня
    final int yellow;
    final int red;
    final int onText;      // текст поверх заливки цветом text (инверсия)
    final boolean light;   // светлая ли тема

    private Palettes(int bg, int surface, int surface2, int text, int muted,
                     int line, int lineStrong, int accent, int onAccent,
                     int green, int yellow, int red, int onText, boolean light) {
        this.bg = bg;
        this.surface = surface;
        this.surface2 = surface2;
        this.text = text;
        this.muted = muted;
        this.line = line;
        this.lineStrong = lineStrong;
        this.accent = accent;
        this.onAccent = onAccent;
        this.green = green;
        this.yellow = yellow;
        this.red = red;
        this.onText = onText;
        this.light = light;
    }

    static Palettes get(String id) {
        int i = indexOf(id);
        return all()[i];
    }

    static int indexOf(String id) {
        if (id != null) {
            for (int i = 0; i < IDS.length; i++) {
                if (IDS[i].equals(id)) return i;
            }
        }
        return 0; // mono по умолчанию
    }

    static Palettes[] all() {
        return new Palettes[]{
                /* mono — классический чёрно-белый */
                new Palettes(0xFF000000, 0xFF070707, 0xFF0D0D0D, 0xFFF4F4F4, 0xFF6F6F6F,
                        0xFF242424, 0xFF3A3A3A, 0xFFF4F4F4, 0xFF000000,
                        0xFF35D07F, 0xFFF2C53D, 0xFFF0554D, 0xFF000000, false),
                /* night */
                new Palettes(0xFF05070F, 0xFF080B16, 0xFF0D1220, 0xFFE8ECF5, 0xFF707A90,
                        0xFF1D2436, 0xFF2E3952, 0xFF7AA2FF, 0xFF060913,
                        0xFF4ADE80, 0xFFFBBF24, 0xFFF87171, 0xFF05070F, false),
                /* ocean */
                new Palettes(0xFF03141A, 0xFF061C24, 0xFF0A2530, 0xFFDFF5F7, 0xFF6D8E95,
                        0xFF12313C, 0xFF1E4653, 0xFF38C7C0, 0xFF03212A,
                        0xFF2EE6A8, 0xFFFFD166, 0xFFFF6B6B, 0xFF03141A, false),
                /* forest */
                new Palettes(0xFF06120B, 0xFF0A1A10, 0xFF0E2416, 0xFFE7F5EA, 0xFF6F8F79,
                        0xFF17351F, 0xFF235032, 0xFF5FBF72, 0xFF04200D,
                        0xFF42D67C, 0xFFE9C94E, 0xFFEF6A5F, 0xFF06120B, false),
                /* sunset */
                new Palettes(0xFF160A06, 0xFF1E0F09, 0xFF27150D, 0xFFFFE9D6, 0xFF9C7A63,
                        0xFF3A2015, 0xFF55301F, 0xFFFF8A3D, 0xFF2A1104,
                        0xFF7BD97A, 0xFFFFB43D, 0xFFFF5F4D, 0xFF160A06, false),
                /* sakura */
                new Palettes(0xFF140810, 0xFF1C0D17, 0xFF26121F, 0xFFFFE7F1, 0xFF9C7386,
                        0xFF3A1C2C, 0xFF552A3F, 0xFFFF8FB8, 0xFF33101F,
                        0xFF5FD98A, 0xFFF2C94C, 0xFFFF6F91, 0xFF140810, false),
                /* lavender */
                new Palettes(0xFF0C0818, 0xFF120D22, 0xFF191330, 0xFFEFE9FF, 0xFF8778AD,
                        0xFF281F45, 0xFF3A2E63, 0xFFB39AFF, 0xFF170F2E,
                        0xFF56D9A3, 0xFFF5CD5F, 0xFFFF7A8A, 0xFF0C0818, false),
                /* paper — светлый */
                new Palettes(0xFFF4EFE4, 0xFFFBF7EE, 0xFFEEE7D8, 0xFF221D15, 0xFF8A7F6A,
                        0xFFDDD3BD, 0xFFC4B795, 0xFFB96A2E, 0xFFFDF8EF,
                        0xFF1F9D57, 0xFFC9991A, 0xFFD63B32, 0xFFF6F1E6, true),
                /* mist — светлый */
                new Palettes(0xFFE9EDF2, 0xFFF5F7FA, 0xFFDFE5EC, 0xFF1D232B, 0xFF78828F,
                        0xFFCFD7E0, 0xFFAEB8C4, 0xFF2F6FB0, 0xFFF2F6FA,
                        0xFF17864A, 0xFFB07D0A, 0xFFC5352C, 0xFFEEF2F6, true)
        };
    }

    int statusColor(String status) {
        if ("green".equals(status)) return green;
        if ("yellow".equals(status)) return yellow;
        if ("red".equals(status)) return red;
        return 0;
    }
}

package app.polaris.planner;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import app.polaris.planner.PlannerStore.Entry;
import app.polaris.planner.PlannerStore.Line;
import app.polaris.planner.PlannerStore.Note;
import app.polaris.planner.PlannerStore.Repeat;
import app.polaris.planner.PlannerStore.Template;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = 0xfff2f3f9;
    private static final int PAPER = 0xfffafaff;
    private static final int WHITE = 0xffffffff;
    private static final int INK = 0xff202037;
    private static final int MUTED = 0xff626479;
    private static final int LINE = 0xffe7e6f0;
    private static final int PURPLE = 0xff6946e1;
    private static final int DEEP = 0xff5032ba;
    private static final int SOFT = 0xffeee9ff;
    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM", RU);
    private static final DateTimeFormatter TODAY = DateTimeFormatter.ofPattern("EEEE, d MMMM", RU);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("LLLL yyyy", RU);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final String[] CATEGORIES = {"Без категории", "Личное", "Работа", "Учёба"};
    private static final String[] WEEKDAYS = {"Пн","Вт","Ср","Чт","Пт","Сб","Вс"};
    private static final Comparator<Entry> BY_TIME = Comparator.comparing(
            entry -> entry.time, Comparator.nullsLast(Comparator.naturalOrder()));
    private static final int NOTIFICATION_REQUEST = 4;

    private enum Page {
        TODAY("Сегодня", "☀"), CALENDAR("Календарь", "▦"), NOTES("Заметки", "▤");
        final String label;
        final String icon;
        Page(String label, String icon) { this.label = label; this.icon = icon; }
    }

    private enum TaskFilter {
        ALL("Все"), OPEN("В работе"), DONE("Готово");
        final String label;
        TaskFilter(String label) { this.label = label; }
        boolean matches(Entry task) {
            return this == ALL || (this == DONE) == task.done;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable clock = new Runnable() {
        @Override public void run() {
            refreshExternalChanges();
            updateDate();
            handler.postDelayed(this, 15000);
        }
    };
    private PlannerStore store;
    private LinearLayout root;
    private LinearLayout body;
    private LinearLayout navigation;
    private ScrollView scroll;
    private Page page = Page.TODAY;
    private LocalDate today = LocalDate.now();
    private LocalDate selected = today;
    private TaskFilter filter = TaskFilter.ALL;
    private boolean agenda;
    private Template noteFilter;
    private final Set<String> expandedNotes = new HashSet<>();
    private String noteQuery = "";
    private String quickDraft = "";

    @Override public void onCreate(Bundle previous) {
        super.onCreate(previous);
        if (previous != null) {
            page = Page.valueOf(previous.getString("page", Page.TODAY.name()));
            selected = LocalDate.parse(previous.getString("date", today.toString()));
            agenda = previous.getBoolean("agenda");
            quickDraft = previous.getString("quick", "");
            noteQuery = previous.getString("query", "");
            ArrayList<String> expanded = previous.getStringArrayList("expanded_notes");
            if (expanded != null) expandedNotes.addAll(expanded);
        }
        store = new PlannerStore(this);
        if (PlannerStore.rollover(store.entries, today) && store.save()) TodayWidget.refresh(this);
        buildShell();
        render(false);
        openFromNotification(getIntent());
    }

    @Override protected void onSaveInstanceState(Bundle output) {
        output.putString("page", page.name());
        output.putString("date", selected.toString());
        output.putBoolean("agenda", agenda);
        output.putString("quick", quickDraft);
        output.putString("query", noteQuery);
        output.putStringArrayList("expanded_notes", new ArrayList<>(expandedNotes));
        super.onSaveInstanceState(output);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshExternalChanges();
        updateDate();
        schedule();
        TodayWidget.refresh(this);
        if (page == Page.CALENDAR) render(true);
        handler.postDelayed(clock, 15000);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(clock);
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        refreshExternalChanges();
        openFromNotification(intent);
    }

    private void openFromNotification(Intent intent) {
        String id = intent.getStringExtra("entry");
        if (id == null) return;
        intent.removeExtra("entry");
        for (Entry entry : store.entries) {
            if (entry.id.equals(id)) {
                page = entry.event || !entry.date.equals(today) ? Page.CALENDAR : Page.TODAY;
                selected = entry.date;
                render(false);
                root.post(() -> editEntry(entry.event, entry, entry.date));
                return;
            }
        }
    }

    private void updateDate() {
        LocalDate now = LocalDate.now();
        if (now.equals(today)) return;
        if (selected.equals(today)) selected = now;
        today = now;
        if (PlannerStore.rollover(store.entries, today) && store.save()) TodayWidget.refresh(this);
        schedule();
        render(true);
    }

    private void schedule() {
        PlannerAlarm.reschedule(this, new PlannerStore(this));
    }

    private void persist() {
        if (store.save()) {
            TodayWidget.refresh(this);
            schedule();
        }
        else announce("Не удалось сохранить изменения");
    }

    private void refreshExternalChanges() {
        if (store.isCurrent()) return;
        store = new PlannerStore(this);
        render(true);
    }

    private Entry findEntry(String id) {
        for (Entry entry : store.entries) if (entry.id.equals(id)) return entry;
        return null;
    }

    private Note findNote(String id) {
        for (Note note : store.notes) if (note.id.equals(id)) return note;
        return null;
    }

    private void announce(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void buildShell() {
        root = column();
        root.setBackgroundColor(BACKGROUND);
        LinearLayout brand = row();
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(dp(17), 0, dp(17), 0);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(31), dp(31)));
        TextView name = text("Полярис", 18, INK, true);
        LinearLayout.LayoutParams nameParams = wrap();
        nameParams.leftMargin = dp(9);
        brand.addView(name, nameParams);
        root.addView(brand, new LinearLayout.LayoutParams(-1, dp(52)));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        body = column();
        body.setFocusableInTouchMode(true);
        int side = Math.max(14, (getResources().getConfiguration().screenWidthDp - 650) / 2);
        body.setPadding(dp(side), dp(7), dp(side), dp(18));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        navigation = row();
        navigation.setPadding(dp(8), dp(4), dp(8), dp(4));
        navigation.setBackgroundColor(PAPER);
        navigation.setElevation(dp(3));
        root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.post(() -> {
                WindowInsetsController controller = getWindow().getInsetsController();
                int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS |
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                if (controller != null) controller.setSystemBarsAppearance(lightBars, lightBars);
            });
            root.setOnApplyWindowInsetsListener((view,insets) -> {
                android.graphics.Insets padding = insets.getInsets(WindowInsets.Type.systemBars() |
                        WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(padding.left, padding.top, padding.right, padding.bottom);
                navigation.setVisibility(insets.isVisible(WindowInsets.Type.ime()) ? View.GONE : View.VISIBLE);
                return insets;
            });
            root.requestApplyInsets();
        }
    }

    private void render(boolean keepScroll) {
        int y = keepScroll ? scroll.getScrollY() : 0;
        body.removeAllViews();
        navigation.removeAllViews();
        for (Page option : Page.values()) {
            boolean active = option == page;
            Button button = button(option.icon + "\n" + option.label, active ? SOFT : PAPER,
                    active ? DEEP : MUTED, () -> {
                        dismissKeyboard();
                        page = option;
                        render(false);
                    });
            button.setTextSize(13);
            button.setContentDescription(option.label);
            button.setSelected(active);
            navigation.addView(button, new LinearLayout.LayoutParams(0, dp(54), 1));
        }
        switch (page) {
            case CALENDAR: calendarPage(); break;
            case NOTES: notesPage(); break;
            default: todayPage();
        }
        body.requestFocus();
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private void dismissKeyboard() {
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root.getWindowToken(), 0);
    }

    private void header(String title, String subtitle, String action, Runnable onAction) {
        LinearLayout group = column();
        group.addView(text(subtitle, 14, MUTED, true));
        TextView heading = text(title, 34, INK, true);
        heading.setLetterSpacing(-0.055f);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        group.addView(heading);
        Button add = button("+ " + action, PURPLE, WHITE, onAction);
        if (getResources().getConfiguration().screenWidthDp < 365) {
            add.setTextSize(13);
            heading.setTextSize(29);
        }
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(group, weight(1));
        header.addView(add, wrap());
        addBlock(body, header, 14);
    }

    private void todayPage() {
        header("Сегодня", capital(TODAY.format(today)), "Новое дело", () -> editEntry(false, null, today));
        ArrayList<Entry> tasks = items(today, false);
        int completed = 0;
        for (Entry task : tasks) if (task.done) completed++;
        addBlock(body, hero(completed, tasks.size()), 13);

        LinearLayout schedule = card();
        LinearLayout modes = row();
        modes.addView(choice("Дела", !agenda, () -> { agenda = false; render(true); }), weight(1));
        modes.addView(choice("Лента дня", agenda, () -> { agenda = true; render(true); }), weight(1));
        addBlock(schedule, modes, 9);
        LinearLayout quick = row();
        quick.setGravity(Gravity.CENTER_VERTICAL);
        EditText field = input("Например, позвонить в 18:30", quickDraft, false, 154);
        field.setContentDescription("Быстро добавить дело");
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        track(field, value -> quickDraft = value);
        Runnable add = () -> {
            Entry task = PlannerStore.quickTask(value(field), today);
            if (task.title.isEmpty()) { field.setError("Введи название"); return; }
            if (task.title.length() > 140) { field.setError("Сократи название до 140 символов"); return; }
            store.entries.add(0, task);
            quickDraft = "";
            filter = TaskFilter.ALL;
            persist();
            dismissKeyboard();
            render(true);
        };
        field.setOnEditorActionListener((view, action, key) -> {
            if (action != EditorInfo.IME_ACTION_DONE &&
                    (key == null || key.getKeyCode() != android.view.KeyEvent.KEYCODE_ENTER ||
                            key.getAction() != android.view.KeyEvent.ACTION_UP)) return false;
            add.run();
            return true;
        });
        quick.addView(field, weight(1));
        Button plus = button("+", PURPLE, WHITE, add);
        plus.setContentDescription("Добавить дело на сегодня");
        plus.setTextSize(24);
        LinearLayout.LayoutParams plusParams = new LinearLayout.LayoutParams(dp(46), dp(46));
        plusParams.leftMargin = dp(7);
        quick.addView(plus, plusParams);
        addBlock(schedule, quick, 9);

        if (agenda) {
            ArrayList<Entry> timeline = new ArrayList<>(tasks);
            timeline.addAll(items(today, true));
            timeline.sort(BY_TIME);
            if (timeline.isEmpty()) empty(schedule, "На сегодня ничего нет");
            for (Entry entry : timeline) addEntryRow(schedule, entry, false);
        } else {
            LinearLayout filters = row();
            for (TaskFilter option : TaskFilter.values()) {
                filters.addView(choice(option.label, filter == option, () -> { filter = option; render(true); }), weight(1));
            }
            addBlock(schedule, filters, 6);
            boolean listed = false;
            for (Entry task : tasks) {
                if (!filter.matches(task)) continue;
                listed = true;
                addEntryRow(schedule, task, false);
            }
            if (!listed) empty(schedule, tasks.isEmpty() ? "Дел на сегодня пока нет" : "Здесь пока пусто");
        }
        addBlock(body, schedule, 13);

        LinearLayout upcoming = card();
        addHeading(upcoming, "Ближайшие события");
        ArrayList<Entry> events = new ArrayList<>();
        for (Entry item : store.entries) if (item.event && !item.date.isBefore(today)) events.add(item);
        events.sort(Comparator.comparing((Entry entry) -> entry.date).thenComparing(BY_TIME));
        if (events.isEmpty()) empty(upcoming, "Событий пока нет");
        for (int i = 0; i < Math.min(3, events.size()); i++) addEntryRow(upcoming, events.get(i), true);
        upcoming.addView(button("+ Добавить событие", SOFT, DEEP, () -> editEntry(true, null, today)), wrap());
        addBlock(body, upcoming, 13);

        LinearLayout notes = card();
        addHeading(notes, "Заметки");
        ArrayList<Note> recent = orderedNotes();
        if (recent.isEmpty()) empty(notes, "Заметок пока нет");
        for (int i = 0; i < Math.min(2, recent.size()); i++) {
            Note note = recent.get(i);
            Button link = button(note.type.icon + "  " + note.title, WHITE, INK, () -> editNote(note, note.type));
            link.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            addBlock(notes, link, 3);
        }
        notes.addView(button("Все заметки", SOFT, DEEP, () -> {
            page = Page.NOTES;
            render(false);
        }), wrap());
        addBlock(body, notes, 0);
    }

    private FrameLayout hero(int completed, int total) {
        FrameLayout tile = new FrameLayout(this);
        tile.setMinimumHeight(dp(154));
        tile.setBackground(shape(SOFT, 23, 0));
        tile.setClipToOutline(true);
        ImageView image = new ImageView(this);
        image.setImageResource(R.drawable.crystal);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tile.addView(image, new FrameLayout.LayoutParams(-1, -1));
        View veil = new View(this);
        veil.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xffefedff,0xddefedff,0x18efedff}));
        tile.addView(veil, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout content = column();
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(20), dp(22), dp(20), dp(22));
        content.addView(text("Дела на сегодня", 14, DEEP, true));
        addBlock(content, text("Выполнено " + completed + " из " + total, 23, INK, true), 11);
        int ratio = total == 0 ? 0 : Math.round(100f * completed / total);
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(ratio);
        progress.setProgressTintList(ColorStateList.valueOf(PURPLE));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0xffcbc0ee));
        progress.setContentDescription("Доля выполненных дел");
        line.addView(progress, new LinearLayout.LayoutParams(dp(118), dp(13)));
        TextView label = text("   " + ratio + "%", 14, DEEP, true);
        line.addView(label);
        content.addView(line);
        tile.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));
        return tile;
    }

    private ArrayList<Entry> items(LocalDate day, boolean events) {
        if (!events) return PlannerStore.tasksForDay(store.entries, day);
        ArrayList<Entry> result = new ArrayList<>();
        for (Entry entry : store.entries) if (entry.event == events && entry.date.equals(day)) result.add(entry);
        result.sort(BY_TIME);
        return result;
    }

    private void addEntryRow(LinearLayout parent, Entry entry, boolean showDate) {
        LinearLayout item = row();
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setMinimumHeight(dp(58));
        item.setPadding(0, dp(5), 0, dp(5));
        if (entry.event) {
            TextView glyph = text("▦", 21, DEEP, true);
            glyph.setGravity(Gravity.CENTER);
            glyph.setBackground(shape(SOFT, 12, 0));
            item.addView(glyph, new LinearLayout.LayoutParams(dp(39), dp(39)));
            item.setBackground(touch(WHITE, 12));
            item.setOnClickListener(view -> editEntry(true, entry, entry.date));
        } else {
            CheckBox complete = new CheckBox(this);
            complete.setButtonTintList(checkColors());
            complete.setChecked(entry.done);
            complete.setContentDescription(entry.title);
            complete.setOnCheckedChangeListener((view, checked) -> {
                PlannerStore.toggle(store.entries, entry, today);
                PlannerAlarm.clearNotification(this, entry.id);
                persist();
                render(true);
            });
            item.addView(complete, new LinearLayout.LayoutParams(dp(43), dp(44)));
        }
        LinearLayout label = column();
        label.setPadding(dp(7), 0, dp(4), 0);
        TextView title = text(entry.title, 16, entry.done ? MUTED : INK, true);
        if (entry.done) title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        label.addView(title);
        String detail;
        if (entry.event) {
            detail = (showDate ? DAY.format(entry.date) + " · " : "") +
                    (entry.time == null ? "Весь день" : CLOCK.format(entry.time));
            if (!entry.detail.isEmpty()) detail += " · " + entry.detail;
        } else {
            detail = entry.category;
            if (entry.repeat != Repeat.NONE) detail += (detail.isEmpty() ? "" : " · ") + entry.repeat.label;
        }
        if (!detail.isEmpty()) label.addView(text(detail, 13, MUTED, false));
        if (!entry.event && !entry.done && entry.date.equals(today)) {
            Button postpone = button("На завтра", SOFT, DEEP, () -> {
                entry.date = today.plusDays(1);
                entry.edited = !entry.previous.isEmpty();
                PlannerAlarm.clearNotification(this, entry.id);
                persist();
                render(true);
                announce("Дело перенесено на завтра");
            });
            postpone.setTextSize(12);
            postpone.setMinimumHeight(dp(41));
            label.addView(postpone, wrap());
        }
        item.addView(label, weight(1));
        if (!entry.event && entry.time != null) item.addView(text(CLOCK.format(entry.time), 13, MUTED, true));
        if (!entry.event) {
            Button edit = button("⋯", WHITE, MUTED, () -> editEntry(false, entry, entry.date));
            edit.setContentDescription("Изменить дело: " + entry.title);
            edit.setPadding(0, 0, 0, 0);
            item.addView(edit, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
        parent.addView(item, new LinearLayout.LayoutParams(-1, -2));
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        parent.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private void calendarPage() {
        header("Календарь", "События и дела", "Новое событие", () -> editEntry(true, null, selected));
        LinearLayout calendar = card();
        LinearLayout control = row();
        control.setGravity(Gravity.CENTER_VERTICAL);
        YearMonth month = YearMonth.from(selected);
        control.addView(text(capital(MONTH.format(month)), 18, INK, true), weight(1));
        control.addView(button("Сегодня", WHITE, DEEP, () -> { selected = today; render(false); }), wrap());
        for (int offset : new int[]{-1, 1}) {
            Button arrow = button(offset == -1 ? "‹" : "›", WHITE, DEEP, () -> {
                selected = month.plusMonths(offset).atDay(1);
                render(true);
            });
            arrow.setContentDescription(offset == -1 ? "Предыдущий месяц" : "Следующий месяц");
            arrow.setPadding(0, 0, 0, 0);
            control.addView(arrow, new LinearLayout.LayoutParams(dp(36), dp(44)));
        }
        addBlock(calendar, control, 6);
        LinearLayout weekdays = row();
        for (String day : WEEKDAYS) {
            TextView label = text(day, 13, MUTED, true);
            label.setGravity(Gravity.CENTER);
            weekdays.addView(label, weight(1));
        }
        addBlock(calendar, weekdays, 4);
        LocalDate first = month.atDay(1);
        int offset = first.getDayOfWeek().getValue() - 1;
        int weeks = (offset + month.lengthOfMonth() + 6) / 7;
        for (int w = 0; w < weeks; w++) {
            LinearLayout week = row();
            for (int d = 0; d < 7; d++) {
                LocalDate date = first.plusDays(w * 7L + d - offset);
                week.addView(dayTile(date, month), new LinearLayout.LayoutParams(0, dp(43), 1));
            }
            calendar.addView(week);
        }
        TextView legend = text("● События   • Дела", 13, DEEP, false);
        legend.setPadding(dp(4), dp(9), 0, 0);
        calendar.addView(legend);
        addBlock(body, calendar, 13);
        LinearLayout events = card();
        addHeading(events, DAY.format(selected) + " · События");
        ArrayList<Entry> eventItems = items(selected, true);
        if (eventItems.isEmpty()) empty(events, "Событий на этот день нет");
        for (Entry event : eventItems) addEntryRow(events, event, false);
        addBlock(body, events, 13);
        LinearLayout tasks = card();
        addHeading(tasks, "Дела на этот день");
        ArrayList<Entry> taskItems = items(selected, false);
        if (taskItems.isEmpty()) empty(tasks, "Дел на этот день нет");
        for (Entry task : taskItems) addEntryRow(tasks, task, false);
        tasks.addView(button("+ Добавить дело", SOFT, DEEP, () -> editEntry(false, null, selected)), wrap());
        addBlock(body, tasks, 13);
        addBlock(body, reminderSettings(), 0);
    }

    private View dayTile(LocalDate date, YearMonth month) {
        boolean active = date.equals(selected);
        boolean events = store.entries.stream().anyMatch(entry -> entry.event && entry.date.equals(date));
        boolean tasks = store.entries.stream().anyMatch(entry -> !entry.event && entry.date.equals(date));
        int color = active ? WHITE : YearMonth.from(date).equals(month) ? INK : MUTED;
        int fill = active ? PURPLE : date.equals(today) ? SOFT : WHITE;
        LinearLayout cell = column();
        cell.setGravity(Gravity.CENTER);
        cell.setBackground(touch(fill, 9));
        cell.setOnClickListener(view -> { selected = date; render(true); });
        cell.setFocusable(true);
        cell.setSelected(active);
        cell.setContentDescription(DAY.format(date) + (events ? ", события" : "") + (tasks ? ", дела" : ""));
        cell.addView(text(Integer.toString(date.getDayOfMonth()), 14, color, true));
        TextView dots = text((events ? "●" : "") + (tasks ? "•" : ""), 9, active ? WHITE : PURPLE, true);
        dots.setIncludeFontPadding(false);
        cell.addView(dots);
        return cell;
    }

    private View reminderSettings() {
        LinearLayout card = card();
        addHeading(card, "Напоминания");
        boolean enabled = PlannerAlarm.canNotify(this);
        boolean exact = PlannerAlarm.exactAllowed(this);
        String detail = enabled ? (exact ? "Уведомления и точное время разрешены." :
                "Уведомления разрешены. Без доступа к точным будильникам Android может доставлять их позже.") :
                "Разреши уведомления, чтобы получать напоминания при закрытом приложении.";
        addBlock(card, text(detail, 14, MUTED, false), 7);
        if (!enabled) card.addView(button("Разрешить уведомления", SOFT, DEEP, this::enableNotifications), wrap());
        else if (!exact && Build.VERSION.SDK_INT >= 31) {
            card.addView(button("Разрешить точное время", SOFT, DEEP, () -> openSettings(
                    new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName())))), wrap());
        }
        return card;
    }

    private void editEntry(boolean event, Entry original, LocalDate day) {
        Entry draft = original == null ? new Entry(event, day) : original.copy();
        LinearLayout fields = dialogFields();
        EditText title = input("Введи название", draft.title, false, 140);
        field(fields, "Название", title);

        Button dateButton = button(DAY.format(draft.date), SOFT, DEEP, () -> {});
        dateButton.setOnClickListener(view -> new DatePickerDialog(this, (picker, year, month, date) -> {
            draft.date = LocalDate.of(year, month + 1, date);
            dateButton.setText(DAY.format(draft.date));
        }, draft.date.getYear(), draft.date.getMonthValue() - 1, draft.date.getDayOfMonth()).show());
        field(fields, "Дата", dateButton);

        LinearLayout times = row();
        Button timeButton = button(draft.time == null ? "Указать время" : CLOCK.format(draft.time), SOFT, DEEP, () -> {});
        timeButton.setOnClickListener(view -> {
            LocalTime start = draft.time == null ? LocalTime.now() : draft.time;
            new TimePickerDialog(this, (picker, hour, minute) -> {
                draft.time = LocalTime.of(hour, minute);
                timeButton.setText(CLOCK.format(draft.time));
            }, start.getHour(), start.getMinute(), true).show();
        });
        times.addView(timeButton, weight(1));
        times.addView(button("Очистить", PAPER, MUTED, () -> {
            draft.time = null;
            timeButton.setText("Указать время");
        }), wrap());
        field(fields, "Время", times);

        EditText reminder = input("Без напоминания", draft.reminder == null ? "" :
                Integer.toString(draft.reminder), false, 10);
        reminder.setInputType(InputType.TYPE_CLASS_NUMBER);
        field(fields, "Напомнить за, мин", reminder);
        addBlock(fields, text("Для записей со временем. 120 минут = 2 часа, 0 = в момент начала.", 13, MUTED, false), 10);

        EditText detail;
        Spinner repeat;
        Spinner category;
        if (event) {
            detail = input("Место или детали события", draft.detail, true, 8000);
            field(fields, "Описание", detail);
            repeat = null;
            category = null;
        } else {
            detail = null;
            String[] repeats = java.util.Arrays.stream(Repeat.values()).map(option -> option.label).toArray(String[]::new);
            repeat = spinner(repeats, draft.repeat.ordinal());
            category = spinner(CATEGORIES, Math.max(0, java.util.Arrays.asList(CATEGORIES).indexOf(draft.category)));
            field(fields, "Повтор", repeat);
            field(fields, "Категория", category);
        }

        String label = event ? (original == null ? "Новое событие" : "Редактировать событие") :
                (original == null ? "Новое дело" : "Редактировать дело");
        AlertDialog dialog = showEditor(label, fields, original == null ? null : () -> {
            store.entries.removeIf(entry -> entry.id.equals(original.id));
            PlannerAlarm.clearNotification(this, original.id);
            persist();
            render(true);
            announce("Запись удалена");
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            draft.title = value(title);
            if (draft.title.isEmpty()) { title.setError("Введи название"); title.requestFocus(); return; }
            try { draft.reminder = value(reminder).isEmpty() ? null : Integer.valueOf(value(reminder)); }
            catch (NumberFormatException invalid) {
                reminder.setError("Укажи целое количество минут");
                reminder.requestFocus();
                return;
            }
            Entry latest = original == null ? null : findEntry(original.id);
            if (original != null && latest == null) {
                dialog.dismiss();
                announce("Этой записи больше нет");
                return;
            }
            if (latest != null) {
                draft.done = latest.done;
                if (draft.date.equals(original.date)) draft.date = latest.date;
            }
            if (event) draft.detail = value(detail);
            else {
                draft.repeat = Repeat.values()[repeat.getSelectedItemPosition()];
                int index = category.getSelectedItemPosition();
                draft.category = index > 0 ? CATEGORIES[index] : "";
                if (latest == null || !draft.date.equals(latest.date) || draft.repeat != latest.repeat) {
                    draft.anchor = draft.date;
                } else draft.anchor = latest.anchor;
                draft.edited = !draft.previous.isEmpty();
                filter = TaskFilter.ALL;
            }
            if (latest == null) store.entries.add(0, draft);
            else {
                store.entries.set(store.entries.indexOf(latest), draft);
                if (!draft.reminderKey().equals(latest.reminderKey()) || !draft.title.equals(latest.title)) {
                    PlannerAlarm.clearNotification(this, latest.id);
                }
            }
            PlannerStore.rollover(store.entries, today);
            if (page == Page.CALENDAR) selected = draft.date;
            persist();
            dialog.dismiss();
            render(true);
            if (draft.time != null && draft.reminder != null && !PlannerAlarm.canNotify(this)) {
                announce("Запись сохранена. Уведомления можно включить в календаре.");
            } else announce("Сохранено");
        });
    }

    private void notesPage() {
        header("Заметки", "Записи и списки", "Новая заметка", () -> editNote(null, Template.TEXT));
        Template[] templates = Template.values();
        for (int i = 0; i < templates.length; i += 2) {
            LinearLayout row = row();
            for (int position = i; position < i + 2; position++) {
                Template type = templates[position];
                LinearLayout tile = card();
                tile.setMinimumHeight(dp(83));
                tile.setBackground(touch(WHITE, 17));
                tile.setOnClickListener(view -> editNote(null, type));
                tile.setFocusable(true);
                tile.setContentDescription(type.label + ", " + type.description);
                tile.addView(text(type.icon + "  " + type.label, 15, INK, true));
                tile.addView(text(type.description, 13, MUTED, false));
                LinearLayout.LayoutParams params = weight(1);
                if (position == i) params.rightMargin = dp(8);
                row.addView(tile, params);
            }
            addBlock(body, row, 8);
        }
        EditText search = input("Поиск по заметкам", noteQuery, false, 200);
        search.setContentDescription("Поиск по заметкам");
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        addBlock(body, search, 13);
        addHeading(body, "Мои заметки");
        LinearLayout firstFilter = row();
        LinearLayout secondFilter = row();
        firstFilter.addView(choice("Все", noteFilter == null, () -> { noteFilter = null; render(true); }), weight(1));
        for (int i = 0; i < templates.length; i++) {
            Template type = templates[i];
            (i < 2 ? firstFilter : secondFilter).addView(choice(type.label, noteFilter == type, () -> {
                noteFilter = type;
                render(true);
            }), weight(1));
        }
        addBlock(body, firstFilter, 1);
        addBlock(body, secondFilter, 9);
        LinearLayout results = column();
        body.addView(results);
        renderNotes(results);
        track(search, value -> {
            noteQuery = value;
            renderNotes(results);
        });
        search.setOnEditorActionListener((view, action, key) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            dismissKeyboard();
            search.clearFocus();
            return true;
        });
    }

    private void renderNotes(LinearLayout results) {
        results.removeAllViews();
        boolean listed = false;
        for (Note note : orderedNotes()) {
            if ((noteFilter != null && note.type != noteFilter) || !note.matches(noteQuery)) continue;
            listed = true;
            addBlock(results, noteCard(note, results), 11);
        }
        if (!listed) {
            LinearLayout tile = card();
            empty(tile, noteQuery.trim().isEmpty() ? "Здесь пока пусто" : "Ничего не найдено");
            addBlock(results, tile, 0);
        }
    }

    private LinearLayout noteCard(Note note, LinearLayout results) {
        LinearLayout tile = card();
        addBlock(tile, text(note.type.label, 13, DEEP, true), 3);
        TextView title = text(note.title, 19, INK, true);
        title.setOnClickListener(view -> editNote(note, note.type));
        title.setFocusable(true);
        addBlock(tile, title, 5);

        LinearLayout details = column();
        tile.addView(details);
        ImageButton arrow = new ImageButton(this);
        arrow.setImageResource(R.drawable.ic_expand_note);
        arrow.setScaleType(ImageView.ScaleType.CENTER);
        arrow.setPadding(0, 0, 0, 0);
        arrow.setBackground(touch(SOFT, 12));
        LinearLayout.LayoutParams arrowSize = new LinearLayout.LayoutParams(dp(48), dp(48));
        arrowSize.gravity = Gravity.END;
        tile.addView(arrow, arrowSize);
        arrow.setOnClickListener(view -> {
            if (expandedNotes.remove(note.id)) {
                renderNoteDetails(note, results, details, arrow);
                tile.post(() -> tile.requestRectangleOnScreen(
                        new Rect(0, 0, tile.getWidth(), tile.getHeight()), false));
            } else {
                expandedNotes.add(note.id);
                renderNoteDetails(note, results, details, arrow);
            }
        });
        renderNoteDetails(note, results, details, arrow);
        return tile;
    }

    private void renderNoteDetails(Note note, LinearLayout results, LinearLayout details, ImageButton arrow) {
        boolean expanded = expandedNotes.contains(note.id);
        arrow.setRotation(expanded ? 180f : 0f);
        arrow.setContentDescription((expanded ? "Свернуть заметку: " : "Развернуть заметку: ") + note.title);
        details.removeAllViews();
        details.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (!expanded) return;

        addNoteContents(details, note);
        LinearLayout actions = row();
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button pin = button(note.pinned ? "Открепить" : "Закрепить", note.pinned ? SOFT : WHITE, DEEP, () -> {
            note.pinned = !note.pinned;
            persist();
            renderNotes(results);
        });
        pin.setSelected(note.pinned);
        actions.addView(pin, wrap());
        actions.addView(button("Изменить", WHITE, MUTED, () -> editNote(note, note.type)), wrap());
        addBlock(details, actions, 4);
    }

    private ArrayList<Note> orderedNotes() {
        ArrayList<Note> notes = new ArrayList<>(store.notes);
        notes.sort(Comparator.comparing((Note note) -> note.pinned).reversed());
        return notes;
    }

    private void addNoteContents(LinearLayout tile, Note note) {
        if (note.type == Template.TEXT) {
            if (!note.text.isEmpty()) tile.addView(text(note.text, 16, MUTED, false));
            return;
        }
        int number = 0;
        for (Line line : note.lines) {
            if (note.type == Template.SECTIONS) {
                if (!line.title.isEmpty()) addBlock(tile, text(line.title, 15, INK, true), 2);
                if (!line.body.isEmpty()) addBlock(tile, text(line.body, 15, MUTED, false), 9);
            } else if (!line.title.isEmpty()) {
                if (note.type == Template.CHECKLIST) {
                    CheckBox box = new CheckBox(this);
                    box.setText(line.title);
                    box.setTextSize(15);
                    box.setTextColor(MUTED);
                    box.setButtonTintList(checkColors());
                    box.setChecked(line.checked);
                    strike(box, line.checked);
                    box.setMinimumHeight(dp(41));
                    box.setOnCheckedChangeListener((view, checked) -> {
                        line.checked = checked;
                        strike(box, checked);
                        persist();
                    });
                    tile.addView(box);
                } else {
                    addBlock(tile, text(++number + ".  " + line.title, 15, MUTED, false), 4);
                }
            }
        }
    }

    private static void strike(TextView text, boolean enabled) {
        int flags = text.getPaintFlags();
        text.setPaintFlags(enabled ? flags | Paint.STRIKE_THRU_TEXT_FLAG : flags & ~Paint.STRIKE_THRU_TEXT_FLAG);
    }

    private void editNote(Note original, Template type) {
        Note draft = original == null ? new Note(type) : original.copy();
        LinearLayout fields = dialogFields();
        EditText title = input("Введи название", draft.title, false, 140);
        field(fields, "Название", title);
        String[] labels = java.util.Arrays.stream(Template.values()).map(option -> option.label).toArray(String[]::new);
        Spinner selector = spinner(labels, draft.type.ordinal());
        field(fields, "Шаблон", selector);
        LinearLayout editor = column();
        fields.addView(editor);
        noteFields(editor, draft);
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View row, int position, long id) {
                Template chosen = Template.values()[position];
                if (chosen != draft.type) {
                    draft.type = chosen;
                    noteFields(editor, draft);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        AlertDialog dialog = showEditor(original == null ? "Новая заметка" : "Редактировать заметку", fields,
                original == null ? null : () -> {
                    store.notes.removeIf(note -> note.id.equals(original.id));
                    expandedNotes.remove(original.id);
                    persist();
                    render(true);
                    announce("Заметка удалена");
                });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            draft.title = value(title);
            if (draft.title.isEmpty()) { title.setError("Введи название"); title.requestFocus(); return; }
            draft.text = draft.text.trim();
            for (Line entry : draft.lines) {
                entry.title = entry.title.trim();
                entry.body = entry.body.trim();
            }
            draft.lines.removeIf(entry -> entry.title.isEmpty() && entry.body.isEmpty());
            Note latest = original == null ? null : findNote(original.id);
            if (original != null && latest == null) {
                dialog.dismiss();
                announce("Этой заметки больше нет");
                return;
            }
            if (latest == null) store.notes.add(0, draft);
            else {
                draft.pinned = latest.pinned;
                store.notes.set(store.notes.indexOf(latest), draft);
            }
            noteFilter = null;
            noteQuery = "";
            persist();
            dialog.dismiss();
            render(true);
            announce("Заметка сохранена");
        });
    }

    private void noteFields(LinearLayout editor, Note note) {
        editor.removeAllViews();
        if (note.type == Template.TEXT) {
            EditText text = input("Напиши что-нибудь", note.text, true, 8000);
            track(text, value -> note.text = value);
            field(editor, "Текст", text);
            return;
        }
        boolean sections = note.type == Template.SECTIONS;
        boolean checklist = note.type == Template.CHECKLIST;
        if (note.lines.isEmpty()) note.lines.add(new Line());
        for (Line line : note.lines) {
            LinearLayout row = row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            if (checklist) {
                CheckBox flag = new CheckBox(this);
                flag.setContentDescription("Отметить пункт");
                flag.setButtonTintList(checkColors());
                flag.setChecked(line.checked);
                flag.setOnCheckedChangeListener((view, checked) -> line.checked = checked);
                row.addView(flag, new LinearLayout.LayoutParams(dp(43), dp(44)));
            }
            String label = sections ? "Заголовок раздела" : "Текст пункта";
            EditText heading = input(label, line.title, false, 180);
            heading.setContentDescription(label);
            track(heading, value -> line.title = value);
            row.addView(heading, weight(1));
            Button remove = button("×", PAPER, MUTED, () -> {
                note.lines.remove(line);
                noteFields(editor, note);
            });
            remove.setContentDescription(sections ? "Убрать раздел" : "Убрать пункт");
            row.addView(remove, new LinearLayout.LayoutParams(dp(45), dp(44)));
            addBlock(editor, row, 6);
            if (sections) {
                EditText content = input("Текст раздела", line.body, true, 8000);
                track(content, value -> line.body = value);
                addBlock(editor, content, 10);
            }
        }
        editor.addView(button(sections ? "+ Добавить раздел" : "+ Добавить пункт", SOFT, DEEP, () -> {
            note.lines.add(new Line());
            noteFields(editor, note);
            LinearLayout lastRow = (LinearLayout) editor.getChildAt(editor.getChildCount() - (sections ? 3 : 2));
            lastRow.getChildAt(checklist ? 1 : 0).requestFocus();
        }), wrap());
    }

    private LinearLayout dialogFields() {
        LinearLayout fields = column();
        fields.setPadding(dp(21), dp(10), dp(21), dp(12));
        return fields;
    }

    private void field(LinearLayout fields, String label, View input) {
        TextView title = text(label, 14, INK, true);
        input.setId(View.generateViewId());
        title.setLabelFor(input.getId());
        addBlock(fields, title, 4);
        addBlock(fields, input, 11);
    }

    private Spinner spinner(String[] items, int selected) {
        Spinner spinner = new Spinner(this, Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected);
        spinner.setMinimumHeight(dp(44));
        return spinner;
    }

    private AlertDialog showEditor(String title, LinearLayout fields, Runnable delete) {
        ScrollView container = new ScrollView(this);
        container.addView(fields, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2));
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(title).setView(container)
                .setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null);
        if (delete != null) builder.setNeutralButton("Удалить", null);
        AlertDialog dialog = builder.create();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN |
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        if (delete != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view ->
                new AlertDialog.Builder(this).setTitle("Удалить запись?")
                        .setNegativeButton("Отмена", null).setPositiveButton("Удалить", (confirm, option) -> {
                            dialog.dismiss();
                            delete.run();
                        }).show());
        return dialog;
    }

    private void enableNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !getPreferences(MODE_PRIVATE).getBoolean("asked_notifications", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("asked_notifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        } else {
            openSettings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    private void openSettings(Intent intent) {
        try { startActivity(intent); }
        catch (android.content.ActivityNotFoundException missing) {
            announce("Разрешение можно открыть в настройках Android");
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == NOTIFICATION_REQUEST) {
            schedule();
            render(true);
        }
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private Button button(String label, int background, int foreground, Runnable action) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setText(label);
        view.setTextSize(14);
        view.setTextColor(foreground);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setMinimumWidth(0);
        view.setMinWidth(0);
        view.setMinimumHeight(dp(45));
        view.setMinHeight(dp(45));
        view.setPadding(dp(10), dp(3), dp(10), dp(3));
        view.setBackground(touch(background, 12));
        view.setStateListAnimator(null);
        view.setOnClickListener(button -> action.run());
        return view;
    }

    private Button choice(String label, boolean selected, Runnable action) {
        Button button = button(label, selected ? SOFT : WHITE, selected ? DEEP : MUTED, action);
        button.setSelected(selected);
        return button;
    }

    private EditText input(String hint, String initial, boolean multiline, int max) {
        EditText edit = new EditText(this);
        edit.setTextColor(INK);
        edit.setHintTextColor(MUTED);
        edit.setTextSize(16);
        edit.setSingleLine(!multiline);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES |
                (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        edit.setGravity(multiline ? Gravity.TOP | Gravity.START : Gravity.CENTER_VERTICAL | Gravity.START);
        edit.setMinLines(multiline ? 3 : 1);
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        edit.setHint(hint);
        edit.setText(initial);
        edit.setPadding(dp(10), dp(8), dp(10), dp(8));
        edit.setBackground(shape(PAPER, 10, LINE));
        edit.setMinimumHeight(dp(44));
        return edit;
    }

    private LinearLayout card() {
        LinearLayout view = column();
        view.setBackground(shape(WHITE, 20, LINE));
        view.setElevation(dp(1));
        view.setPadding(dp(14), dp(13), dp(14), dp(13));
        return view;
    }

    private void addHeading(LinearLayout view, String title) {
        TextView heading = text(title, 18, INK, true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        addBlock(view, heading, 6);
    }

    private void empty(LinearLayout view, String message) {
        TextView label = text(message, 15, MUTED, false);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(4), dp(17), dp(4), dp(17));
        view.addView(label, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addBlock(LinearLayout parent, View view, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(gap);
        parent.addView(view, params);
    }

    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        if (stroke != 0) shape.setStroke(dp(1), stroke);
        return shape;
    }

    private RippleDrawable touch(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x236946e1),
                shape(color, radius, 0), shape(WHITE, radius, 0));
    }

    private ColorStateList checkColors() {
        return new ColorStateList(new int[][]{{android.R.attr.state_checked},{}}, new int[]{PURPLE,MUTED});
    }

    private static LinearLayout.LayoutParams weight(int value) {
        return new LinearLayout.LayoutParams(0, -2, value);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-2, -2);
    }

    private static String capital(String value) {
        return value.substring(0, 1).toUpperCase(RU) + value.substring(1);
    }

    private static String value(EditText input) {
        return input.getText().toString().trim();
    }

    private void track(EditText edit, Consumer<String> onChange) {
        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                onChange.accept(text.toString());
            }
            @Override public void afterTextChanged(Editable editable) { }
        });
    }
}

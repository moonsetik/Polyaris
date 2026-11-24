package app.polaris.planner;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PlannerStore {
    private static final Pattern QUICK_TIME = Pattern.compile(
            "^(.+?)\\s+(?:в\\s+)?([01]?\\d|2[0-3]):([0-5]\\d)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private final SharedPreferences preferences;
    private String savedData;
    final ArrayList<Entry> entries = new ArrayList<>();
    final ArrayList<Note> notes = new ArrayList<>();
    final Set<String> delivered = new HashSet<>();

    enum Repeat {
        NONE("Без повтора"), DAILY("Каждый день"), WEEKLY("Каждую неделю"), MONTHLY("Каждый месяц");
        final String label;
        Repeat(String label) { this.label = label; }
    }

    enum Template {
        TEXT("Текст", "Свободная запись", "≡"), LIST("Список", "Пункты по порядку", "☷"),
        CHECKLIST("Чек-лист", "Пункты с отметками", "✓"), SECTIONS("С разделами", "Блоки с заголовками", "▤");
        final String label;
        final String description;
        final String icon;
        Template(String label, String description, String icon) {
            this.label = label;
            this.description = description;
            this.icon = icon;
        }
    }

    static final class Entry {
        String id = UUID.randomUUID().toString();
        boolean event;
        String title = "";
        LocalDate date;
        LocalTime time;
        LocalDate anchor;
        boolean done;
        Repeat repeat = Repeat.NONE;
        String category = "";
        String detail = "";
        Integer reminder;
        String previous = "";
        boolean edited;

        Entry(boolean event, LocalDate date) {
            this.event = event;
            this.date = date;
            anchor = date;
            reminder = event ? Integer.valueOf(120) : null;
        }

        Entry copy() {
            Entry copy = new Entry(event, date);
            copy.id = id;
            copy.title = title;
            copy.time = time;
            copy.anchor = anchor;
            copy.done = done;
            copy.repeat = repeat;
            copy.category = category;
            copy.detail = detail;
            copy.reminder = reminder;
            copy.previous = previous;
            copy.edited = edited;
            return copy;
        }

        Instant startsAt(ZoneId zone) {
            return time == null ? null : date.atTime(time).atZone(zone).toInstant();
        }

        Instant reminderAt(ZoneId zone) {
            return time == null || reminder == null || done ? null :
                    startsAt(zone).minusSeconds(reminder.longValue() * 60);
        }

        String reminderKey() {
            return id + "|" + date + "|" + time + "|" + reminder;
        }
    }

    static final class Line {
        String title = "";
        String body = "";
        boolean checked;

        Line copy() {
            Line copy = new Line();
            copy.title = title;
            copy.body = body;
            copy.checked = checked;
            return copy;
        }
    }

    static final class Note {
        String id = UUID.randomUUID().toString();
        String title = "";
        Template type;
        boolean pinned;
        String text = "";
        final ArrayList<Line> lines = new ArrayList<>();

        Note(Template type) { this.type = type; }

        Note copy() {
            Note copy = new Note(type);
            copy.id = id;
            copy.title = title;
            copy.pinned = pinned;
            copy.text = text;
            for (Line line : lines) copy.lines.add(line.copy());
            return copy;
        }

        boolean matches(String query) {
            StringBuilder content = new StringBuilder(title).append(' ').append(text);
            for (Line line : lines) content.append(' ').append(line.title).append(' ').append(line.body);
            String haystack = normalize(content.toString());
            for (String word : normalize(query.trim()).split("\\s+")) {
                if (!haystack.contains(word)) return false;
            }
            return true;
        }
    }

    PlannerStore(Context context) {
        preferences = context.getSharedPreferences("planner", Context.MODE_PRIVATE);
        savedData = preferences.getString("data", "{}");
        try {
            JSONObject data = new JSONObject(savedData);
            JSONArray savedEntries = data.optJSONArray("entries");
            JSONArray savedNotes = data.optJSONArray("notes");
            if (savedEntries != null) {
                for (int i = 0; i < savedEntries.length(); i++) entries.add(readEntry(savedEntries.getJSONObject(i)));
            }
            if (savedNotes != null) {
                for (int i = 0; i < savedNotes.length(); i++) notes.add(readNote(savedNotes.getJSONObject(i)));
            }
        } catch (JSONException | IllegalArgumentException failure) {
            throw new IllegalStateException("Unable to read planner data", failure);
        }
        delivered.addAll(preferences.getStringSet("delivered", new HashSet<>()));
    }

    boolean isCurrent() {
        return savedData.equals(preferences.getString("data", "{}"));
    }

    boolean save() {
        try {
            JSONArray savedEntries = new JSONArray();
            JSONArray savedNotes = new JSONArray();
            for (Entry entry : entries) savedEntries.put(writeEntry(entry));
            for (Note note : notes) savedNotes.put(writeNote(note));
            savedData = new JSONObject().put("entries", savedEntries).put("notes", savedNotes).toString();
            return preferences.edit().putString("data", savedData).commit();
        } catch (JSONException failure) {
            return false;
        }
    }

    void saveDelivered() {
        Set<String> relevant = new HashSet<>();
        for (Entry entry : entries) relevant.add(entry.reminderKey());
        delivered.retainAll(relevant);
        preferences.edit().putStringSet("delivered", new HashSet<>(delivered)).apply();
    }

    private static Entry readEntry(JSONObject item) throws JSONException {
        Entry entry = new Entry(item.getBoolean("event"), LocalDate.parse(item.getString("date")));
        entry.id = item.getString("id");
        entry.title = item.getString("title");
        if (!item.optString("time").isEmpty()) entry.time = LocalTime.parse(item.getString("time"));
        entry.anchor = LocalDate.parse(item.optString("anchor", entry.date.toString()));
        entry.done = item.optBoolean("done");
        entry.repeat = Repeat.valueOf(item.optString("repeat", "NONE"));
        entry.category = item.optString("category");
        entry.detail = item.optString("detail");
        entry.reminder = item.isNull("reminder") ? null : item.getInt("reminder");
        entry.previous = item.optString("previous");
        entry.edited = item.optBoolean("edited");
        return entry;
    }

    private static JSONObject writeEntry(Entry entry) throws JSONException {
        return new JSONObject().put("id", entry.id).put("event", entry.event)
                .put("title", entry.title).put("date", entry.date.toString())
                .put("time", entry.time == null ? "" : entry.time.toString())
                .put("anchor", entry.anchor.toString()).put("done", entry.done)
                .put("repeat", entry.repeat.name()).put("category", entry.category)
                .put("detail", entry.detail).put("reminder", entry.reminder == null ? JSONObject.NULL : entry.reminder)
                .put("previous", entry.previous).put("edited", entry.edited);
    }

    private static Note readNote(JSONObject item) throws JSONException {
        Note note = new Note(Template.valueOf(item.getString("type")));
        note.id = item.getString("id");
        note.title = item.getString("title");
        note.text = item.optString("text");
        note.pinned = item.optBoolean("pinned");
        JSONArray lines = item.getJSONArray("lines");
        for (int i = 0; i < lines.length(); i++) {
            JSONObject saved = lines.getJSONObject(i);
            Line line = new Line();
            line.title = saved.optString("title");
            line.body = saved.optString("body");
            line.checked = saved.optBoolean("checked");
            note.lines.add(line);
        }
        return note;
    }

    private static JSONObject writeNote(Note note) throws JSONException {
        JSONArray lines = new JSONArray();
        for (Line line : note.lines) {
            lines.put(new JSONObject().put("title", line.title).put("body", line.body).put("checked", line.checked));
        }
        return new JSONObject().put("id", note.id).put("title", note.title).put("type", note.type.name())
                .put("text", note.text).put("pinned", note.pinned).put("lines", lines);
    }

    static Entry quickTask(String input, LocalDate date) {
        Entry task = new Entry(false, date);
        String value = input.trim();
        Matcher match = QUICK_TIME.matcher(value);
        if (match.matches()) {
            task.title = match.group(1).trim();
            task.time = LocalTime.of(Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3)));
        } else {
            task.title = value;
        }
        return task;
    }

    static boolean rollover(ArrayList<Entry> entries, LocalDate today) {
        boolean changed = false;
        for (Entry entry : entries) {
            if (!entry.event && !entry.done && entry.date.isBefore(today)) {
                entry.date = today;
                changed = true;
            }
        }
        return changed;
    }

    static ArrayList<Entry> tasksForDay(ArrayList<Entry> entries, LocalDate date) {
        ArrayList<Entry> tasks = new ArrayList<>();
        for (Entry entry : entries) if (!entry.event && entry.date.equals(date)) tasks.add(entry);
        tasks.sort(Comparator.comparing(entry -> entry.time, Comparator.nullsLast(Comparator.naturalOrder())));
        return tasks;
    }

    static boolean setCompletion(ArrayList<Entry> entries, String id, LocalDate day, boolean completed) {
        for (Entry entry : entries) {
            if (!entry.event && entry.id.equals(id) && entry.date.equals(day) && entry.done != completed) {
                toggle(entries, entry, day);
                return true;
            }
        }
        return false;
    }

    static ArrayList<Entry> widgetTasks(Context context, LocalDate date) {
        PlannerStore store = new PlannerStore(context);
        rollover(store.entries, date);
        return tasksForDay(store.entries, date);
    }

    static LocalDate nextOccurrence(Entry entry, LocalDate today) {
        LocalDate boundary = entry.date.isAfter(today) ? entry.date : today;
        switch (entry.repeat) {
            case DAILY: return boundary.plusDays(1);
            case WEEKLY:
                long weeks = Math.floorDiv(ChronoUnit.DAYS.between(entry.anchor, boundary), 7);
                return entry.anchor.plusWeeks(weeks + 1);
            case MONTHLY:
                YearMonth month = YearMonth.from(boundary);
                LocalDate candidate = month.atDay(Math.min(entry.anchor.getDayOfMonth(), month.lengthOfMonth()));
                if (candidate.isAfter(boundary)) return candidate;
                YearMonth next = month.plusMonths(1);
                return next.atDay(Math.min(entry.anchor.getDayOfMonth(), next.lengthOfMonth()));
            default: return null;
        }
    }

    static void toggle(ArrayList<Entry> entries, Entry task, LocalDate today) {
        task.done = !task.done;
        if (!task.done) {
            entries.removeIf(next -> next.previous.equals(task.id) && !next.done && !next.edited);
            if (task.date.isBefore(today)) task.date = today;
            return;
        }
        LocalDate nextDate = nextOccurrence(task, today);
        if (nextDate != null && entries.stream().noneMatch(next -> next.previous.equals(task.id))) {
            Entry next = task.copy();
            next.id = UUID.randomUUID().toString();
            next.previous = task.id;
            next.done = false;
            next.edited = false;
            next.date = nextDate;
            entries.add(next);
        }
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.forLanguageTag("ru")).replace('ё', 'е');
    }
}

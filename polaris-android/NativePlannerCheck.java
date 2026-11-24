package app.polaris.planner;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Objects;

public final class NativePlannerCheck {
    private static void equal(Object expected, Object actual, String feature) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(feature + ": expected " + expected + ", got " + actual);
        }
    }

    public static void main(String[] args) {
        LocalDate today = LocalDate.of(2026,11,21);
        PlannerStore.Entry task = PlannerStore.quickTask(" Позвонить В 9:05 ",today);
        equal("Позвонить",task.title,"quick task title");
        equal(LocalTime.of(9,5),task.time,"quick task time");
        equal(null,PlannerStore.quickTask("Время 24:10",today).time,"invalid time treated as text");

        ArrayList<PlannerStore.Entry> entries = new ArrayList<>();
        task.repeat = PlannerStore.Repeat.DAILY;
        entries.add(task);
        PlannerStore.toggle(entries,task,today);
        equal(true,task.done,"task completes");
        equal(today.plusDays(1),entries.get(1).date,"daily repeat");
        PlannerStore.toggle(entries,task,today);
        equal(1,entries.size(),"undo drops generated occurrence");
        task.date = today.minusDays(2);
        equal(true,PlannerStore.rollover(entries,today),"rollover noticed");
        equal(today,task.date,"unfinished task rolls to today");

        PlannerStore.Entry month = new PlannerStore.Entry(false,LocalDate.of(2024,1,31));
        month.repeat = PlannerStore.Repeat.MONTHLY;
        equal(LocalDate.of(2024,2,29),PlannerStore.nextOccurrence(month,month.date),"short leap month");
        month.date = LocalDate.of(2024,2,29);
        equal(LocalDate.of(2024,3,31),PlannerStore.nextOccurrence(month,month.date),"monthly anchor preserved");
        task.repeat = PlannerStore.Repeat.WEEKLY;
        task.anchor = today.minusDays(2);
        equal(today.plusDays(5),PlannerStore.nextOccurrence(task,today),"weekly anchor preserved after rollover");

        PlannerStore.Note note = new PlannerStore.Note(PlannerStore.Template.SECTIONS);
        note.title = "План работы";
        note.pinned = true;
        PlannerStore.Line section = new PlannerStore.Line();
        section.title = "Приложение";
        section.body = "Проверить отчёт";
        section.checked = true;
        note.lines.add(section);
        equal(true,note.matches("ПЛАН отчет"),"note search");
        equal(false,note.matches("произвольный"),"note non-match");
        PlannerStore.Note copy = note.copy();
        copy.lines.get(0).body = "Новое содержание";
        equal("Проверить отчёт",section.body,"draft is independent");
        equal(true,copy.pinned,"draft preserves pin");
        equal(true,copy.lines.get(0).checked,"draft preserves checklist state");

        PlannerStore.Entry event = new PlannerStore.Entry(true,today.plusDays(1));
        event.time=LocalTime.of(0,30);
        ZoneId zone=ZoneId.of("Europe/Moscow");
        equal(Integer.valueOf(120),event.reminder,"event default reminder");
        equal(Instant.parse("2026-11-21T19:30:00Z"),event.reminderAt(zone),"reminder crosses midnight");
        event.reminder=15;
        equal(Instant.parse("2026-11-21T21:15:00Z"),event.reminderAt(zone),"custom reminder");
        event.reminder=null;
        equal(null,event.reminderAt(zone),"disabled reminder");
        event.reminder=0;
        equal(event.startsAt(zone),event.reminderAt(zone),"at start reminder");
        event.time=null;
        equal(null,event.reminderAt(zone),"all-day event has no alarm");
        task.reminder=10;
        task.done=true;
        equal(null,task.reminderAt(zone),"complete task has no alarm");

        ArrayList<PlannerStore.Entry> widgetEntries = new ArrayList<>();
        PlannerStore.Entry throughWidget = PlannerStore.quickTask("Виджет в 11:00", today);
        throughWidget.repeat = PlannerStore.Repeat.DAILY;
        PlannerStore.Entry untimed = PlannerStore.quickTask("Без времени", today);
        widgetEntries.add(untimed);
        widgetEntries.add(throughWidget);
        widgetEntries.add(new PlannerStore.Entry(true, today));
        widgetEntries.add(new PlannerStore.Entry(false, today.plusDays(1)));
        equal(java.util.Arrays.asList(throughWidget, untimed), PlannerStore.tasksForDay(widgetEntries, today),
                "widget lists only today's tasks and sorts by time");
        equal(false, PlannerStore.setCompletion(widgetEntries, throughWidget.id, today.minusDays(1), true),
                "widget rejects a stale date");
        equal(true, PlannerStore.setCompletion(widgetEntries, throughWidget.id, today, true),
                "widget completes a task");
        equal(true, throughWidget.done, "widget completion is shared");
        equal(false, PlannerStore.setCompletion(widgetEntries, throughWidget.id, today, true),
                "duplicate widget tap is harmless");
        equal(5, widgetEntries.size(), "widget completion creates one repeat");
        equal(true, PlannerStore.setCompletion(widgetEntries, throughWidget.id, today, false),
                "widget can restore a completed task");
        equal(4, widgetEntries.size(), "undo removes the untouched repeated task");
        System.out.println("Passed: quick capture, recurrence, rollover, note search/copy and reminder offsets.");
        System.out.println("Passed: widget day filter, ordering, completion, undo and stale clicks.");
    }
}

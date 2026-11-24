package app.polaris.planner;

import android.content.Intent;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StrikethroughSpan;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class TodayWidgetService extends RemoteViewsService {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new RemoteViewsFactory() {
            private List<PlannerStore.Entry> tasks = new ArrayList<>();
            private String day = "";

            @Override public void onCreate() { onDataSetChanged(); }

            @Override public void onDataSetChanged() {
                LocalDate today = LocalDate.now();
                tasks = PlannerStore.widgetTasks(TodayWidgetService.this, today);
                day = today.toString();
            }

            @Override public void onDestroy() { tasks.clear(); }
            @Override public int getCount() { return tasks.size(); }
            @Override public RemoteViews getLoadingView() { return null; }
            @Override public int getViewTypeCount() { return 1; }
            @Override public boolean hasStableIds() { return true; }
            @Override public long getItemId(int position) {
                return position >= 0 && position < tasks.size() ? rowId(tasks.get(position)) : -1;
            }

            @Override public RemoteViews getViewAt(int position) {
                if (position < 0 || position >= tasks.size()) return null;
                return row(getPackageName(), tasks.get(position), day);
            }
        };
    }

    static long rowId(PlannerStore.Entry task) {
        return java.util.UUID.fromString(task.id).getMostSignificantBits();
    }

    static RemoteViews row(String packageName, PlannerStore.Entry task, String day) {
        RemoteViews view = new RemoteViews(packageName, R.layout.today_widget_row);
        SpannableString title = new SpannableString(task.title);
        if (task.done) title.setSpan(new StrikethroughSpan(), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setTextViewText(R.id.widget_task_title, title);
        view.setTextColor(R.id.widget_task_title, task.done ? 0xff626479 : 0xff202037);
        view.setImageViewResource(R.id.widget_check, task.done ? R.drawable.widget_checked : R.drawable.widget_unchecked);
        view.setTextViewText(R.id.widget_task_time, task.time == null ? "" : CLOCK.format(task.time));
        view.setViewVisibility(R.id.widget_task_time, task.time == null ? View.GONE : View.VISIBLE);
        view.setContentDescription(R.id.widget_row,
                (task.done ? "Вернуть в работу: " : "Выполнить: ") + task.title);
        view.setOnClickFillInIntent(R.id.widget_row, new Intent()
                .putExtra(TodayWidget.TASK_ID, task.id)
                .putExtra(TodayWidget.CHECKED, !task.done)
                .putExtra(TodayWidget.DISPLAY_DAY, day));
        return view;
    }
}

package app.polaris.planner;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Parcel;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.util.List;

public final class TodayWidget extends AppWidgetProvider {
    // Larger lists keep using the paged adapter to stay below Binder's limit.
    private static final int MAX_EMBEDDED_ROWS = 192;
    private static final int MAX_EMBEDDED_BYTES = 384 * 1024;
    private static final String SET_COMPLETION = "app.polaris.planner.WIDGET_SET_COMPLETION";
    static final String TASK_ID = "widget_task_id";
    static final String CHECKED = "widget_checked";
    static final String DISPLAY_DAY = "widget_display_day";

    private static int[] ids(Context context) {
        return AppWidgetManager.getInstance(context)
                .getAppWidgetIds(new ComponentName(context, TodayWidget.class));
    }

    static boolean hasWidgets(Context context) {
        return ids(context).length > 0;
    }

    static void refresh(Context context) {
        int[] current = ids(context);
        if (current.length > 0) {
            publish(context, AppWidgetManager.getInstance(context), current);
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        publish(context, manager, widgetIds);
        PlannerAlarm.reschedule(context, new PlannerStore(context));
    }

    private static RemoteViews baseViews(Context context) {
        Intent add = new Intent(context, WidgetQuickAddActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent addTask = PendingIntent.getActivity(context, 0, add, PendingIntent.FLAG_IMMUTABLE);
        Intent change = new Intent(context, TodayWidget.class).setAction(SET_COMPLETION);
        int mutable = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent updateTask = PendingIntent.getBroadcast(context, 0, change, mutable);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.today_widget);
        views.setEmptyView(R.id.widget_tasks, R.id.widget_empty);
        views.setPendingIntentTemplate(R.id.widget_tasks, updateTask);
        views.setOnClickPendingIntent(R.id.widget_add, addTask);
        return views;
    }

    private static void publish(Context context, AppWidgetManager manager, int[] widgetIds) {
        if (widgetIds.length == 0) return;
        LocalDate today = LocalDate.now();
        if (Build.VERSION.SDK_INT >= 31) {
            List<PlannerStore.Entry> tasks = PlannerStore.widgetTasks(context, today);
            if (tasks.size() <= MAX_EMBEDDED_ROWS) {
                RemoteViews views = baseViews(context);
                CollectionViews.attach(views, context, tasks, today.toString());
                if (fitsInTransaction(views)) {
                    manager.updateAppWidget(widgetIds, views);
                    return;
                }
            }
        }
        for (int id : widgetIds) {
            Intent adapter = new Intent(context, TodayWidgetService.class)
                    .setData(Uri.parse("polaris://today-widget/" + id + "/" + today));
            RemoteViews views = baseViews(context);
            views.setRemoteAdapter(R.id.widget_tasks, adapter);
            manager.updateAppWidget(id, views);
        }
        manager.notifyAppWidgetViewDataChanged(widgetIds, R.id.widget_tasks);
    }

    private static boolean fitsInTransaction(RemoteViews views) {
        Parcel parcel = Parcel.obtain();
        try {
            views.writeToParcel(parcel, 0);
            return parcel.dataSize() <= MAX_EMBEDDED_BYTES;
        } finally {
            parcel.recycle();
        }
    }

    private static final class CollectionViews {
        static void attach(RemoteViews views, Context context, List<PlannerStore.Entry> tasks, String day) {
            RemoteViews.RemoteCollectionItems.Builder rows = new RemoteViews.RemoteCollectionItems.Builder()
                    .setHasStableIds(true)
                    .setViewTypeCount(1);
            for (PlannerStore.Entry task : tasks) {
                rows.addItem(TodayWidgetService.rowId(task), TodayWidgetService.row(context.getPackageName(), task, day));
            }
            views.setRemoteAdapter(R.id.widget_tasks, rows.build());
        }
    }

    @Override
    public void onDisabled(Context context) {
        PlannerAlarm.reschedule(context, new PlannerStore(context));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!SET_COMPLETION.equals(intent.getAction())) {
            super.onReceive(context, intent);
            return;
        }
        LocalDate today = LocalDate.now();
        String id = intent.getStringExtra(TASK_ID);
        if (id == null || !intent.hasExtra(CHECKED) || !today.toString().equals(intent.getStringExtra(DISPLAY_DAY))) {
            refresh(context);
            return;
        }
        PlannerStore store = new PlannerStore(context);
        boolean moved = PlannerStore.rollover(store.entries, today);
        boolean changed = PlannerStore.setCompletion(store.entries, id, today, intent.getBooleanExtra(CHECKED, false));
        if ((moved || changed) && store.save()) {
            if (changed) PlannerAlarm.clearNotification(context, id);
            PlannerAlarm.reschedule(context, store);
        }
        refresh(context);
    }
}

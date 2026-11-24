package app.polaris.planner;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class PlannerAlarm extends BroadcastReceiver {
    static final String CHANNEL = "planner_reminders";
    private static final String WAKE = "app.polaris.planner.WAKE";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    static boolean canNotify(Context context) {
        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled() &&
                (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    static boolean exactAllowed(Context context) {
        return Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    static void reschedule(Context context, PlannerStore store) {
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        PendingIntent wake = PendingIntent.getBroadcast(context, 0,
                new Intent(context, PlannerAlarm.class).setAction(WAKE), PendingIntent.FLAG_IMMUTABLE);
        alarmManager.cancel(wake);
        Instant now = Instant.now();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        Instant midnight = today.plusDays(1).atStartOfDay(zone).toInstant();
        Instant next = TodayWidget.hasWidgets(context) ? midnight : null;
        if (canNotify(context)) {
            for (PlannerStore.Entry entry : store.entries) {
                Instant at = entry.reminderAt(zone);
                if (at == null) continue;
                if (!entry.event && !entry.date.isAfter(today) && (next == null || midnight.isBefore(next))) {
                    next = midnight;
                }
                if (store.delivered.contains(entry.reminderKey()) || expired(entry, zone, now)) continue;
                if (at.isBefore(now)) at = now;
                if (next == null || at.isBefore(next)) next = at;
            }
        }
        if (next == null) return;

        long fireAt = Math.max(now.toEpochMilli() + 250, next.toEpochMilli());
        if (exactAllowed(context)) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, wake);
                return;
            } catch (SecurityException revoked) {
                // The permission can change between the check and scheduling.
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, wake);
    }

    private static boolean expired(PlannerStore.Entry entry, ZoneId zone, Instant now) {
        return !now.isBefore(entry.startsAt(zone).plusSeconds(300));
    }

    static void clearNotification(Context context, String id) {
        context.getSystemService(NotificationManager.class).cancel(id, 1);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        PlannerStore store = new PlannerStore(context);
        ZoneId zone = ZoneId.systemDefault();
        if (PlannerStore.rollover(store.entries, LocalDate.now(zone))) store.save();
        if (canNotify(context)) {
            Instant now = Instant.now();
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Напоминания «Поляриса»", NotificationManager.IMPORTANCE_HIGH));
            for (PlannerStore.Entry entry : store.entries) {
                Instant at = entry.reminderAt(zone);
                if (at == null || at.isAfter(now) || expired(entry, zone, now) ||
                        store.delivered.contains(entry.reminderKey())) continue;
                show(context, manager, entry);
                store.delivered.add(entry.reminderKey());
            }
            store.saveDelivered();
        }
        TodayWidget.refresh(context);
        reschedule(context, store);
    }

    private static void show(Context context, NotificationManager manager, PlannerStore.Entry entry) {
        Intent open = new Intent(context, MainActivity.class)
                .setData(Uri.parse("polaris://entry/" + entry.id))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("entry", entry.id);
        PendingIntent tap = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(entry.title)
                .setContentText((entry.event ? "Событие" : "Дело") + " · " + CLOCK.format(entry.time))
                .setColor(0xff6946e1).setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(tap).setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build();
        manager.notify(entry.id, 1, notification);
    }
}

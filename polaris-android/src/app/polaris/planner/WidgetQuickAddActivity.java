package app.polaris.planner;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.time.LocalDate;

public final class WidgetQuickAddActivity extends Activity {
    private EditText title;
    private AlertDialog dialog;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        showBackdrop();
        int padding = Math.round(22 * getResources().getDisplayMetrics().density);
        LinearLayout fields = new LinearLayout(this);
        fields.setPadding(padding, padding / 2, padding, 0);
        title = new EditText(this);
        title.setSingleLine(true);
        title.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        title.setImeOptions(EditorInfo.IME_ACTION_DONE);
        title.setHint("Например, позвонить в 18:30");
        title.setContentDescription("Название дела");
        title.setFilters(new InputFilter[]{new InputFilter.LengthFilter(154)});
        if (state != null) title.setText(state.getString("draft", ""));
        fields.addView(title, new LinearLayout.LayoutParams(-1, -2));
        dialog = new AlertDialog.Builder(this).setTitle("Дело на сегодня").setView(fields)
                .setNegativeButton("Отмена", null).setPositiveButton("Добавить", null)
                .create();
        dialog.setOnDismissListener(view -> finish());
        title.setOnEditorActionListener((view, action, key) -> {
            if (action != EditorInfo.IME_ACTION_DONE &&
                    (key == null || key.getKeyCode() != KeyEvent.KEYCODE_ENTER ||
                            key.getAction() != KeyEvent.ACTION_UP)) return false;
            addTask();
            return true;
        });
        title.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE |
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        dialog.getWindow().setDimAmount(0.24f);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> addTask());
    }

    private void showBackdrop() {
        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(0xffefedff);
        ImageView crystal = new ImageView(this);
        crystal.setImageResource(R.drawable.crystal);
        crystal.setImportantForAccessibility(ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO);
        crystal.setScaleType(ImageView.ScaleType.MATRIX);
        crystal.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            Drawable image = crystal.getDrawable();
            float width = right - left;
            float height = bottom - top;
            if (image == null || width <= 0 || height <= 0) return;
            float scale = Math.max(width / image.getIntrinsicWidth(), height / image.getIntrinsicHeight());
            Matrix crop = new Matrix();
            crop.setScale(scale, scale);
            crop.postTranslate((width - image.getIntrinsicWidth() * scale) * 0.75f,
                    (height - image.getIntrinsicHeight() * scale) / 2f);
            crystal.setImageMatrix(crop);
        });
        screen.addView(crystal, new FrameLayout.LayoutParams(-1, -1));
        setContentView(screen);
    }

    private void addTask() {
        LocalDate today = LocalDate.now();
        PlannerStore.Entry entry = PlannerStore.quickTask(title.getText().toString(), today);
        if (entry.title.isEmpty()) {
            title.setError("Введи название");
            return;
        }
        if (entry.title.length() > 140) {
            title.setError("Сократи название до 140 символов");
            return;
        }
        PlannerStore store = new PlannerStore(this);
        PlannerStore.rollover(store.entries, today);
        store.entries.add(0, entry);
        if (!store.save()) {
            Toast.makeText(this, "Не удалось сохранить дело", Toast.LENGTH_SHORT).show();
            return;
        }
        TodayWidget.refresh(this);
        PlannerAlarm.reschedule(this, store);
        dialog.dismiss();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("draft", title.getText().toString());
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        if (dialog != null) {
            dialog.setOnDismissListener(null);
            dialog.dismiss();
        }
        super.onDestroy();
    }
}

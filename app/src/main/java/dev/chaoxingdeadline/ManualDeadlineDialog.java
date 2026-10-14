package dev.chaoxingdeadline;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * "Add a to-do by hand" dialog. Everything the hook cannot see — an assignment announced
 * in class, a task from another platform — can be entered here and then joins the same
 * store, notifications and widgets as captured items.
 */
public final class ManualDeadlineDialog {

    private ManualDeadlineDialog() {
    }

    public static void show(Activity activity, Runnable onSaved) {
        Context context = activity;
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * context.getResources().getDisplayMetrics().density + 0.5f);
        form.setPadding(pad, pad / 2, pad, 0);

        EditText title = new EditText(context);
        title.setHint("标题，例如：第三章作业");
        title.setSingleLine(true);
        title.setInputType(InputType.TYPE_CLASS_TEXT);
        form.addView(title, fieldParams());

        Spinner type = new Spinner(context);
        type.setAdapter(new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"\u4f5c\u4e1a", "\u8003\u8bd5", "\u7ae0\u8282", "\u8bfe\u5802\u6d3b\u52a8"}));
        form.addView(labeled(context, "类型", type));

        List<String> courseChoices = new ArrayList<>();
        courseChoices.add("\uff08\u4e0d\u6307\u5b9a\uff09");
        try {
            courseChoices.addAll(new DeadlineStore(context).knownCourses());
        } catch (Throwable ignored) {
        }
        Spinner course = new Spinner(context);
        course.setAdapter(new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_dropdown_item,
                courseChoices.toArray(new String[0])));
        form.addView(labeled(context, "课程", course));

        Calendar due = Calendar.getInstance();
        due.add(Calendar.DAY_OF_MONTH, 1);
        due.set(Calendar.SECOND, 0);
        due.set(Calendar.MILLISECOND, 0);

        TextView dateButton = new TextView(context);
        TextView timeButton = new TextView(context);
        stylePickButton(context, dateButton);
        stylePickButton(context, timeButton);
        Runnable syncLabels = () -> {
            dateButton.setText(String.format(Locale.CHINA, "%04d-%02d-%02d",
                    due.get(Calendar.YEAR), due.get(Calendar.MONTH) + 1, due.get(Calendar.DAY_OF_MONTH)));
            timeButton.setText(String.format(Locale.CHINA, "%02d:%02d",
                    due.get(Calendar.HOUR_OF_DAY), due.get(Calendar.MINUTE)));
        };
        syncLabels.run();

        LinearLayout pickers = new LinearLayout(context);
        pickers.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1f);
        pickers.addView(labeled(context, "截止日期", dateButton), half);
        pickers.addView(labeled(context, "截止时间", timeButton), half);
        form.addView(pickers, fieldParams());

        dateButton.setOnClickListener(v -> new DatePickerDialog(context,
                (view, year, month, dayOfMonth) -> {
                    due.set(Calendar.YEAR, year);
                    due.set(Calendar.MONTH, month);
                    due.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                    syncLabels.run();
                },
                due.get(Calendar.YEAR), due.get(Calendar.MONTH), due.get(Calendar.DAY_OF_MONTH))
                .show());
        timeButton.setOnClickListener(v -> new TimePickerDialog(context,
                (view, hourOfDay, minute) -> {
                    due.set(Calendar.HOUR_OF_DAY, hourOfDay);
                    due.set(Calendar.MINUTE, minute);
                    syncLabels.run();
                },
                due.get(Calendar.HOUR_OF_DAY), due.get(Calendar.MINUTE), true)
                .show());

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("添加待办")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = title.getText() == null ? "" : title.getText().toString().trim();
            if (value.isEmpty()) {
                Toast.makeText(context, "请填写标题", Toast.LENGTH_SHORT).show();
                return;
            }
            long dueAt = due.getTimeInMillis();
            if (dueAt <= System.currentTimeMillis()) {
                Toast.makeText(context, "截止时间必须晚于现在", Toast.LENGTH_SHORT).show();
                return;
            }
            save(context, value,
                    String.valueOf(type.getSelectedItem()),
                    course.getSelectedItemPosition() == 0 ? "" : String.valueOf(course.getSelectedItem()),
                    dueAt);
            Toast.makeText(context, "已添加待办", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            if (onSaved != null) {
                onSaved.run();
            }
        });
    }

    private static void save(Context context, String title, String type, String course, long dueAt) {
        DeadlineItem item = new DeadlineItem();
        item.type = type;
        item.title = title;
        item.course = course;
        item.courseConfidence = course.isEmpty() ? 0 : 100;
        item.dueAt = dueAt;
        item.source = "manual";
        item.setSubmissionState(DeadlineItem.SUBMISSION_UNSUBMITTED);
        item.id = item.stableId();
        DeadlineStore store = new DeadlineStore(context);
        store.upsert(item);
        DeadlineNotifier.rescheduleAll(context);
        OverlayBridge.publish(context);
        DeadlineWidgetProvider.updateAll(context);
        context.sendBroadcast(new Intent(DeadlineReceiver.ACTION_REFRESH).setPackage(context.getPackageName()));
    }

    private static LinearLayout labeled(Context context, String label, android.view.View field) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView text = new TextView(context);
        text.setText(label);
        text.setTextSize(12);
        box.addView(text);
        box.addView(field);
        return box;
    }

    private static void stylePickButton(Context context, TextView button) {
        button.setTextSize(15);
        button.setPadding(0, 12, 0, 12);
        button.setFocusable(true);
        button.setClickable(true);
    }

    private static LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = 12;
        return params;
    }
}

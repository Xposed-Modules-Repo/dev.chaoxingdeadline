package dev.chaoxingdeadline;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Backup screen. Uses the system document picker (SAF) instead of storage permissions:
 * the user chooses where the file goes, and the module never touches shared storage.
 */
public final class BackupActivity extends BaseActivity {
    private static final int REQUEST_EXPORT_JSON = 1;
    private static final int REQUEST_EXPORT_ICS = 2;
    private static final int REQUEST_IMPORT_JSON = 3;

    private TextView summary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemBars();
        setContentView(buildContent());
        reload();
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiTheme.background(this));
        root.setPadding(dp(20), statusBarHeight() + dp(8), dp(20), dp(16));

        root.addView(titleBar("备份与导出"), new LinearLayout.LayoutParams(-1, dp(48)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, 0);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout note = card();
        note.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView body = text("模块没有联网权限，也不会把数据上传到任何地方。"
                + "备份文件由你自己选择保存位置：\n"
                + "· JSON 用于换机或重装后恢复本模块的待办与设置；\n"
                + "· ICS 可直接导入系统日历／小米日历，改用日历提醒。",
                12, false, UiTheme.muted(this));
        body.setLineSpacing(0f, 1.25f);
        note.addView(body, new LinearLayout.LayoutParams(-1, -2));
        content.addView(note, groupParams());

        content.addView(sectionHeader("当前数据"));
        LinearLayout statsCard = card();
        statsCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        summary = text("", 14, false, UiTheme.text(this));
        statsCard.addView(summary, new LinearLayout.LayoutParams(-1, -2));
        content.addView(statsCard, groupParams());

        content.addView(sectionHeader("导出"));
        LinearLayout exportGroup = card();
        View json = actionRow("↗", "导出备份（JSON）", "保存待办数据，用于恢复到本模块");
        json.setOnClickListener(v -> createDocument("application/json", REQUEST_EXPORT_JSON));
        exportGroup.addView(json);
        exportGroup.addView(divider());
        View ics = actionRow("📅", "导出日历（ICS）", "生成 .ics，可导入系统日历等应用");
        ics.setOnClickListener(v -> createDocument("text/calendar", REQUEST_EXPORT_ICS));
        exportGroup.addView(ics);
        content.addView(exportGroup, groupParams());

        content.addView(sectionHeader("导入"));
        LinearLayout importGroup = card();
        View restore = actionRow("↙", "从备份恢复（JSON）", "覆盖同名待办，其余条目保留");
        restore.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json");
            try {
                startActivityForResult(intent, REQUEST_IMPORT_JSON);
            } catch (Throwable throwable) {
                Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
            }
        });
        importGroup.addView(restore);
        importGroup.addView(divider());
        importGroup.addView(switchRow("同时恢复设置", "提醒档位、免打扰、课程屏蔽等一并覆盖；关闭则只恢复待办",
                AppSettings.backupRestoreSettings(this),
                (b, c) -> AppSettings.setBackupRestoreSettings(this, c)));
        content.addView(importGroup, groupParams());

        return root;
    }

    private View switchRow(String title, String subtitle, boolean checked,
                           CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(14), dp(13));
        row.setMinimumHeight(dp(68));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        texts.addView(text(title, 15, true, UiTheme.text(this)), new LinearLayout.LayoutParams(-1, -2));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 12, false, UiTheme.muted(this));
            sub.setPadding(0, dp(2), 0, 0);
            texts.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        Switch toggle = new Switch(this);
        toggle.setText("");
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);
        row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private void createDocument(String mimeType, int requestCode) {
        String name;
        if (requestCode == REQUEST_EXPORT_ICS) {
            name = "deadline-" + stamp() + ".ics";
        } else {
            name = "deadline-backup-" + stamp() + ".json";
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mimeType)
                .putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(intent, requestCode);
        } catch (Throwable throwable) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(new Date());
    }

    private void reload() {
        int count = 0;
        try {
            count = new DeadlineStore(this).countAll();
        } catch (Throwable ignored) {
        }
        summary.setText("共 " + count + " 条待办记录（含已完成与已截止）");
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_IMPORT_JSON) {
            importFrom(uri);
            return;
        }
        exportTo(uri, requestCode == REQUEST_EXPORT_ICS);
    }

    private void exportTo(Uri uri, boolean ics) {
        try {
            String payload;
            if (ics) {
                payload = DeadlineBackup.exportIcs(this);
            } else {
                payload = DeadlineBackup.exportJson(this).toString(2);
            }
            try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
                if (stream == null) {
                    Toast.makeText(this, "写入失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                stream.write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            Toast.makeText(this, "导出成功", Toast.LENGTH_SHORT).show();
        } catch (Throwable throwable) {
            Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void importFrom(Uri uri) {
        try {
            String payload;
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                if (stream == null) {
                    Toast.makeText(this, "读取失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                payload = readAll(stream);
            }
            int restored = DeadlineBackup.importJson(this, payload, false,
                    AppSettings.backupRestoreSettings(this));
            if (restored < 0) {
                Toast.makeText(this, "不是有效的备份文件", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, AppSettings.backupRestoreSettings(this)
                    ? "已恢复 " + restored + " 条待办与设置"
                    : "已恢复 " + restored + " 条待办", Toast.LENGTH_SHORT).show();
            reload();
        } catch (Throwable throwable) {
            Toast.makeText(this, "导入失败", Toast.LENGTH_SHORT).show();
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private LinearLayout titleBar(String titleValue) {
        LinearLayout top = hbox();
        TextView back = icon("←", 28, UiTheme.accent(this));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(44), -1));
        TextView title = text(titleValue, 22, true, UiTheme.text(this));
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, -1, 1f));
        top.addView(new View(this), new LinearLayout.LayoutParams(dp(44), -1));
        return top;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(UiTheme.divider(this));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1));
        p.leftMargin = dp(16);
        p.rightMargin = dp(16);
        v.setLayoutParams(p);
        return v;
    }

    private View actionRow(String iconValue, String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(15), dp(16), dp(15));
        row.setMinimumHeight(dp(70));
        TextView ic = icon(iconValue, 22, UiTheme.accent(this));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(36), dp(44));
        iconParams.rightMargin = dp(8);
        row.addView(ic, iconParams);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        texts.addView(text(title, 15, true, UiTheme.text(this)), new LinearLayout.LayoutParams(-1, -2));
        TextView sub = text(subtitle, 12, false, UiTheme.muted(this));
        sub.setPadding(0, dp(2), 0, 0);
        texts.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private LinearLayout.LayoutParams groupParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(16));
        return p;
    }
}

package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.TikConfigManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * TikConfigActivity — the TikConfig control center (Step 1).
 *
 * Drop-in path:
 *   TMessagesProj/src/main/java/org/telegram/ui/TikConfigActivity.java
 *
 * Features:
 * - Five sectioned categories (Chat, Media, Privacy, UI, Network).
 * - Live search filter in the action bar (filters by flag title and key).
 * - Amber "●" badge on any row whose value deviates from factory default.
 * - Toggle rows (BOOLEAN), slider rows (INT, real SeekBar), value-picker
 *   rows (STRING with options), free-text rows (STRING without options).
 * - Action rows: .tikcfg export/import, master reset, font/emoji import
 *   via Storage Access Framework, reset to system defaults, app restart.
 *
 * Registration (see TIKGRAM-SPEC.md for exact lines):
 * - ApplicationLoader.onCreate(): TikConfigManager.getInstance().init(this)
 * - SettingsActivity.fillItems(): SettingCell.Factory.of(100, ...) row
 * - SettingsActivity.onClick(): case 100 -> presentFragment(new TikConfigActivity())
 */
public class TikConfigActivity extends BaseFragment {

    private static final int REQ_EXPORT = 1001;
    private static final int REQ_IMPORT = 1002;
    private static final int REQ_FONT = 1003;
    private static final int REQ_EMOJI = 1004;

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHECK = 1;
    private static final int TYPE_VALUE = 2;
    private static final int TYPE_SLIDER = 3;
    private static final int TYPE_ACTION = 4;

    private static final int AMBER = 0xFFFFAA00;

    // ---------------- Row model ----------------

    private static class Row {
        int type;
        TikConfigManager.FlagDef flag; // null for headers and actions
        String header;                 // for TYPE_HEADER
        String actionId;               // for TYPE_ACTION
        String actionTitle;

        static Row header(String text) {
            Row r = new Row();
            r.type = TYPE_HEADER;
            r.header = text;
            return r;
        }

        static Row flag(TikConfigManager.FlagDef def) {
            Row r = new Row();
            r.type = def.type == TikConfigManager.Type.BOOLEAN ? TYPE_CHECK
                    : def.type == TikConfigManager.Type.INT ? TYPE_SLIDER : TYPE_VALUE;
            r.flag = def;
            return r;
        }

        static Row action(String id, String title) {
            Row r = new Row();
            r.type = TYPE_ACTION;
            r.actionId = id;
            r.actionTitle = title;
            return r;
        }
    }

    // ---------------- State ----------------

    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private final List<Row> rows = new ArrayList<>();
    private String query = "";

    private TikConfigManager cfg() {
        return TikConfigManager.getInstance();
    }

    // ---------------- Fragment lifecycle ----------------

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Tikgram");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem searchItem = menu.addItem(0, R.drawable.outline_header_search);
        searchItem.setIsSearchField(true);
        searchItem.setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
                // no-op; list stays visible under the search field
            }

            @Override
            public void onSearchCollapse() {
                query = "";
                rebuildRows();
                listAdapter.notifyDataSetChanged();
            }

            @Override
            public void onTextChanged(EditText editText) {
                query = editText.getText().toString().trim().toLowerCase();
                rebuildRows();
                listAdapter.notifyDataSetChanged();
            }
        });
        searchItem.setSearchFieldHint("Search flags");

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);
        listView.setOnItemClickListener((view, position) -> onRowClicked(position));

        rebuildRows();
        return listView;
    }

    // ---------------- Row building ----------------

    private void rebuildRows() {
        rows.clear();
        if (!query.isEmpty()) {
            rows.add(Row.header("Search results"));
            for (TikConfigManager.FlagDef def : cfg().allFlags()) {
                if (def.title.toLowerCase().contains(query) || def.key.contains(query)) {
                    rows.add(Row.flag(def));
                }
            }
            if (rows.size() == 1) {
                rows.add(Row.action("none", "No flags match your search"));
            }
            return;
        }
        for (TikConfigManager.Category category : TikConfigManager.Category.values()) {
            rows.add(Row.header(category.title));
            for (TikConfigManager.FlagDef def : cfg().flagsFor(category)) {
                rows.add(Row.flag(def));
            }
            if (category == TikConfigManager.Category.UI) {
                rows.add(Row.action("import_font", "Import custom font (.ttf / .otf)"));
                rows.add(Row.action("reset_font", "Reset font to system default"));
                rows.add(Row.action("import_emoji", "Import custom emoji pack"));
                rows.add(Row.action("reset_emoji", "Reset emoji to system default"));
            }
            if (category == TikConfigManager.Category.NETWORK) {
                // Tikgram: API identity override, typed by the user on-device only.
                // Row shows status only (Custom/Built-in) — the hash value itself is never shown here.
                rows.add(Row.action("api_credentials", cfg().hasApiOverride()
                        ? "Telegram API credentials (Custom)"
                        : "Telegram API credentials (Built-in)"));
            }
        }
        rows.add(Row.header("TikConfig file"));
        rows.add(Row.action("export", "Export config (.tikcfg)"));
        rows.add(Row.action("import", "Import config (.tikcfg)"));
        rows.add(Row.action("reset_all", "Master reset (factory defaults)"));
        rows.add(Row.action("restart", "Restart Tikgram"));
    }

    private CharSequence titled(TikConfigManager.FlagDef def) {
        if (cfg().isOverridden(def.key)) {
            SpannableStringBuilder sb = new SpannableStringBuilder("● ");
            sb.setSpan(new ForegroundColorSpan(AMBER), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(def.title);
            return sb;
        }
        return def.title;
    }

    // ---------------- Click handling ----------------

    private void onRowClicked(int position) {
        if (position < 0 || position >= rows.size()) {
            return;
        }
        Row row = rows.get(position);
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        switch (row.type) {
            case TYPE_CHECK: {
                boolean next = !cfg().getBoolean(row.flag.key);
                cfg().setBoolean(row.flag.key, next);
                listAdapter.notifyItemChanged(position);
                break;
            }
            case TYPE_VALUE: {
                if (row.flag.options != null) {
                    new AlertDialog.Builder(activity)
                            .setTitle(row.flag.title)
                            .setItems(row.flag.options, (dialog, which) -> {
                                cfg().setString(row.flag.key, row.flag.options[which]);
                                listAdapter.notifyItemChanged(position);
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                } else {
                    EditText input = new EditText(activity);
                    input.setText(cfg().getString(row.flag.key));
                    new AlertDialog.Builder(activity)
                            .setTitle(row.flag.title)
                            .setView(input)
                            .setPositiveButton("Save", (dialog, which) ->
                                    cfg().setString(row.flag.key, input.getText().toString()))
                            .setNegativeButton("Cancel", null)
                            .show();
                }
                break;
            }
            case TYPE_ACTION:
                onActionClicked(row.actionId, activity);
                break;
            default:
                break;
        }
    }

    private void onActionClicked(String actionId, Activity activity) {
        switch (actionId) {
            case "export": {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, "tikgram.tikcfg");
                startActivityForResult(intent, REQ_EXPORT);
                break;
            }
            case "import": {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                startActivityForResult(intent, REQ_IMPORT);
                break;
            }
            case "reset_all":
                cfg().resetAll();
                rebuildRows();
                listAdapter.notifyDataSetChanged();
                toast("All TikConfig overrides reset");
                break;
            case "restart":
                TikConfigManager.restartApp(activity);
                break;
            case "api_credentials":
                // Shared dialog; no auto-restart here (a live session is running) —
                // the user restarts via the Restart row, then logs in again.
                TikApiCredentialsDialog.show(activity, false, () -> {
                    rebuildRows();
                    listAdapter.notifyDataSetChanged();
                    toast("Credentials updated — restart Tikgram and log in again");
                });
                break;
            case "import_font": {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"font/ttf", "font/otf"});
                startActivityForResult(intent, REQ_FONT);
                break;
            }
            case "reset_font":
                deleteLocalFile("tikgram_font.ttf");
                cfg().setString("custom_font_path", "");
                cfg().setBoolean("custom_font_enabled", false);
                rebuildRows();
                listAdapter.notifyDataSetChanged();
                toast("Font reset to system default");
                break;
            case "import_emoji": {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, REQ_EMOJI);
                break;
            }
            case "reset_emoji":
                deleteLocalFile("tikgram_emoji.pack");
                cfg().setString("custom_emoji_path", "");
                cfg().setBoolean("custom_emoji_enabled", false);
                rebuildRows();
                listAdapter.notifyDataSetChanged();
                toast("Emoji reset to system default");
                break;
            default:
                break;
        }
    }

    // ---------------- SAF results ----------------

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        Activity activity = getParentActivity();
        if (activity == null || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            switch (requestCode) {
                case REQ_EXPORT: {
                    try (OutputStream out = activity.getContentResolver().openOutputStream(uri)) {
                        if (out != null) {
                            out.write(cfg().exportJson().getBytes("UTF-8"));
                            toast("Config exported");
                        }
                    }
                    break;
                }
                case REQ_IMPORT: {
                    String json = readAll(activity, uri);
                    if (json != null && cfg().importJson(json)) {
                        rebuildRows();
                        listAdapter.notifyDataSetChanged();
                        toast("Config imported");
                    } else {
                        toast("Invalid .tikcfg file — nothing applied");
                    }
                    break;
                }
                case REQ_FONT: {
                    String name = displayName(activity, uri);
                    if (name != null && !(name.endsWith(".ttf") || name.endsWith(".otf"))) {
                        toast("Pick a .ttf or .otf font file");
                        return;
                    }
                    File dest = new File(activity.getFilesDir(), "tikgram_font.ttf");
                    copyUri(activity, uri, dest);
                    cfg().setString("custom_font_path", dest.getAbsolutePath());
                    cfg().setBoolean("custom_font_enabled", true);
                    rebuildRows();
                    listAdapter.notifyDataSetChanged();
                    toast("Font imported — restart to apply");
                    break;
                }
                case REQ_EMOJI: {
                    File dest = new File(activity.getFilesDir(), "tikgram_emoji.pack");
                    copyUri(activity, uri, dest);
                    cfg().setString("custom_emoji_path", dest.getAbsolutePath());
                    cfg().setBoolean("custom_emoji_enabled", true);
                    rebuildRows();
                    listAdapter.notifyDataSetChanged();
                    toast("Emoji pack saved (applied at build — see spec)");
                    break;
                }
                default:
                    break;
            }
        } catch (Exception e) {
            toast("Operation failed");
        }
    }

    private String readAll(Activity activity, Uri uri) {
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void copyUri(Activity activity, Uri uri, File dest) throws Exception {
        try (InputStream in = activity.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) {
                throw new Exception("cannot open source");
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    private String displayName(Activity activity, Uri uri) {
        try (Cursor cursor = activity.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private void deleteLocalFile(String name) {
        Activity activity = getParentActivity();
        if (activity != null) {
            new File(activity.getFilesDir(), name).delete();
        }
    }

    private void toast(String text) {
        Activity activity = getParentActivity();
        if (activity != null) {
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- Adapter ----------------

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() != TYPE_HEADER;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_HEADER:
                    view = new HeaderCell(context);
                    break;
                case TYPE_CHECK:
                    view = new TextCheckCell(context);
                    break;
                case TYPE_VALUE:
                case TYPE_ACTION:
                    view = new TextSettingsCell(context);
                    break;
                case TYPE_SLIDER:
                default:
                    view = createSliderRow();
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        private View createSliderRow() {
            LinearLayout layout = new LinearLayout(context);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(10),
                    AndroidUtilities.dp(20), AndroidUtilities.dp(10));

            LinearLayout top = new LinearLayout(context);
            top.setOrientation(LinearLayout.HORIZONTAL);
            TextView titleView = new TextView(context);
            titleView.setTextSize(16);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            top.addView(titleView, titleParams);
            TextView valueView = new TextView(context);
            valueView.setTextSize(16);
            valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            top.addView(valueView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            layout.addView(top, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            SeekBar seekBar = new SeekBar(context);
            layout.addView(seekBar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            layout.setTag(new SliderHolder(titleView, valueView, seekBar));
            return layout;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            boolean divider = position < rows.size() - 1
                    && rows.get(position + 1).type != TYPE_HEADER;
            switch (row.type) {
                case TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText(row.header);
                    break;
                case TYPE_CHECK:
                    ((TextCheckCell) holder.itemView).setTextAndCheck(
                            titled(row.flag), cfg().getBoolean(row.flag.key), divider);
                    break;
                case TYPE_VALUE: {
                    String value = row.flag.options != null
                            ? cfg().getString(row.flag.key)
                            : (cfg().getString(row.flag.key).isEmpty() ? "Not set"
                                    : cfg().getString(row.flag.key));
                    ((TextSettingsCell) holder.itemView).setTextAndValue(
                            titled(row.flag), value, divider);
                    break;
                }
                case TYPE_ACTION:
                    ((TextSettingsCell) holder.itemView).setText(row.actionTitle, divider);
                    break;
                case TYPE_SLIDER: {
                    SliderHolder h = (SliderHolder) holder.itemView.getTag();
                    TikConfigManager.FlagDef def = row.flag;
                    int steps = Math.max(1, (def.max - def.min) / Math.max(1, def.step));
                    int current = cfg().getInt(def.key);
                    h.title.setText(titled(def));
                    h.value.setText(String.valueOf(current));
                    h.seek.setMax(steps);
                    h.seek.setProgress((current - def.min) / Math.max(1, def.step));
                    h.seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                            h.value.setText(String.valueOf(def.min + progress * def.step));
                        }

                        @Override
                        public void onStartTrackingTouch(SeekBar seekBar) {
                        }

                        @Override
                        public void onStopTrackingTouch(SeekBar seekBar) {
                            cfg().setInt(def.key, def.min + seekBar.getProgress() * def.step);
                            notifyItemChanged(holder.getAdapterPosition());
                        }
                    });
                    break;
                }
                default:
                    break;
            }
        }
    }

    private static class SliderHolder {
        final TextView title;
        final TextView value;
        final SeekBar seek;

        SliderHolder(TextView title, TextView value, SeekBar seek) {
            this.title = title;
            this.value = value;
            this.seek = seek;
        }
    }
}

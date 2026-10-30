package org.telegram.ui;

import android.app.Activity;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.TikConfigManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Tikgram: shared "Telegram API credentials" dialog.
 *
 * Used from every pre-login / settings entry point (IntroActivity "..."
 * overflow, LoginActivity phone screen, TikConfigActivity Network section)
 * so the logic exists exactly once.
 *
 * The user types his OWN api_id and api_hash here, on his device. Values
 * are stored in private SharedPreferences only; they are never baked into
 * the code or the repo, and they are never exported to .tikcfg files.
 * Empty/unset == shipped built-in values (BuildVars.APP_ID / APP_HASH).
 *
 * Changing the identity requires an app restart AND a fresh login: the
 * native transport fixes its api_id at process start (ConnectionsManager
 * native initConnection), and the stored session is tied to the old
 * identity. On pre-login screens the caller passes restartAfterSave=true
 * so the new identity is picked up immediately; in Settings the caller
 * passes false and tells the user to restart (there is a Restart row).
 */
public class TikApiCredentialsDialog {

    /**
     * @param activity host activity (must be non-null)
     * @param restartAfterSave restart the app right after a successful
     *                         Save/Reset (pre-login screens)
     * @param onChanged run after a successful Save/Reset, before any
     *                  restart (e.g. refresh a row label). May be null.
     */
    public static void show(Activity activity, boolean restartAfterSave, Runnable onChanged) {
        if (activity == null) {
            return;
        }
        TikConfigManager cfg = TikConfigManager.getInstance();
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(16);
        layout.setPadding(pad, pad, pad, 0);

        EditText idInput = new EditText(activity);
        idInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        idInput.setHint("api_id (numbers only)");
        int savedId = cfg.getApiIdOverride();
        if (savedId > 0) {
            idInput.setText(String.valueOf(savedId));
        }
        layout.addView(idInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        EditText hashInput = new EditText(activity);
        hashInput.setInputType(InputType.TYPE_CLASS_TEXT);
        hashInput.setHint("api_hash");
        String savedHash = cfg.getApiHashOverride();
        if (!savedHash.isEmpty()) {
            hashInput.setText(savedHash);
        }
        layout.addView(hashInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        new AlertDialog.Builder(activity)
                .setTitle("Telegram API credentials")
                .setMessage("Type your own api_id and api_hash (from my.telegram.org). They stay on this device only. Changing them requires a restart and a fresh login.")
                .setView(layout)
                .setPositiveButton("Save", (dialog, which) -> {
                    String idStr = idInput.getText().toString().trim();
                    String hashStr = hashInput.getText().toString().trim();
                    if (idStr.isEmpty() && hashStr.isEmpty()) {
                        cfg.resetApiCredentials();
                    } else {
                        int id;
                        try {
                            id = Integer.parseInt(idStr);
                        } catch (NumberFormatException e) {
                            Toast.makeText(activity, "Invalid api_id — numbers only", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (id <= 0 || hashStr.isEmpty()) {
                            Toast.makeText(activity, "Enter both api_id and api_hash", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        cfg.setApiCredentials(id, hashStr);
                    }
                    if (onChanged != null) {
                        onChanged.run();
                    }
                    if (restartAfterSave) {
                        TikConfigManager.restartApp(activity);
                    }
                })
                .setNeutralButton("Reset to built-in", (dialog, which) -> {
                    cfg.resetApiCredentials();
                    if (onChanged != null) {
                        onChanged.run();
                    }
                    if (restartAfterSave) {
                        TikConfigManager.restartApp(activity);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}

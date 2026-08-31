package com.ethosprotocol.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ethosprotocol.R

/**
 * Widget configuration activity (#245).
 *
 * Launched automatically when the user adds a new Vault Status widget to the home screen.
 * Lets the user pin a specific vault to this widget instance; if the user cancels, the
 * widget falls back to the most-urgent vault (urgency selection).
 *
 * The chosen vault ID is persisted to per-widget SharedPreferences via
 * [VaultStatusWidget.saveSelectedVaultId] (#246), which [VaultWidgetUpdateWorker] reads
 * when deciding which vault data to render for each widget instance.
 */
class VaultWidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Returning RESULT_CANCELED causes the launcher to remove the widget if the activity
        // finishes before setting RESULT_OK — set this as the default before anything else
        // so a crash or unexpected back-press doesn't leave an orphan widget entry.
        setResult(Activity.RESULT_CANCELED)

        // Extract the widget ID that launched this activity.
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // Load the vault ID list saved by VaultWidgetUpdateWorker after its last successful
        // fetch. If no vaults are available yet, show a message and fall back to urgency.
        val vaultIds = loadVaultIdList(this)

        if (vaultIds.isEmpty()) {
            Toast.makeText(this, getString(R.string.widget_no_vaults), Toast.LENGTH_SHORT).show()
            finishWithUrgencyFallback()
            return
        }

        // Build a simple full-screen layout: title + list of vault IDs.
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        val title = TextView(this).apply {
            text = getString(R.string.widget_configure_title)
            textSize = 18f
            setPadding(0, 0, 0, 24)
        }
        layout.addView(title)

        val listView = ListView(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, vaultIds)
        listView.adapter = adapter
        layout.addView(listView)

        setContentView(layout)

        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedVaultId = vaultIds[position]
            onVaultSelected(selectedVaultId)
        }
    }

    private fun onVaultSelected(vaultId: String) {
        // Persist the user's choice to per-widget prefs so the worker can read it.
        VaultStatusWidget.saveSelectedVaultId(this, appWidgetId, vaultId)

        // Trigger an immediate widget update so the newly configured vault is visible right away.
        val manager = AppWidgetManager.getInstance(this)
        VaultStatusWidget.updateWidget(this, manager, appWidgetId)

        // Signal success to the launcher so the widget is pinned to the home screen.
        val resultValue = Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        setResult(Activity.RESULT_OK, resultValue)
        finish()
    }

    /** User pressed back or otherwise cancelled — widget falls back to urgency selection. */
    private fun finishWithUrgencyFallback() {
        // RESULT_CANCELED was already set in onCreate; just finish so the launcher
        // knows no vault was pinned and the urgency default will be used.
        finish()
    }

    companion object {
        /** Loads the comma-separated vault ID list saved by [VaultWidgetUpdateWorker]. */
        fun loadVaultIdList(context: Context): List<String> {
            val raw = context.getSharedPreferences(
                VaultStatusWidget.PREFS_SHARED, Context.MODE_PRIVATE
            ).getString(VaultStatusWidget.KEY_VAULT_ID_LIST, null)
            return if (raw.isNullOrBlank()) emptyList()
            else raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}

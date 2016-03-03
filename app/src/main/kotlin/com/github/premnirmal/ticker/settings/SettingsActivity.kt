package com.github.premnirmal.ticker.settings

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.CheckBoxPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceActivity
import android.support.v7.widget.Toolbar
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.view.LayoutInflater
import android.widget.TextView
import com.devpaul.filepickerlibrary.FilePickerActivity
import com.github.premnirmal.ticker.InAppMessage
import com.github.premnirmal.ticker.Injector
import com.github.premnirmal.ticker.Tools
import com.github.premnirmal.ticker.model.IStocksProvider
import com.github.premnirmal.ticker.widget.StockWidget
import com.github.premnirmal.tickerwidget.BuildConfig
import com.github.premnirmal.tickerwidget.R
import uk.co.chrisjenx.calligraphy.CalligraphyContextWrapper
import uk.co.chrisjenx.calligraphy.CalligraphyTypefaceSpan
import uk.co.chrisjenx.calligraphy.TypefaceUtils
import javax.inject.Inject
import com.github.premnirmal.ticker.CrashLogger
import com.github.premnirmal.ticker.Analytics

/**
 * Created by premnirmal on 2/27/16.
 */
class SettingsActivity : PreferenceActivity() {

    @Inject
    lateinit internal var stocksProvider: IStocksProvider

    @Inject
    lateinit internal var preferences: SharedPreferences

    private inner open class DefaultPreferenceChangeListener : Preference.OnPreferenceChangeListener {

        override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
            return false
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        val intent = Intent(applicationContext, StockWidget::class.java)
        intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        val widgetManager = AppWidgetManager.getInstance(this)
        val ids = widgetManager.getAppWidgetIds(ComponentName(this, StockWidget::class.java))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            widgetManager.notifyAppWidgetViewDataChanged(ids, R.id.list)
        }
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        sendBroadcast(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Injector.inject(this)
        setContentView(R.layout.activity_preferences)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(CalligraphyContextWrapper.wrap(newBase))
    }

    protected override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        val bar = findViewById(R.id.toolbar) as Toolbar
        bar.setNavigationOnClickListener(android.view.View.OnClickListener { finish() })

        listView.addFooterView(LayoutInflater.from(this).inflate(R.layout.preferences_footer, null, false))

        val versionView = findViewById(R.id.version) as TextView
        val sBuilder = SpannableStringBuilder()
        sBuilder.append("v" + BuildConfig.VERSION_NAME)
        val typefaceSpan = CalligraphyTypefaceSpan(TypefaceUtils.load(getAssets(), "fonts/alegreya-black-italic.ttf"))
        sBuilder.setSpan(typefaceSpan, 0, sBuilder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        versionView.text = sBuilder
        setupSimplePreferencesScreen()
    }

    /**
     * Shows the simplified settings UI if the device configuration if the
     * device configuration dictates that a simplified, single-pane UI should be
     * shown.
     */
    private fun setupSimplePreferencesScreen() {
        // In the simplified UI, fragments are not used at all and we instead
        // use the older PreferenceActivity APIs.

        // Add 'general' preferences.
        addPreferencesFromResource(R.xml.prefs)

        run({
            val exportPref = findPreference("EXPORT")
            exportPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                object : FileExportTask() {
                    override fun onPostExecute(result: String?) {
                        if (result == null) {
                            showDialog(getString(R.string.error_exporting))
                            CrashLogger.logException(Throwable("Error exporting tickers"))
                        } else {
                            showDialog("Exported to $result")
                        }
                    }
                }.execute(stocksProvider.getTickers())
                true
            }
        })

        run({
            val sharePref = findPreference("SHARE")
            sharePref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                Analytics.trackSettingsChange("SHARE", TextUtils.join(",", stocksProvider.getTickers().toTypedArray()))
                val file = Tools.tickersFile
                if (file.exists()) {
                    shareTickers()
                } else {
                    object : FileExportTask() {
                        override fun onPostExecute(result: String?) {
                            if (result == null) {
                                showDialog(getString(R.string.error_sharing))
                                CrashLogger.logException(Throwable("Error sharing tickers"))
                            } else {
                                shareTickers()
                            }
                        }
                    }.execute(stocksProvider.getTickers())
                }
                true
            }
        })

        run({
            val importPref = findPreference("IMPORT")
            importPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                val filePickerIntent = Intent(this@SettingsActivity, FilePickerActivity::class.java)
                filePickerIntent.putExtra(FilePickerActivity.REQUEST_CODE, FilePickerActivity.REQUEST_FILE)
                filePickerIntent.putExtra(FilePickerActivity.INTENT_EXTRA_COLOR_ID, R.color.color_primary)
                startActivityForResult(filePickerIntent, FilePickerActivity.REQUEST_FILE)
                true
            }
        })

        run({
            val fontSizePreference = findPreference(Tools.FONT_SIZE) as ListPreference
            val size = preferences.getInt(Tools.FONT_SIZE, 1)
            fontSizePreference.setValueIndex(size)
            fontSizePreference.summary = fontSizePreference.entries[size]
            fontSizePreference.onPreferenceChangeListener = object : DefaultPreferenceChangeListener() {
                override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
                    super.onPreferenceChange(preference, newValue)
                    val stringValue = newValue.toString()
                    val listPreference = preference as ListPreference
                    val index = listPreference.findIndexOfValue(stringValue)
                    preferences.edit().remove(Tools.FONT_SIZE).putInt(Tools.FONT_SIZE, index).apply()
                    broadcastUpdateWidget()
                    fontSizePreference.summary = fontSizePreference.getEntries()[index]
                    InAppMessage.showMessage(this@SettingsActivity, R.string.text_size_updated_message)
                    return true
                }
            }
        })

        run({
            val bgPreference = findPreference(Tools.WIDGET_BG) as ListPreference
            val bgIndex = preferences.getInt(Tools.WIDGET_BG, 0)
            bgPreference.setValueIndex(bgIndex)
            bgPreference.summary = bgPreference.entries[bgIndex]
            bgPreference.onPreferenceChangeListener = object : DefaultPreferenceChangeListener() {
                override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
                    super.onPreferenceChange(preference, newValue)
                    val stringValue = newValue.toString()
                    val listPreference = preference as ListPreference
                    val index = listPreference.findIndexOfValue(stringValue)
                    preferences.edit().putInt(Tools.WIDGET_BG, index).apply()
                    broadcastUpdateWidget()
                    bgPreference.summary = bgPreference.getEntries()[index]
                    InAppMessage.showMessage(this@SettingsActivity, R.string.bg_updated_message)
                    return true
                }
            }
        })

        run({
            val layoutTypePref = findPreference(Tools.LAYOUT_TYPE) as ListPreference
            val typeIndex = preferences.getInt(Tools.LAYOUT_TYPE, 0)
            layoutTypePref.setValueIndex(typeIndex)
            layoutTypePref.summary = layoutTypePref.entries[typeIndex]
            layoutTypePref.onPreferenceChangeListener = object : DefaultPreferenceChangeListener() {
                override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
                    super.onPreferenceChange(preference, newValue)
                    val stringValue = newValue.toString()
                    val listPreference = preference as ListPreference
                    val index = listPreference.findIndexOfValue(stringValue)
                    preferences.edit().putInt(Tools.LAYOUT_TYPE, index).apply()
                    broadcastUpdateWidget()
                    layoutTypePref.summary = layoutTypePref.entries[index]
                    InAppMessage.showMessage(this@SettingsActivity, R.string.layout_updated_message)

                    if (index == 2) {
                        showDialog(getString(R.string.change_instructions))
                    }

                    return true
                }
            }
        })

        run({
            val textColorPreference = findPreference(Tools.TEXT_COLOR) as ListPreference
            val colorIndex = preferences.getInt(Tools.TEXT_COLOR, 0)
            textColorPreference.setValueIndex(colorIndex)
            textColorPreference.summary = textColorPreference.entries[colorIndex]
            textColorPreference.onPreferenceChangeListener = object : DefaultPreferenceChangeListener() {
                override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
                    super.onPreferenceChange(preference, newValue)
                    val stringValue = newValue.toString()
                    val listPreference = preference as ListPreference
                    val index = listPreference.findIndexOfValue(stringValue)
                    preferences.edit().putInt(Tools.TEXT_COLOR, index).apply()
                    broadcastUpdateWidget()
                    val color = textColorPreference.entries[index]
                    textColorPreference.summary = color
                    InAppMessage.showMessage(this@SettingsActivity, R.string.text_coor_updated_message)
                    return true
                }
            }
        })

        run({
            val refreshPreference = findPreference(Tools.UPDATE_INTERVAL) as ListPreference
            val refreshIndex = preferences.getInt(Tools.UPDATE_INTERVAL, 1)
            refreshPreference.setValueIndex(refreshIndex)
            refreshPreference.summary = refreshPreference.entries[refreshIndex]
            refreshPreference.onPreferenceChangeListener = object : DefaultPreferenceChangeListener() {
                override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
                    super.onPreferenceChange(preference, newValue)
                    val stringValue = newValue.toString()
                    val listPreference = preference as ListPreference
                    val index = listPreference.findIndexOfValue(stringValue)
                    preferences.edit().putInt(Tools.UPDATE_INTERVAL, index).apply()
                    broadcastUpdateWidget()
                    refreshPreference.summary = refreshPreference.entries[index]
                    InAppMessage.showMessage(this@SettingsActivity, R.string.refresh_updated_message)
                    return true
                }
            }
        })

        run({
            val autoSortPreference = findPreference(Tools.SETTING_AUTOSORT) as CheckBoxPreference
            val autoSort = Tools.autoSortEnabled()
            autoSortPreference.isChecked = autoSort
            autoSortPreference.onPreferenceChangeListener = object : DefaultPreferenceChangeListener() {
                override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
                    super.onPreferenceChange(preference, newValue)
                    val checked = newValue as Boolean
                    preferences.edit().putBoolean(Tools.SETTING_AUTOSORT, checked).apply()
                    return true
                }
            }
        })

        run({
            val boldChangePreference = findPreference(Tools.BOLD_CHANGE) as CheckBoxPreference
            val bold = Tools.boldEnabled()
            boldChangePreference.isChecked = bold
            boldChangePreference.onPreferenceChangeListener = object : DefaultPreferenceChangeListener() {
                override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
                    super.onPreferenceChange(preference, newValue)
                    val checked = newValue as Boolean
                    preferences.edit().putBoolean(Tools.BOLD_CHANGE, checked).apply()
                    return true
                }
            }
        })

        run({
            val startTimePref = findPreference(Tools.START_TIME) as TimePreference
            startTimePref.summary = preferences.getString(Tools.START_TIME, "09:30")
            startTimePref.onPreferenceChangeListener = object : DefaultPreferenceChangeListener() {
                override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
                    super.onPreferenceChange(preference, newValue)
                    preferences.edit().putString(Tools.START_TIME, newValue.toString()).apply()
                    startTimePref.summary = newValue.toString()
                    InAppMessage.showMessage(this@SettingsActivity, R.string.start_time_updated)
                    return true
                }
            }
        })

        run({
            val endTimePref = findPreference(Tools.END_TIME) as TimePreference
            endTimePref.summary = preferences.getString(Tools.END_TIME, "16:30")
            endTimePref.onPreferenceChangeListener = object : DefaultPreferenceChangeListener() {
                override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
                    super.onPreferenceChange(preference, newValue)
                    preferences.edit().putString(Tools.END_TIME, newValue.toString()).apply()
                    endTimePref.summary = newValue.toString()
                    InAppMessage.showMessage(this@SettingsActivity, R.string.end_time_updated)
                    return true
                }
            }
        })
    }

    private fun shareTickers() {
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_EMAIL, arrayOf<String>())
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.my_stock_portfolio))
        intent.putExtra(Intent.EXTRA_TEXT, getString(R.string.share_email_subject))
        val file = Tools.tickersFile
        if (!file.exists() || !file.canRead()) {
            showDialog(getString(R.string.error_sharing))
            CrashLogger.logException(Throwable("Error sharing tickers"))
            return
        }
        val uri = Uri.fromFile(file)
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        startActivity(Intent.createChooser(intent, getString(R.string.action_share)))
    }

    private fun broadcastUpdateWidget() {
        val intent = Intent(applicationContext, StockWidget::class.java)
        intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        val widgetManager = AppWidgetManager.getInstance(applicationContext)
        val ids = widgetManager.getAppWidgetIds(ComponentName(applicationContext, StockWidget::class.java))
        widgetManager.notifyAppWidgetViewDataChanged(ids, android.R.id.list)
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        sendBroadcast(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FilePickerActivity.REQUEST_FILE && resultCode == Activity.RESULT_OK) {
            val filePath = data?.getStringExtra(FilePickerActivity.FILE_EXTRA_DATA_PATH)
            if (filePath != null) {
                object : FileImportTask(stocksProvider) {
                    override fun onPostExecute(result: Boolean?) {
                        if (result != null && result) {
                            showDialog(getString(R.string.ticker_import_success))
                        } else {
                            showDialog(getString(R.string.ticker_import_fail))
                        }
                    }
                }.execute(filePath)
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun showDialog(message: String) {
        AlertDialog.Builder(this).setMessage(message).setNeutralButton("OK",
                { dialog, which -> dialog.dismiss() }).show()
    }
}
package org.wikipedia.settings

import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.wikipedia.BuildConfig
import org.wikipedia.Constants
import org.wikipedia.R
import org.wikipedia.WikipediaApp
import org.wikipedia.analytics.eventplatform.RecommendedReadingListEvent
import org.wikipedia.auth.AccountUtil
import org.wikipedia.feed.configure.ConfigureActivity
import org.wikipedia.login.LoginActivity
import org.wikipedia.readinglist.recommended.RecommendedReadingListOnboardingActivity
import org.wikipedia.readinglist.recommended.RecommendedReadingListSettingsActivity
import org.wikipedia.readinglist.recommended.RecommendedReadingListSource
import org.wikipedia.readinglist.sync.ReadingListSyncAdapter
import org.wikipedia.settings.languages.WikipediaLanguagesActivity
import org.wikipedia.theme.ThemeFittingRoomActivity
import org.wikipedia.util.FeedbackUtil

/** UI code for app settings used by PreferenceFragment.  */
internal class SettingsPreferenceLoader(fragment: PreferenceFragmentCompat) : BasePreferenceLoader(fragment) {
    override fun loadPreferences() {
        loadPreferences(R.xml.preferences)
        if (RemoteConfig.config.disableReadingListSync) {
            findPreference(R.string.preference_category_sync).isVisible = false
            findPreference(R.string.preference_key_sync_reading_lists).isVisible = false
        }
        findPreference(R.string.preference_key_sync_reading_lists).onPreferenceChangeListener = SyncReadingListsListener()
        loadPreferences(R.xml.preferences_about)
        updateLanguagePrefSummary()
        findPreference(R.string.preference_key_language).onPreferenceClickListener = Preference.OnPreferenceClickListener {
            activity.startActivityForResult(WikipediaLanguagesActivity.newIntent(activity, Constants.InvokeSource.SETTINGS),
                    Constants.ACTIVITY_REQUEST_ADD_A_LANGUAGE)
            true
        }
        findPreference(R.string.preference_key_customize_explore_feed).onPreferenceClickListener = Preference.OnPreferenceClickListener {
            activity.startActivityForResult(ConfigureActivity.newIntent(activity, Constants.InvokeSource.NAV_MENU.ordinal),
                    Constants.ACTIVITY_REQUEST_FEED_CONFIGURE)
            true
        }
        findPreference(R.string.preference_key_color_theme).let {
            it.setSummary(WikipediaApp.instance.currentTheme.nameId)
            it.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                activity.startActivity(ThemeFittingRoomActivity.newIntent(activity))
                true
            }
        }

        findPreference(R.string.preference_key_about_wikipedia_app).onPreferenceClickListener = Preference.OnPreferenceClickListener {
            activity.startActivity(Intent(activity, AboutActivity::class.java))
            true
        }
        findPreference(R.string.preference_key_send_feedback).onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                FeedbackUtil.composeEmail(
                    activity,
                    subject = "Android App ${BuildConfig.VERSION_NAME} Feedback",
                    body = deviceInformation()
                )
                true
        }
        findPreference(R.string.preference_key_recommended_reading_list_enabled).onPreferenceClickListener = Preference.OnPreferenceClickListener {
            RecommendedReadingListEvent.submit("discover_click", "global_settings")
            if (Prefs.recommendedReadingListInterests.isEmpty() &&
                Prefs.recommendedReadingListSource == RecommendedReadingListSource.INTERESTS) {
                activity.startActivity(RecommendedReadingListOnboardingActivity.newIntent(activity))
            } else {
                activity.startActivity(RecommendedReadingListSettingsActivity.newIntent(activity))
            }
            true
        }

        if (AccountUtil.isLoggedIn) {
            loadPreferences(R.xml.preferences_account)
            (findPreference(R.string.preference_key_logout) as LogoutPreference).activity = activity
        }

        if (Prefs.donationResults.isNotEmpty()) {
            setupDeleteLocalDonationHistoryPreference()
        }
    }

    private fun deviceInformation(): String {
        return "\n\nVersion: ${BuildConfig.VERSION_NAME} \nDevice: ${Build.BRAND} ${Build.MODEL} (SDK: ${Build.VERSION.SDK_INT})\n"
    }

    private fun setupDeleteLocalDonationHistoryPreference() {
        findPreference(R.string.preference_key_delete_local_donation_history).let {
            it.isVisible = true
            it.onPreferenceClickListener = Preference.OnPreferenceClickListener { preference ->
                MaterialAlertDialogBuilder(activity)
                    .setTitle(activity.getString(R.string.dialog_confirm_delete_donation_history_title))
                    .setMessage(activity.getString(R.string.dialog_confirm_delete_donation_history_message))
                    .setPositiveButton(R.string.dialog_confirm_delete_donation_history_delete) { _, _ ->
                        Prefs.donationResults = emptyList()
                        FeedbackUtil.showMessage(
                            activity,
                            R.string.donation_history_deleted_message_snackbar
                        )
                        preference.isVisible = false
                    }
                    .setNegativeButton(R.string.dialog_confirm_delete_donation_history_cancel, null)
                    .show()
                true
            }
        }
    }

    fun updateLanguagePrefSummary() {
        // TODO: resolve RTL vs LTR with multiple languages (e.g. list contains English and Hebrew)
        findPreference(R.string.preference_key_language).summary = WikipediaApp.instance.languageState.appLanguageLocalizedNames
    }

    fun updateRecommendedReadingListSummary() {
        val summary = if (Prefs.isRecommendedReadingListEnabled) {
            R.string.recommended_reading_list_settings_toggle_enable_message
        } else R.string.recommended_reading_list_settings_toggle_disable_message
        findPreference(R.string.preference_key_recommended_reading_list_enabled).summary = activity.getString(summary)
    }

    private inner class SyncReadingListsListener : Preference.OnPreferenceChangeListener {
        override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
            if (AccountUtil.isLoggedIn) {
                if (newValue as Boolean) {
                    (preference as SwitchPreferenceCompat).isChecked = true
                    ReadingListSyncAdapter.setSyncEnabledWithSetup()
                } else {
                    MaterialAlertDialogBuilder(activity)
                            .setTitle(activity.getString(R.string.preference_dialog_of_turning_off_reading_list_sync_title, AccountUtil.userName))
                            .setMessage(activity.getString(R.string.preference_dialog_of_turning_off_reading_list_sync_text, AccountUtil.userName))
                            .setPositiveButton(R.string.reading_lists_confirm_remote_delete_yes, DeleteRemoteListsYesListener(preference))
                            .setNegativeButton(R.string.reading_lists_confirm_remote_delete_no, null)
                            .show()
                }
            } else {
                MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.reading_list_preference_login_to_enable_sync_dialog_title)
                        .setMessage(R.string.reading_list_preference_login_to_enable_sync_dialog_text)
                        .setPositiveButton(R.string.reading_list_preference_login_to_enable_sync_dialog_login
                        ) { _: DialogInterface, _: Int ->
                            val loginIntent = LoginActivity.newIntent(activity,
                                    LoginActivity.SOURCE_SETTINGS)
                            activity.startActivity(loginIntent)
                        }
                        .setNegativeButton(R.string.reading_list_preference_login_to_enable_sync_dialog_cancel, null)
                        .show()
            }
            // clicks are handled and preferences updated accordingly; don't pass the result through
            return false
        }
    }

    fun updateSyncReadingListsPrefSummary() {
        findPreference(R.string.preference_key_sync_reading_lists).let {
            if (AccountUtil.isLoggedIn) {
                it.summary = activity.getString(R.string.preference_summary_sync_reading_lists_from_account, AccountUtil.userName)
            } else {
                it.setSummary(R.string.preference_summary_sync_reading_lists)
            }
        }
    }

    private inner class DeleteRemoteListsYesListener(private val preference: Preference) : DialogInterface.OnClickListener {
        override fun onClick(dialog: DialogInterface, which: Int) {
            (preference as SwitchPreferenceCompat).isChecked = false
            Prefs.isReadingListSyncEnabled = false
            Prefs.isReadingListsRemoteSetupPending = false
            Prefs.isReadingListsRemoteDeletePending = true
            ReadingListSyncAdapter.manualSync()
        }
    }
}

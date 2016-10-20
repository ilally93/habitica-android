package com.habitrpg.android.habitica.ui.fragments.preferences;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceScreen;

import com.habitrpg.android.habitica.APIHelper;
import com.habitrpg.android.habitica.HabiticaApplication;
import com.habitrpg.android.habitica.NotificationPublisher;
import com.habitrpg.android.habitica.R;
import com.habitrpg.android.habitica.callbacks.MergeUserCallback;
import com.habitrpg.android.habitica.helpers.LanguageHelper;
import com.habitrpg.android.habitica.helpers.SoundManager;
import com.habitrpg.android.habitica.helpers.notifications.PushNotificationManager;
import com.habitrpg.android.habitica.prefs.TimePreference;
import com.habitrpg.android.habitica.ui.activities.ClassSelectionActivity;
import com.habitrpg.android.habitica.ui.activities.MainActivity;
import com.magicmicky.habitrpgwrapper.lib.models.HabitRPGUser;
import com.magicmicky.habitrpgwrapper.lib.models.Preferences;
import com.raizlabs.android.dbflow.runtime.transaction.BaseTransaction;
import com.raizlabs.android.dbflow.runtime.transaction.TransactionListener;
import com.raizlabs.android.dbflow.sql.builder.Condition;
import com.raizlabs.android.dbflow.sql.language.Select;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;

public class PreferencesFragment extends BasePreferencesFragment implements
        SharedPreferences.OnSharedPreferenceChangeListener {

    @Inject
    public APIHelper apiHelper;

    @Inject
    public SoundManager soundManager;

    private Context context;
    private TimePreference timePreference;
    private PreferenceScreen pushNotificationsPreference;
    private Preference classSelectionPreference;
    private Preference audioThemePreference;
    private HabitRPGUser user;
    public MainActivity activity;
    private PushNotificationManager pushNotificationManager;

    private TransactionListener<HabitRPGUser> userTransactionListener = new TransactionListener<HabitRPGUser>() {
        @Override
        public void onResultReceived(HabitRPGUser habitRPGUser) {
            PreferencesFragment.this.setUser(habitRPGUser);
        }

        @Override
        public boolean onReady(BaseTransaction<HabitRPGUser> baseTransaction) {
            return true;
        }

        @Override
        public boolean hasResult(BaseTransaction<HabitRPGUser> baseTransaction, HabitRPGUser habitRPGUser) {
            return true;
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ((HabiticaApplication) getActivity().getApplication()).getComponent().inject(this);
        context = getActivity();

        android.support.v7.preference.PreferenceManager preferenceManager = getPreferenceManager();
        SharedPreferences sharedPreferences = preferenceManager.getSharedPreferences();

        String userID = sharedPreferences.getString(context.getString(R.string.SP_userID), null);
        if (userID != null) {
            new Select().from(HabitRPGUser.class).where(Condition.column("id").eq(userID)).async().querySingle(userTransactionListener);
        }

        pushNotificationManager = PushNotificationManager.getInstance(this.getActivity());
    }

    @Override
    protected void setupPreferences() {
        timePreference = (TimePreference) findPreference("reminder_time");
        boolean useReminder = getPreferenceManager().getSharedPreferences().getBoolean("use_reminder", false);
        timePreference.setEnabled(useReminder);


        pushNotificationsPreference = (PreferenceScreen) findPreference("pushNotifications");
        boolean userPushNotifications = getPreferenceManager().getSharedPreferences().getBoolean("usePushNotifications", true);
        pushNotificationsPreference.setEnabled(userPushNotifications);


        classSelectionPreference = findPreference("choose_class");
        classSelectionPreference.setVisible(false);

        audioThemePreference = findPreference("audioTheme");
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);

    }

    @Override
    public void onPause() {
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (preference.getKey().equals("logout")) {
            HabiticaApplication.logout(context);
            getActivity().finish();
        } else if (preference.getKey().equals("choose_class")) {
            Bundle bundle = new Bundle();
            bundle.putString("size", user.getPreferences().getSize());
            bundle.putString("skin", user.getPreferences().getSkin());
            bundle.putString("shirt", user.getPreferences().getShirt());
            bundle.putInt("hairBangs", user.getPreferences().getHair().getBangs());
            bundle.putInt("hairBase", user.getPreferences().getHair().getBase());
            bundle.putString("hairColor", user.getPreferences().getHair().getColor());
            bundle.putInt("hairMustache", user.getPreferences().getHair().getMustache());
            bundle.putInt("hairBeard", user.getPreferences().getHair().getBeard());
            if (!user.getFlags().getClassSelected()) {
                bundle.putBoolean("isInitialSelection", true);
            } else {
                bundle.putBoolean("isInitialSelection", false);
            }

            Intent intent = new Intent(getActivity(), ClassSelectionActivity.class);
            intent.putExtras(bundle);

            if (user.getFlags().getClassSelected() && !user.getPreferences().getDisableClasses()) {
                AlertDialog.Builder builder = new AlertDialog.Builder(getActivity())
                        .setTitle(getString(R.string.change_class_confirmation))
                        .setNegativeButton(getString(R.string.dialog_go_back), (dialog, which) -> {
                            dialog.dismiss();
                        })
                        .setPositiveButton(getString(R.string.change_class), (dialog, which) -> {
                            startActivityForResult(intent, MainActivity.SELECT_CLASS_RESULT);
                        });
                AlertDialog alert = builder.create();
                alert.show();
            } else {
                startActivityForResult(intent, MainActivity.SELECT_CLASS_RESULT);
            }
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }

    private void scheduleNotifications() {

        String timeval = getPreferenceManager().getSharedPreferences().getString("reminder_time", "19:00");

        String[] pieces = timeval.split(":");
        int hour = Integer.parseInt(pieces[0]);
        int minute = Integer.parseInt(pieces[1]);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        long trigger_time = cal.getTimeInMillis();

        Intent notificationIntent = new Intent(context, NotificationPublisher.class);
        notificationIntent.putExtra(NotificationPublisher.NOTIFICATION_ID, 1);
        notificationIntent.putExtra(NotificationPublisher.CHECK_DAILIES, false);

        if (PendingIntent.getBroadcast(context, 0, notificationIntent, PendingIntent.FLAG_NO_CREATE) == null) {
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, notificationIntent, PendingIntent.FLAG_CANCEL_CURRENT);

            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            alarmManager.setInexactRepeating(AlarmManager.RTC_WAKEUP, trigger_time, AlarmManager.INTERVAL_DAY, pendingIntent);
        }
    }

    private void removeNotifications() {
        Intent notificationIntent = new Intent(context, NotificationPublisher.class);
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent displayIntent = PendingIntent.getBroadcast(context, 0, notificationIntent, 0);
        alarmManager.cancel(displayIntent);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        switch(key) {
            case "use_reminder": {
                boolean use_reminder = sharedPreferences.getBoolean(key, false);
                timePreference.setEnabled(use_reminder);
                if (use_reminder) {
                    scheduleNotifications();
                } else {
                    removeNotifications();
                }
            }
            break;
            case "reminder_time": {
                removeNotifications();
                scheduleNotifications();
            }
            break;
            case "usePushNotifications": {
                boolean userPushNotifications = sharedPreferences.getBoolean(key, false);
                pushNotificationsPreference.setEnabled(userPushNotifications);
                if (userPushNotifications) {
                    pushNotificationManager.addPushDeviceUsingStoredToken();
                } else {
                    pushNotificationManager.removePushDeviceUsingStoredToken();
                }
            }
            break;
            case "language": {
                LanguageHelper languageHelper = new LanguageHelper(sharedPreferences.getString(key, "en"));

                Locale.setDefault(languageHelper.getLocale());
                Configuration configuration = new Configuration();
                if (android.os.Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN) {
                    configuration.locale = languageHelper.getLocale();
                } else {
                    configuration.setLocale(languageHelper.getLocale());
                }
                getActivity().getResources().updateConfiguration(configuration,
                        getActivity().getResources().getDisplayMetrics());

                Map<String, Object> updateData = new HashMap<>();
                updateData.put("preferences.language", languageHelper.getLanguageCode());
                apiHelper.apiService.updateUser(updateData)
                        .compose(apiHelper.configureApiCallObserver())
                        .subscribe(new MergeUserCallback(activity, user), throwable -> {
                        });

                Preferences preferences = user.getPreferences();
                preferences.setLanguage(languageHelper.getLanguageCode());
                apiHelper.languageCode = preferences.getLanguage();
                apiHelper.apiService.getContent(apiHelper.languageCode)
                        .compose(apiHelper.configureApiCallObserver())
                        .subscribe(contentResult -> {
                        }, throwable -> {
                        });

                if (android.os.Build.VERSION.SDK_INT <= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
                    Intent intent = new Intent(getActivity(), MainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(intent);
                } else {
                    Intent intent = new Intent(getActivity(), MainActivity.class);
                    this.startActivity(intent);
                    getActivity().finishAffinity();
                }
            }
            case "audioTheme": {
                String newAudioTheme = sharedPreferences.getString(key, "off");

                Map<String, Object> updateData = new HashMap<>();
                updateData.put("preferences.sound", newAudioTheme);
                MergeUserCallback mergeUserCallback = new MergeUserCallback(activity, user);
                apiHelper.apiService.updateUser(updateData)
                        .compose(apiHelper.configureApiCallObserver())
                        .subscribe(mergeUserCallback, throwable -> {
                        });

                Preferences preferences = user.getPreferences();
                preferences.setSound(newAudioTheme);

                soundManager.setSoundTheme(newAudioTheme);

                soundManager.preloadAllFiles();
            }
        }
    }

    @Override
    public void onDisplayPreferenceDialog(Preference preference) {
        if (preference instanceof TimePreference) {
            if (getFragmentManager().findFragmentByTag(TimePreferenceDialogFragment.TAG) == null) {
                TimePreferenceDialogFragment.newInstance(this, preference.getKey())
                        .show(getFragmentManager(), TimePreferenceDialogFragment.TAG);
            }
        } else {
            super.onDisplayPreferenceDialog(preference);
        }
    }

    public void setUser(HabitRPGUser user) {
        this.user = user;
        if (user != null) {
            if(user.getPreferences() != null){
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                prefs.edit().putString("audioTheme", user.getPreferences().getSound()).apply();
            }

            if (user.getFlags() != null && user.getStats() != null) {
                if (user.getStats().getLvl() >= 10) {
                    if (user.getFlags().getClassSelected()) {
                        if (user.getPreferences().getDisableClasses()) {
                            classSelectionPreference.setTitle(getString(R.string.enable_class));
                        } else {
                            classSelectionPreference.setTitle(getString(R.string.change_class));
                            classSelectionPreference.setSummary(getString(R.string.change_class_description));
                        }
                        classSelectionPreference.setVisible(true);
                    } else {
                        classSelectionPreference.setTitle(getString(R.string.enable_class));
                        classSelectionPreference.setVisible(true);
                    }
                }
            }
        }
    }
}

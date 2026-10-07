// What every Android setting does and what its values mean. Made by tools/hsinfo/build.py: do not edit by hand.
// Text of the settings Android documents comes from AOSP (Settings.java, Apache License 2.0); the rest was written for this app.
window.HS_INFO = {
global: `activity_manager_constants|Hidden tuning values of the activity manager (how Android starts and keeps apps).|text: name=value pairs separated by commas; empty = built-in defaults
activity_starts_logging_enabled|Feature flag to enable or disable the activity starts logging feature.|0 = off; 1 = on
adaptive_battery_management_enabled|Adaptive Battery: Android learns which apps you use and limits the ones you don't.|0 = off; 1 = on
adb_allowed_connection_time|How long a "Always allow from this computer" ADB approval stays valid before the phone asks again.|milliseconds; 0 = never expires; default 604800000 (7 days)
adb_disconnect_sessions_on_revoke|Whether existing ADB sessions over both USB and Wifi should be terminated when the user revokes debugging authorizations.|0 = off; 1 = on
adb_enabled|USB debugging: lets a computer, or this app, talk to the phone over ADB.|0 = off; 1 = on|conn
adb_wifi_enabled|Wireless debugging: ADB over Wi-Fi.|0 = off; 1 = on|conn
add_users_when_locked|Lets people add users or a guest from the lock screen.|0 = no; 1 = yes
advanced_battery_usage_amount|The usage amount of advanced battery. The value is 0~100.
airplane_mode_on|Airplane mode: switches the radios off.|0 = off; 1 = on|conn
airplane_mode_radios|The radios airplane mode switches off.|list separated by commas: cell, bluetooth, wifi, nfc, wimax
airplane_mode_toggleable_radios|Radios you may switch back on by hand while airplane mode is on.|list separated by commas: bluetooth, wifi, nfc
allow_user_switching_when_system_user_locked|Allows switching users when system user is locked.|whole number
allow_work_profile_telephony_for_non_dpm_role_holders|Whether work profile telephony feature is enabled for non ROLE_DEVICE_POLICY_MANAGEMENT holders. ("0" = false, "1" = true).|0 = off; 1 = on
always_finish_activities|"Don't keep activities": destroys every screen of an app the moment you leave it (developer option).|0 = off; 1 = on
always_on_display_constants|Always on display(AOD) specific settings This is encoded as a key=value list, separated by commas.|text: name=value pairs separated by commas
angle_debug_package|Package containing ANGLE libraries other than system, which are only available to dumpable apps that opt-in.
angle_egl_features|Lists of ANGLE EGL features for debugging. Each list of features is separated by a comma, each feature in each list is separated by a colon. e.g. feature1:feature2:feature3,feature1:feature3:feature5
angle_gl_driver_all_angle|Force all PKGs to use ANGLE, regardless of any other settings The value is a boolean (1 or 0).|0 = off; 1 = on
angle_gl_driver_selection_pkgs|List of PKGs that have an OpenGL driver selected
angle_gl_driver_selection_values|List of selected OpenGL drivers, corresponding to the PKGs in GLOBAL_SETTINGS_DRIVER_PKGS
animator_duration_scale|Speed of animations inside apps (developer option "Animator duration scale").|decimal; 1 = normal; 0 = no animation; 0.5 = twice as fast; 2 = twice as slow
anomaly_config|A base64-encoded string represents anomaly stats config, used for StatsManager.
anomaly_config_version|An integer to show the version of the anomaly config. Ex: 1, which means current version is 1.
anomaly_detection_constants|Tuning values of the battery anomaly detector.|text: name=value pairs separated by commas
apn_db_content_url|URL for apn_db updates|web address (URL)
apn_db_metadata_url|URL for apn_db update metadata|web address (URL)
app_auto_restriction_enabled|Settings restricts apps that drain the battery in the background by itself.|0 = off; 1 = on
app_integrity_verification_timeout|Timeout for app integrity verification.
app_ops_constants|App ops specific settings. This is encoded as a key=value list, separated by commas.|text: name=value pairs separated by commas
app_standby_enabled|App Standby: limits the background work of apps you have not used for a while.|0 = off; 1 = on
app_time_limit_usage_source|App time limit usage source setting. This controls which app in a task will be considered the source of usage when calculating app usage time limits.
apply_ramping_ringer|Deprecated. Whether applying ramping ringer on incoming phone call ringtone. 1 = apply ramping ringer 0 = do not apply ramping ringer|1 = apply ramping ringer; 0 = do not apply ramping ringer
appop_history_parameters|Appop history parameters. These parameters are represented by a comma-delimited key-value list.
are_user_disabled_hdr_formats_allowed|Whether or not user-disabled HDR formats are allowed. The value is boolean (1 or 0).|0 = off; 1 = on
art_verifier_verify_debuggable|Enable ART bytecode verification verifications for debuggable apps. 0 = disable, 1 = enable.|0 = disable; 1 = enable
assisted_gps_enabled|Assisted GPS: uses the network to find your position faster.|0 = off; 1 = on
audio_safe_csd_current_value|Persisted safe hearding current CSD value. Values are stored as float percentages where 1.f represents 100% sound dose has been reached.|decimal number
audio_safe_csd_dose_records|Persisted safe hearding dose records (see SoundDoseRecord)
audio_safe_csd_next_warning|Persisted safe hearding next CSD warning value. Values are stored as float percentages.|decimal number
audio_safe_volume_state|Persisted safe headphone volume management state by AudioService
auto_revoke_parameters|Auto revoke parameters.
auto_time|Sets the clock from the mobile network.|0 = set by hand; 1 = automatic
auto_time_zone|Sets the time zone from the mobile network.|0 = set by hand; 1 = automatic
auto_time_zone_explicit|Records whether an explicit preference for AUTO_TIME_ZONE has been expressed instead of the current value being the default.
autofill_compat_mode_allowed_packages|Deprecated. The packages allowlisted to be run in autofill compatibility mode.
autofill_logging_level|Level of autofill logging. Valid values are NO_LOGGING, FLAG_ADD_CLIENT_DEBUG, or FLAG_ADD_CLIENT_VERBOSE.
autofill_max_partitions_size|Maximum number of partitions that can be allowed in an autofill session.
autofill_max_visible_datasets|Maximum number of visible datasets in the Autofill dataset picker UI, or 0 to use the default value from resources.
automatic_power_save_mode|How Battery Saver turns on by itself.|0 = at a battery level (low_power_trigger_level); 1 = when the phone predicts the battery will not last until your next charge
average_time_to_discharge|Deprecated. A long value indicating how long the system battery takes to deplete from 100% to 0% on average based on historical drain rates.
aware_allowed|Indicates whether aware is available in the current location.|0 = off; 1 = on
backup_agent_timeout_parameters|Backup and restore agent timeout parameters. These parameters are represented by a comma-delimited key-value list.
battery_charging_state_enforce_level|Threshold battery level to enforce battery state as charging. That means when battery level is equal to or higher than this threshold, it is always considered charging, even if battery level lowered.
battery_charging_state_update_delay|Delay for sending ACTION_CHARGING after device is plugged in. This is used as an override for constants defined in BatteryStatsImpl.
battery_estimates_last_update_time|Deprecated. A long indicating the epoch time in milliseconds when TIME_REMAINING_ESTIMATE_MILLIS, TIME_REMAINING_ESTIMATE_BASED_ON_USAGE, and AVERAGE_TIME_TO_DISCHARGE were last updated.|milliseconds
battery_saver_constants|Hidden tuning values of Battery Saver, for example which features it switches off.|text: name=value pairs separated by commas; empty = defaults
battery_saver_device_specific_constants|Battery Saver tuning values chosen by the phone maker.|text: name=value pairs separated by commas
battery_stats_constants|BatteryStats specific settings. This is encoded as a key=value list, separated by commas.|0 = off; 1 = on
battery_tip_constants|Battery tip specific settings This is encoded as a key=value list, separated by commas.|0 = off; 1 = on
bcast_bg_constants|Broadcast dispatch tuning parameters specific to background broadcasts. This is encoded as a key=value list, separated by commas. Ex: "foo=1,bar=true".|text: name=value pairs separated by commas
bcast_fg_constants|Broadcast dispatch tuning parameters specific to foreground broadcasts. This is encoded as a key=value list, separated by commas.|text: name=value pairs separated by commas
bcast_offload_constants|Broadcast dispatch tuning parameters specific to specific "offline" broadcasts. This is encoded as a key=value list, separated by commas. Ex: "foo=1,bar=true".|text: name=value pairs separated by commas
binder_calls_stats|Binder call stats settings. The following strings are supported as keys: enabled (boolean) detailed_tracking (boolean) upload_data (boolean) sampling_interval (int)|0 = off; 1 = on
ble_scan_always_enabled|Lets apps scan for Bluetooth devices even while Bluetooth is off (helps location).|0 = off; 1 = on
ble_scan_background_mode|The mode that BLE scanning clients will be moved to when in the background.
ble_scan_balanced_interval_ms|The length in milliseconds of a BLE scan interval in a balanced scan mode.|milliseconds
ble_scan_balanced_window_ms|The length in milliseconds of a BLE scan window in a balanced scan mode.|milliseconds
ble_scan_low_latency_interval_ms|The length in milliseconds of a BLE scan interval in a low-latency scan mode.|milliseconds
ble_scan_low_latency_window_ms|The length in milliseconds of a BLE scan window in a low-latency scan mode.|milliseconds
ble_scan_low_power_interval_ms|The length in milliseconds of a BLE scan interval in a low-power scan mode.|milliseconds
ble_scan_low_power_window_ms|The length in milliseconds of a BLE scan window in a low-power scan mode.|milliseconds
blocked_slices|A colon separated list of keys for Settings Slices.|a list, items separated by , or :
blocking_helper_dismiss_to_view_ratio|Settings key for the ratio of notification dismissals to notification views - one of the criteria for showing the notification blocking helper.|decimal number
blocking_helper_streak_limit|Settings key for the longest streak of dismissals - one of the criteria for showing the notification blocking helper. The value is an integer greater than 0.
bluetooth_class_of_device|An integer representing the Bluetooth Class of Device (CoD).
bluetooth_disabled_profiles|A Long representing a bitmap of profiles that should be disabled when bluetooth starts.
bluetooth_interoperability_list|A semi-colon separated list of Bluetooth interoperability workarounds.|a list, items separated by , or :
bluetooth_on|Bluetooth.|0 = off; 1 = on
boot_count|How many times the phone has started. Android keeps this by itself.|whole number, counts up
bugreport_in_power_menu|Deprecated. When the user has enable the option to have a "bug report" command in the power menu.
bypass_device_policy_management_role_qualifications|Whether bypassing the device policy management role holder qualification is allowed, (0 = false, 1 = true).|0 = false; 1 = true
cached_apps_freezer|Freezes apps kept in the background so they use no processor time.|enabled = on; disabled = off; device_default = decided by the phone
call_auto_retry|Calls again by itself when a call is dropped (CDMA networks).|0 = off; 1 = on
captive_portal_detection_enabled|Checks whether a Wi-Fi network needs a sign-in page.|0 = off; 1 = on
captive_portal_fallback_probe_specs|A list of captive portal detection specifications used in addition to the fallback URLs. Each spec has the format url@@/@@statusCodeRegex@@/@@contentRegex. Specs are separated by "@@,@@".
captive_portal_fallback_url|The URL used for fallback HTTP captive portal detection when previous HTTP and HTTPS captive portal detection attemps did not return a conclusive answer.|web address (URL)
captive_portal_http_url|Address the phone opens to test for a Wi-Fi sign-in page (HTTP).|web address
captive_portal_https_url|Address the phone opens to test for a Wi-Fi sign-in page (HTTPS).|web address
captive_portal_mode|What to do when a Wi-Fi network shows a sign-in page.|0 = ignore it; 1 = prompt for sign-in; 2 = avoid the network
captive_portal_other_fallback_urls|A comma separated list of URLs used for captive portal detection in addition to the fallback HTTP url associated with the CAPTIVE_PORTAL_FALLBACK_URL settings.|a list, items separated by , or :
captive_portal_server|The server used for captive portal detection upon a new conection. A 204 response code from the server is used for validation. TODO: remove this deprecated symbol.
captive_portal_use_https|Uses HTTPS to check that a Wi-Fi network really has internet.|0 = off; 1 = on
captive_portal_user_agent|Which User-Agent string to use in the header of the captive portal detection probes. The User-Agent field is unset when this setting has no value (HttpUrlConnection default).
car_dock_sound|Sound played when the phone is put into a car dock.|address of a sound file (URI)
car_undock_sound|Sound played when the phone is taken out of a car dock.|address of a sound file (URI)
carrier_app_names|Map of package name to application names. The application names cannot and will not be localized. App names may not contain colons or semicolons.
carrier_app_whitelist|List of certificate (hex string representation of the application's certificate - SHA-1 or SHA-256) and carrier app package pairs which are allowlisted to prompt the user for install when a sim card...
cdma_cell_broadcast_sms|CDMA Cell Broadcast SMS 0 = CDMA Cell Broadcast SMS disabled 1 = CDMA Cell Broadcast SMS enabled|0 = CDMA Cell Broadcast SMS disabled; 1 = CDMA Cell Broadcast SMS enabled
cell_on|Whether cell is enabled/disabled|0 = off; 1 = on
cert_pin_content_url|URL for cert pinlist updates|web address (URL)
cert_pin_metadata_url|URL for cert pinlist updates|web address (URL)
chained_battery_attribution_enabled|Flag to toggle whether system services report attribution chains when they attribute battery use via a WorkSource.|whole number
charging_sounds_enabled|Plays a sound when charging starts.|0 = off; 1 = on
charging_started_sound|The sound played when wired charging starts.|address of a sound file (URI)
charging_vibration_enabled|Vibrates when charging starts.|0 = off; 1 = on
clockwork_home_ready|Setting to determine if the Clockwork Home application is ready. Set to 1 when the Clockwork Home application has finished starting up.
compatibility_mode|Runs older apps in a compatibility mode.|0 = off for all apps; 1 = on for older apps
connected_apps_allowed_packages|An allow list of packages for which the user has granted the permission to communicate across profiles.
connected_apps_disallowed_packages|A block list of packages for which the user has denied the permission to communicate across profiles.
connectivity_change_delay|The number of milliseconds to delay before sending out CONNECTIVITY_ACTION broadcasts. Ignored.
connectivity_metrics_buffer_size|Size of the event buffer for IP connectivity metrics.
connectivity_sampling_interval_in_seconds|Network sampling interval, in seconds. We'll generate link information about bytes/packets sent and error rates based on data sampled in this interval|seconds
contact_metadata_sync|Deprecated. Whether to enable contacts metadata syncing or not The value 1 - enable, 0 - disable|1 = enable; 0 = disable
contact_metadata_sync_enabled|Whether to enable contacts metadata syncing or not The value 1 - enable, 0 - disable|1 = enable; 0 = disable
contacts_database_wal_enabled|Flag to toggle journal mode WAL on or off for the contacts database. WAL is enabled by default. Set to 0 to disable.
conversation_actions_content_url|URL for conversation actions model updates|web address (URL)
conversation_actions_metadata_url|URL for conversation actions model update metadata|web address (URL)
custom_bugreport_handler_app|Deprecated. The package name for the custom bugreport handler app. This app must be allowlisted. This is currently used only by Power Menu short press.|package name
custom_bugreport_handler_user|Deprecated. The user id for the custom bugreport handler app. This is currently used only by Power Menu short press.
data_activity_timeout_mobile|Inactivity timeout to track mobile data activity. If set to a positive integer, it indicates the inactivity timeout value in seconds to infer the data activity of mobile network.|seconds
data_activity_timeout_wifi|Timeout to tracking Wifi data activity. Same as DATA_ACTIVITY_TIMEOUT_MOBILE but for Wifi network.
data_roaming|Mobile data while roaming (abroad).|0 = off; 1 = on
data_roaming1|Mobile data while roaming, for the SIM with subscription number 1.|0 = off; 1 = on
data_roaming2|Mobile data while roaming, for the SIM with subscription number 2.|0 = off; 1 = on
data_stall_alarm_aggressive_delay_in_ms|The number of milliseconds to delay when checking for data stalls during aggressive detection. (screen on or suspected data stall)
data_stall_alarm_non_aggressive_delay_in_ms|The number of milliseconds to delay when checking for data stalls during non-aggressive detection. (screen is turned off.)
data_stall_recovery_on_bad_network|Tries to repair mobile data when the network is reported as bad.|0 = off; 1 = on
database_creation_buildid|The build id of when the settings database was first created (or re-created due it being missing).|text
database_downgrade_reason|The reason for the settings database being downgraded. This is only for troubleshooting purposes and its value should not be interpreted in any way.|text
debug.force_rtl|Developer setting to force RTL layout.
debug_app|The app that waits for a debugger when it starts (developer option "Select debug app").|package name; empty = none
debug_view_attributes|Lets views save their attributes so tools such as Layout Inspector can read them (developer option).|0 = off; 1 = on
debug_view_attributes_application_package|Which application package is allowed to save View attribute data.
default_dns_server|Setting for default DNS in case nobody suggests one
default_install_location|Where new apps are installed.|0 = let Android decide; 1 = internal storage; 2 = SD card
default_restrict_background_data|Whether background data is restricted by default (Data Saver).|0 = not restricted; 1 = restricted
default_sm_dp_plus|The default SM-DP+ configured for this device. An SM-DP+ is used by an LPA (see EuiccService) to download profiles.
desk_dock_sound|Sound played when the phone is put into a desk dock.|address of a sound file (URI)
desk_undock_sound|Sound played when the phone is taken out of a desk dock.|address of a sound file (URI)
development_settings_enabled|Shows Developer options in Settings.|0 = hidden; 1 = shown
device_config_sync_disabled|Whether or not syncs (bulk set operations) for DeviceConfig are currently persistently disabled.|0 = off; 1 = on
device_demo_mode|The phone is in retail demo mode.|0 = no; 1 = yes
device_idle_constants|Hidden tuning values of Doze (the deep sleep the phone enters when idle).|text: name=value pairs separated by commas; empty = defaults
device_name|The name of this phone, shown to Bluetooth, the hotspot and the network.|text
device_policy_constants|DevicePolicyManager specific settings. This is encoded as a key=value list, separated by commas.|text: name=value pairs separated by commas
device_provisioned|The first-time setup of the phone is finished: leave this at 1.|1 = finished; 0 = setup not finished|break
device_provisioning_mobile_data|Indicates whether mobile data should be allowed while the device is being provisioned.|0 = off; 1 = on
disable_screen_share_protections_for_apps_and_notifications|Whether to disable app and notification screen share protections. The value 1 - enable, 0 - disable|1 = enable; 0 = disable
disable_window_blurs|Turns off the blur effect behind windows (saves power, helps slow phones).|0 = blur allowed; 1 = blur disabled
display_panel_lpm|Flag to enable or disable display panel low power mode (lpm) false -> Display panel power saving mode is disabled. true -> Display panel power saving mode is enabled.
display_scaling_force|The saved value for WindowManagerService.setForcedDisplayScalingMode(). 0 or unset if scaling is automatic, 1 if scaling is disabled.
display_size_forced|The saved value for WindowManagerService.setForcedDisplaySize(). Two integers separated by a comma. If unset, then use the real display size.
dns_resolver_max_samples|Maximum number taken into account for statistics purposes in the system DNS resolver.
dns_resolver_min_samples|Minimum number of samples needed for statistics to be considered meaningful in the system DNS resolver.
dns_resolver_sample_validity_seconds|Sample validity in seconds to configure for the system DNS resolver.|seconds
dns_resolver_success_threshold_percent|Success threshold in percent for use with the system DNS resolver.
dock_audio_media_enabled|Plays media on the dock's speakers while docked.|0 = off; 1 = on
dock_sounds_enabled|Plays sounds when the phone is docked or undocked.|0 = off; 1 = on
dock_sounds_enabled_when_accessbility|Whether to play a sound for dock events, only when an accessibility service is on.|0 = off; 1 = on
download_manager_max_bytes_over_mobile|The maximum size, in bytes, of a download that the download manager will transfer over a non-wifi connection.
download_manager_recommended_max_bytes_over_mobile|The recommended maximum size, in bytes, of a download that the download manager should transfer over a non-wifi connection.
dropbox:|Prefix for per-tag dropbox disable/enable settings.
dropbox_age_seconds|Maximum age of entries kept by DropBoxManager.
dropbox_max_files|Maximum number of entry files which DropBoxManager will keep around.
dropbox_quota_kb|Maximum amount of disk space used by DropBoxManager no matter what.
dropbox_quota_percent|Percent of free disk (excluding reserve) which DropBoxManager will use.
dropbox_reserve_percent|Percent of total disk which DropBoxManager will never dip into.
dsrm_duration_millis|The duration in milliseconds of each action, separated by commas. Ex: "18000,18000,18000,18000,0" See com.android.internal.telephony.data.DataStallRecoveryManager for more info|a list, items separated by , or :
dsrm_enabled_actions|The list of DSRM enabled actions, separated by commas. Ex: "true,true,false,true,true" See com.android.internal.telephony.data.DataStallRecoveryManager for more info|a list, items separated by , or :
dynamic_power_savings_disable_threshold|The setting that backs the disable threshold for the setPowerSavingsWarning api in PowerManager
dynamic_power_savings_enabled|The setting which backs the setDynamicPowerSaveHint api in PowerManager.
emergency_affordance_needed|Whether the phone should show an emergency call shortcut. Android sets this by itself.|0 = no; 1 = yes
emergency_gesture_power_button_cooldown_period_ms|The power button "cooldown" period in milliseconds after the Emergency gesture is triggered, during which single-key actions on the power button are suppressed.|milliseconds
emergency_gesture_sticky_ui_max_duration_millis|The maximum duration in milliseconds for which the emergency gesture UI can stay "sticky", where the notification pull-down shade and navigation gestures/buttons are temporarily disabled.|milliseconds
emergency_gesture_tap_detection_min_time_ms|The minimum time in milliseconds to perform the emergency gesture.|milliseconds
emergency_tone|Tone played when an emergency call ends (CDMA).|0 = off; 1 = alert; 2 = vibrate
emulate_display_cutout|DisplayCutout DisplayCutout emulation mode.
enable_16k_pages|Whether to boot with 16K page size compatible kernel 1 = Boot with 16K kernel 0 = Boot with 4K kernel (default)|0 = off; 1 = on
enable_accessibility_global_gesture_enabled|Setting whether the global gesture for enabling accessibility is enabled.
enable_adb_incremental_install_default|Installs apps over ADB in the faster incremental way by default.|0 = off; 1 = on
enable_automatic_system_server_heap_dumps|Whether to enable automatic system server heap dumps. This only works on userdebug or eng builds, not on user builds. This is set by the user and overrides the config value.|0 = off; 1 = on
enable_back_animation|Shows the preview animation while you do a back gesture (developer option "Predictive back animations").|0 = off; 1 = on
enable_cache_quota_calculation|Whether the cache quota calculation task is enabled/disabled.|0 = off; 1 = on
enable_cellular_on_boot|Whether to enable cellular on boot. The value 1 - enable, 0 - disable|1 = enable; 0 = disable
enable_deletion_helper_no_threshold_toggle|Whether the Deletion Helper no threshold toggle is available.|0 = off; 1 = on
enable_diskstats_logging|Whether the diskstats logging task is enabled/disabled.|0 = off; 1 = on
enable_ephemeral_feature|Toggle to enable/disable the entire ephemeral feature. By default, ephemeral is enabled. Set to zero to disable.|0 = off; 1 = on
enable_freeform_support|Lets apps run in freeform windows that can be moved and resized (developer option).|0 = off; 1 = on
enable_gnss_raw_meas_full_tracking|Enable GNSS Raw Measurements Full Tracking? 0 = no 1 = yes|0 = no; 1 = yes
enable_gpu_debug_layers|Lets an app load GPU debug layers (developer option).|0 = no; 1 = yes
enable_multi_slot_timeout_millis|Flag to set the waiting time for enabling multi SIM slot.|whole number
enable_non_resizable_multi_window|Lets apps that cannot be resized run in split screen (developer option).|0 = off; 1 = on
enabled_subscription_for_slot|Which subscription is enabled for a physical slot.
encoded_surround_output|Surround sound over HDMI.|0 = automatic; 1 = never; 2 = always; 3 = manual (choose the formats)
encoded_surround_output_enabled_formats|Surround sounds formats that are enabled when ENCODED_SURROUND_OUTPUT is set to ENCODED_SURROUND_OUTPUT_MANUAL. Encoded as comma separated list.|a list, items separated by , or :
ephemeral_cookie_max_size_bytes|Ephemeral app cookie max size in bytes.|whole number
euicc_factory_reset_timeout_millis|Flag to set the waiting time for euicc factory reset inside System > Settings|whole number
euicc_provisioned|A profile was once downloaded to the eSIM. Android keeps this by itself.|0 = no; 1 = yes
euicc_removing_invisible_profiles_timeout_millis|Flag to set the waiting time for removing invisible euicc profiles inside System > Settings.|whole number
euicc_supported_countries|List of ISO country codes in which eUICC UI is shown. Country codes should be separated by comma. Note: if EUICC_SUPPORTED_COUNTRIES is empty, then EUICC_UNSUPPORTED_COUNTRIES is used.
euicc_switch_slot_timeout_millis|Flag to set the waiting time for euicc slot switch.|whole number
euicc_unsupported_countries|List of ISO country codes in which eUICC UI is not shown. Country codes should be separated by comma. Note: if EUICC_SUPPORTED_COUNTRIES is empty, then EUICC_UNSUPPORTED_COUNTRIES is used.
extra_low_power|Extreme battery saver is on.|0 = off; 1 = on
fancy_ime_animations|Animation when the keyboard opens and closes.|decimal; 1 = normal; 0 = no animation
force_allow_on_external|Lets every app be moved to an SD card (developer option).|0 = no; 1 = yes
force_desktop_mode_on_external_displays|Whether to enable the legacy freeform support on secondary displays. If enabled, the SECONDARY_HOME of the launcher is started on any secondary display, allowing for a desktop experience.|0 = off; 1 = on
force_enable_pss_profiling|Describes whether AM's AppProfiler should collect PSS even if RSS is the default. This can be set by a user in developer settings.
force_non_debuggable_final_build_for_compat|Flag for forcing OverrideValidatorImpl to consider this a non-debuggable build.
force_resizable_activities|Makes every app resizable for split screen and freeform (developer option).|0 = no; 1 = yes
forced_app_standby_for_small_battery_enabled|Whether or not to enable Forced App Standby on small battery devices.|0 = off; 1 = on
foreground_service_starts_logging_enabled|Feature flag to enable or disable the foreground service starts logging feature.|0 = off; 1 = on
fps_divisor|An integer to reduce the FPS by this factor. Only for experiments. Need to reboot the device for this setting to take full effect.
fstrim_mandatory_interval|How long after the last trim the phone must trim storage at start (flash maintenance).|milliseconds
global_http_proxy_exclusion_list|Addresses that skip the global proxy.|list of host names separated by commas
global_http_proxy_host|Host name of the proxy all web traffic goes through.|host name; empty = no proxy
global_http_proxy_port|Port of the global proxy.|port number, 1 to 65535
gnss_hal_location_request_duration_millis|Duration of updates in millisecond for GNSS location request from HAL to framework. If zero, the GNSS location request feature is disabled. The value is a non-negative long.
gnss_satellite_blocklist|Blocklist of GNSS satellites. This is a list of integers separated by commas to represent pairs of (constellation, svid). Thus, the number of integers should be even.|a list, items separated by , or :
gprs_register_check_period_ms|The interval in milliseconds at which to check gprs registration after the first registration mismatch of gprs and voice service, to detect possible data network registration problems.|milliseconds
gpu_debug_app|App allowed to load GPU debug layers
gpu_debug_layer_app|Addition app for GPU layer discovery
gpu_debug_layers|Ordered GPU debug layer list for Vulkan i.e. ::...:
gpu_debug_layers_gles|Ordered GPU debug layer list for GLES i.e. ::...:
hdr_conversion_mode|How the phone handles HDR video the screen cannot show.|1 = pass it through; 2 = let the system convert it; 3 = force one HDR type
hdr_force_conversion_type|The output HDR type chosen by the user in case when HDR_CONVERSION_MODE is HDR_CONVERSION_FORCE.
heads_up_notifications_enabled|Pop-up (heads-up) notifications at the top of the screen.|0 = off; 1 = on
hearing_aid|Hearing aid compatibility.|0 = off; 1 = on
hearing_device_local_ambient_volume|A semi-colon separated list of Bluetooth hearing devices' local ambient volume data. Each entry is encoded as a key=value list, separated by commas.|0 = off; 1 = on
hearing_device_local_notification|A semi-colon separated list of Bluetooth hearing devices' notification data. Each entry is encoded as a key=value list, separated by commas.|0 = off; 1 = on
hidden_api_blacklist_exemptions|Hidden Android functions that apps may still use.|list separated by commas, or *
hidden_api_policy|How strictly Android stops apps from using hidden (non-SDK) functions.|0 = allow all; 1 = only warn; 2 = block; -1 = system default
hide_error_dialogs|Hides crash and "app is not responding" dialogs.|0 = show them; 1 = hide them
http_proxy|Proxy for web traffic, written host:port.|host:port; empty or :0 = none
install_carrier_app_notification_persistent|Whether the notification should be ongoing (persistent) when a carrier app install is required. The value is a boolean (1 or 0).|0 = off; 1 = on
install_carrier_app_notification_sleep_millis|The amount of time (ms) to hide the install carrier app notification after the user has ignored it. After this time passes, the notification will be shown again The value is a long
install_non_market_apps|Allows installing apps from outside the store (older global switch).|0 = no; 1 = yes
installed_instant_app_max_cache_period|The max period for caching installed instant apps in milliseconds.|milliseconds
installed_instant_app_min_cache_period|The min period for caching installed instant apps in milliseconds.|milliseconds
instant_app_dexopt_enabled|Toggle to enable/disable dexopt for instant applications. The default is for dexopt to be disabled.|whole number
intent_firewall_content_url|URL for intent firewall updates|web address (URL)
intent_firewall_metadata_url|URL for intent firewall update metadata|web address (URL)
keep_profile_in_background|Flag to keep background restricted profiles running after exiting. If disabled, the restricted profile can be put into stopped state as soon as the user leaves it.|0 = off; 1 = on
kernel_cpu_thread_reader|Settings for collecting statistics on CPU usage per thread The following strings are supported as keys: num_buckets (int) collected_uids (string) minimum_total_cpu_usage_millis (int)
kernel_logs_for_|Lines of kernel logs to include with system crash/ANR/etc. reports, as a prefix of the dropbox tag of the report type.
key_chord_power_volume_up|Overrides internal R.integer.config_keyChordPowerVolumeUp. Allowable values detailed in frameworks/base/core/res/res/values/config.xml. Used by PhoneWindowManager.
lang_id_content_url|URL for lang id model updates|web address (URL)
lang_id_metadata_url|URL for lang id model update metadata|web address (URL)
lid_behavior|Specifies the behaviour the lid triggers when closed See WindowManagerPolicy.WindowManagerFuncs
location_background_throttle_interval_ms|The interval in milliseconds at which location requests will be throttled when they are coming from the background.|milliseconds
location_background_throttle_package_whitelist|Packages that are allowlisted for background throttling (throttling will not be applied).
location_background_throttle_proximity_alert_interval_ms|Most frequent location update interval in milliseconds that proximity alert is allowed to request.|milliseconds
location_enable_stationary_throttle|Whether to throttle location when the device is in doze and still.|0 = off; 1 = on
location_ignore_settings_package_whitelist|Deprecated. Packages that are allowlisted for ignoring location settings (may retrieve location even when user location settings are off), for emergency purposes.
location_settings_link_to_permissions_enabled|Flag to enable the link to location permissions in location setting. Set to 0 to disable.
lock_sound|Sound played when the phone locks.|address of a sound file (URI)
logcat_for_|Lines of logcat to include with system crash/ANR/etc. reports, as a prefix of the dropbox tag of the report type.
looper_stats|Looper stats settings. The following strings are supported as keys: enabled (boolean) sampling_interval (int)|0 = off; 1 = on
low_battery_sound|Sound played at low battery.|address of a sound file (URI)
low_battery_sound_timeout|Milliseconds after screen-off after which low battery sounds will be silenced. If zero, battery sounds will always play. Defaults to @integer/def_low_battery_sound_timeout in SettingsProvider.
low_power|Battery Saver is on right now.|0 = off; 1 = on
low_power_mode_reminder_enabled|Whether low power mode reminder is enabled. If this value is 0, the device will not receive low power notification.|0 = off; 1 = on
low_power_mode_suggestion_params|See com.android.settingslib.fuelgauge.BatterySaverUtils.
low_power_standby_active_during_maintenance|Setting indicating whether Low Power Standby is allowed to be active during doze maintenance mode.
low_power_standby_enabled|Low Power Standby: restricts apps while the phone sits idle and unplugged.|0 = off; 1 = on
low_power_sticky|Keeps Battery Saver on after unplugging until you turn it off.|0 = off; 1 = on
low_power_sticky_auto_disable_enabled|Turns "sticky" Battery Saver off by itself once the battery has recharged.|0 = off; 1 = on
low_power_sticky_auto_disable_level|Battery level at which sticky Battery Saver turns off by itself.|percent, 0 to 100
low_power_trigger_level|Battery level at which Battery Saver turns on by itself.|percent, 1 to 100; 0 = never
low_power_trigger_level_max|Highest battery level you can pick for the Battery Saver trigger.|percent, 0 to 100
lte_service_forced|Whether LTE can be switched on or off as a preferred network.|0 = no; 1 = yes
managed_provisioning_defer_provisioning_to_role_holder|Whether to enable managed device provisioning via the role holder.|0 = off; 1 = on
max_error_bytes_for_|Maximum number of bytes of a system crash/ANR/etc. report that ActivityManagerService should send to DropBox, as a prefix of the dropbox tag of the report type.|whole number
max_notification_enqueue_rate|The maximum allowed notification enqueue rate in Hertz. Should be a float, and includes updates only.|decimal number
max_sound_trigger_detection_service_ops_per_day|Maximum number of SoundTriggerDetectionService operations per day.
maximum_obscuring_opacity_for_touch|The maximum allowed obscuring opacity by UID to propagate touches. For certain window types (eg.
mdc_initial_max_retry|The value passed to a Mobile DataConnection via bringUp which defines the number of retries to perform when setting up the initial connection.
mhl_input_switching_enabled|Whether TV will switch to MHL port when a mobile device is plugged in. (0 = false, 1 = true)|0 = false; 1 = true
mhl_power_charge_enabled|Whether TV will charge the mobile device connected at MHL port. (0 = false, 1 = true)|0 = false; 1 = true
min_duration_between_recovery_steps|Minumim duration in millisecodns between cellular data recovery attempts
mobile_data|Mobile data.|0 = off; 1 = on
mobile_data1|Mobile data, for the SIM with subscription number 1.|0 = off; 1 = on
mobile_data_always_on|Keeps mobile data connected while Wi-Fi is in use, for a quicker switch.|0 = off; 1 = on
mode|Parameter for APPOP_HISTORY_PARAMETERS that controls the mode in which the historical registry operates.
mode_ringer|Ringer mode as Android holds it.|0 = silent; 1 = vibrate; 2 = normal
modem_stack_enabled_for_slot|Whether corresponding logical modem is enabled for a physical slot. The value 1 - enable, 0 - disable|1 = enable; 0 = disable
multi_sim_data_call|The SIM used for mobile data (subscription id).|subscription id; -1 = none chosen
multi_sim_sms|The SIM used for text messages (subscription id).|subscription id; -1 = ask every time
multi_sim_sms_prompt|Asks which SIM to use for each text.|0 = off; 1 = on
multi_sim_voice_call|The SIM used for calls (subscription id).|subscription id; -1 = ask every time
multi_sim_voice_prompt|Asks which SIM to use for each call.|0 = off; 1 = on
mute_alarm_stream_with_ringer_mode|Alarms are silenced together with the ringer.|0 = no; 1 = yes
mute_alarm_stream_with_ringer_mode_user_preference|The user's choice for whether or not Alarm stream should always be muted with Ringer.
native_flags_health_check_enabled|Whether we've enabled native flags health check on this device. Takes effect on reboot. The value "1" enables native flags health check; otherwise it's disabled.|0 = off; 1 = on
network_avoid_bad_wifi|Switches away from Wi-Fi that has no internet.|0 = never avoid; 1 = ask; null or other = avoid
network_default_daily_multipath_quota_bytes|Default daily multipath budget used by ConnectivityManager.getMultipathPreference() on metered networks.
network_metered_multipath_preference|Uses Wi-Fi and mobile data together on metered networks.|0 = never; 1 = for a smooth handover; 2 = for reliability; 4 = for speed
network_preference|User preference for which network(s) should be used. Only the connectivity service should touch this.
network_recommendations_enabled|Deprecated. Value to specify if network recommendations from NetworkScoreService are enabled.|-1 = Forced off; 0 = Disabled
network_recommendations_package|Deprecated. Which package name to use for network recommendations. If null, network recommendations will neither be requested nor accepted.|text
network_scorer_app|Which package name to use for network scoring. If null, or if the package is not a valid scorer app, external network scores will neither be requested nor accepted.
network_scoring_provisioned|The network scoring service has started once. Android keeps this by itself.|0 = no; 1 = yes
network_scoring_ui_enabled|Deprecated. Value to specify whether network quality scores and badging should be shown in the UI.|0 = off; 1 = on
network_switch_notification_daily_limit|The maximum number of notifications shown in 24 hours when switching networks.
network_switch_notification_rate_limit_millis|The minimum time in milliseconds between notifications when switching networks.|milliseconds
network_watchlist_enabled|Checks traffic against a watchlist of harmful hosts.|0 = off; 1 = on
network_watchlist_last_report_time|Network watchlist last report time.
new_contact_aggregator|Whether to enable new contacts aggregator or not. The value 1 - enable, 0 - disable|1 = enable; 0 = disable
night_display_forced_auto_mode_available|Whether night display forced auto mode is available. 0 = unavailable, 1 = available.|0 = unavailable; 1 = available
nitz_network_disconnect_retention|If the device connects to a telephony network and was disconnected from a telephony network for less than this time, a previously received NITZ signal can be restored. This value is in milliseconds.|milliseconds
nitz_update_diff|Smallest clock difference between two network time signals that is accepted.|milliseconds
nitz_update_spacing|If the elapsed realtime between two NITZ signals is greater than this value then the second signal cannot be ignored. This value is in milliseconds.|milliseconds
notification_bubbles|Chat bubbles for notifications. Now kept in the Secure table.|0 = off; 1 = on
notification_feedback_enabled|When enabled, notifications the notification assistant service has modified will show an indicator. When tapped, this indicator will describe the adjustment made and solicit feedback.|1 = enable; 0 = disable
notification_snooze_options|The list of snooze options for notifications This is encoded as a key=value list, separated by commas.|text: name=value pairs separated by commas
nr_nsa_tracking_screen_off_mode|For 5G NSA capable devices, determines whether NR tracking indications are on when the screen is off. Values are: 0: off - All 5G NSA tracking indications are off when the screen is off.
nsd_on|Network service discovery: finds printers and other devices on the network.|0 = off; 1 = on
ntp_server|Network time server used to set the clock.|host name
ntp_timeout|How long to wait for the network time server.|milliseconds
one_handed_keyguard_side|In one handed mode, which side the keyguard should be on. Allowable values are one of the ONE_HANDED_KEYGUARD_SIDE_* constants.
ota_disable_automatic_update|Whether to disable the automatic scheduling of system updates. 1 = system updates won't be automatically scheduled (will always present notification instead).|0 = off; 1 = on
overlay_display_devices|Simulated extra screens, to test apps with several displays (developer option).|text, e.g. 1280x720/320; empty = none
override_desktop_mode_features|Whether to override the availability of the desktop mode on the main display of the device. If on, users can make move an app to the desktop, allowing a freeform windowing experience.|0 = off; 1 = on
override_settings_provider_restore_any_version|If set to 1, SettingsProvider's restoreAnyVersion="true" attribute will be ignored and restoring to lower version of platform API will be skipped.
pac_change_delay|The series of successively longer delays used in retrying to download PAC file. Last delay is used between successful PAC downloads.
package_verifier_enable|Checks apps for harm when they are installed.|0 = off; 1 = on
package_verifier_user_consent|Whether you agreed to the app verifier.|1 = agreed; -1 = declined; 0 = not asked yet
pdp_watchdog_error_poll_count|The number of polls to perform (at PDP_WATCHDOG_ERROR_POLL_INTERVAL_MS) after hitting PDP_WATCHDOG_TRIGGER_PACKET_COUNT before attempting data connection recovery.
pdp_watchdog_error_poll_interval_ms|The interval in milliseconds at which to check packet counts on the mobile data interface after PDP_WATCHDOG_TRIGGER_PACKET_COUNT outgoing packets has been reached without incoming packets.|milliseconds
pdp_watchdog_long_poll_interval_ms|The interval in milliseconds at which to check packet counts on the mobile data interface when screen is off, to detect possible data connection problems.|milliseconds
pdp_watchdog_max_pdp_reset_fail_count|The number of failed PDP reset attempts before moving to something more drastic: re-registering to the network.
pdp_watchdog_poll_interval_ms|The interval in milliseconds at which to check packet counts on the mobile data interface when screen is on, to detect possible data connection problems.|milliseconds
pdp_watchdog_trigger_packet_count|The number of outgoing packets sent without seeing an incoming packet that triggers a countdown (of PDP_WATCHDOG_ERROR_POLL_COUNT device is logged to the event log
people_space_conversation_type|Which types of conversation(s) to show in People Space. Values are: 0: Single user-selected conversation (default) 1: Priority conversations only 2: All conversations|0 = Single user-selected conversation (default; 1 = Priority conversations only; 2 = All conversations
policy_control|Hides the status bar or navigation bar for chosen apps (immersive mode).|text, e.g. immersive.full=*; empty = none
power_button_double_press|What a double press of the power button does (when the phone maker has overridden it).|a number picking the action; 0 = nothing; the other numbers depend on the Android version
power_button_long_press|What a long press of the power button does (when the phone maker has overridden it).|a number picking the action; 0 = nothing; 1 = power menu; the other numbers depend on the Android version
power_button_long_press_duration_ms|Override internal R.integer.config_longPressOnPowerDurationMs. It determines the length of power button press to be considered a long press in milliseconds. Used by PhoneWindowManager.|milliseconds
power_button_short_press|Overrides internal R.integer.config_shortPressOnPowerBehavior. Allowable values detailed in frameworks/base/core/res/res/values/config.xml. Used by PhoneWindowManager.
power_button_suppression_delay_after_gesture_wake|The amount of time to suppress "power-off" from the power button after the device has woken due to a gesture (lifting the phone).
power_button_triple_press|Overrides internal R.integer.config_triplePressOnPowerBehavior. Allowable values detailed in frameworks/base/core/res/res/values/config.xml. Used by PhoneWindowManager.
power_button_very_long_press|Overrides internal R.integer.config_veryLongPressOnPowerBehavior. Allowable values detailed in frameworks/base/core/res/res/values/config.xml. Used by PhoneWindowManager.
power_manager_constants|Hidden tuning values of the power manager.|text: name=value pairs separated by commas
power_sounds_enabled|Plays sounds for low battery alerts.|0 = off; 1 = on
preferred_network_mode|See RIL_PreferredNetworkType in ril.h
private_dns_default_mode|Forced override of the default mode (hardcoded as "automatic", nee "opportunistic").
private_dns_mode|Private DNS (DNS over TLS), which hides which sites you look up.|off = off; opportunistic = automatic; hostname = use the host named in private_dns_specifier
private_dns_specifier|Host name of the private DNS provider.|host name, e.g. dns.google; used when private_dns_mode is hostname
provisioning_apn_alarm_delay_in_ms|The number of milliseconds to allow the provisioning apn to remain active
qs_media_controls|Whether or not media is shown automatically when bypassing as a heads up.|0 = off; 1 = on
receive_explicit_user_interaction_audio_enabled|Record audio from near-field microphone (ie. TV remote) Allows audio recording regardless of sensor privacy state, as it is an intentional user interaction: hold-to-talk|whole number
recommended_network_evaluator_cache_expiry_ms|Deprecated. The expiration time in milliseconds for the WifiKey request cache in RecommendedNetworkEvaluator.|milliseconds
remove_guest_on_exit|Whether guest user should be removed on exit from guest mode.|whole number
render_shadows_in_compositor|If true, shadows drawn around the window will be rendered by the system compositor.|0 = false; 1 = true
repair_mode_active|Whether repair mode is active on the device. Set to 1 for true and 0 for false.|0 = off; 1 = on
require_password_to_decrypt|On devices that use full-disk encryption, indicates whether the primary user's lockscreen credential is required to decrypt the device on boot.|0 = off; 1 = on
restricted_networking_mode|Only apps on an allow list may use the network.|0 = off; 1 = on
review_permissions_notification_state|State of whether review notification permissions notification needs to be shown the user, and whether the user has interacted.|-1 = UNKNOWN; 0 = SHOULD_SHOW; 1 = USER_INTERACTED; 2 = DISMISSED; 3 = RESHOWN
roaming_settings|The CDMA roaming mode 0 = Home Networks, CDMA default 1 = Roaming on Affiliated networks 2 = Roaming on any networks|0 = Home Networks, CDMA default; 1 = Roaming on Affiliated networks; 2 = Roaming on any networks
safe_boot_disallowed|Stops the phone from starting in safe mode.|0 = allowed; 1 = disallowed
satellite_mode_enabled|Satellite mode: switches off radios that satellite use does not allow.|0 = off; 1 = on
satellite_mode_radios|A comma separated list of radios that need to be disabled when satellite mode is on.|a list, items separated by , or :
secure_frp_mode|The phone is in a restricted factory-reset-protection state.|0 = no; 1 = yes
selinux_content_url|URL for selinux (mandatory access control) updates|web address (URL)
selinux_metadata_url|URL for selinux (mandatory access control) update metadata|web address (URL)
selinux_status|SELinux security mode.|0 = permissive (only logs); 1 = enforcing
send_action_app_error|Lets Android send the "app error" notice when an app crashes.|0 = no; 1 = yes
set_global_http_proxy|Enables the UI setting to allow the user to specify the global HTTP proxy and associated exclusion list.
set_install_location|Lets you pick where apps are installed.|0 = no; 1 = yes
settings_key_reverse_charging_auto_turn_on|Whether to auto enable reverse charging once plugged-in.|0 = off; 1 = on
settings_use_external_provider_api|Whether or not Settings should enable external provider API.|0 = off; 1 = on
settings_use_psd_api|Whether or not Settings should enable psd API.|0 = off; 1 = on
setup_prepaid_data_service_url|URL to open browser on to allow user to manage a prepay account|web address (URL)
setup_prepaid_detection_redir_host|Host to check for a redirect to after an attempt to GET SETUP_PREPAID_DETECTION_TARGET_URL. (If we redirected there, this is a prepaid device with zero balance.)
setup_prepaid_detection_target_url|URL to attempt a GET on to see if this is a prepay device|web address (URL)
shortcut_manager_constants|ShortcutManager specific settings. This is encoded as a key=value list, separated by commas.|text: name=value pairs separated by commas
show_angle_in_use_dialog_box|Show the "ANGLE In Use" dialog box to the user when ANGLE is the OpenGL driver. The value is a boolean (1 or 0).|0 = off; 1 = on
show_first_crash_dialog|Shows a dialog when an app in the foreground crashes.|0 = no; 1 = yes
show_hidden_icon_apps_enabled|Whether or not show hidden launcher icon apps feature is enabled.|0 = off; 1 = on
show_mute_in_crash_dialog|If nonzero, crash dialogs will show an option to mute all future crash dialogs for this app.
show_new_app_installed_notification_enabled|Whether or not show new app installed notification is enabled.|0 = off; 1 = on
show_new_notif_dismiss|Whether to show new notification dismissal. Values are: 0: Disabled 1: Enabled|0 = Disabled; 1 = Enabled
show_notification_channel_warnings|Shows a toast when an app posts a notification without a valid channel (developer option).|0 = off; 1 = on
show_people_space|Whether to show People Space. Values are: 0: Disabled (default) 1: Enabled|0 = Disabled (default; 1 = Enabled
show_processes|Shows a CPU usage meter (older developer option).|0 = off; 1 = on
show_restart_in_crash_dialog|If nonzero, crash dialogs will show an option to restart the app.
show_temperature_warning|Shows a notification when the phone gets too hot.|0 = off; 1 = on
show_usb_temperature_alarm|Shows a notification when the USB port gets too hot.|0 = off; 1 = on
signed_config_version|Current version of signed configuration applied.
smart_replies_in_notifications_flags|Configuration flags for smart replies in notifications. This is encoded as a key=value list, separated by commas.|0 = off; 1 = on
smart_selection_content_url|URL for smart selection model updates|web address (URL)
smart_selection_metadata_url|URL for smart selection model update metadata|web address (URL)
smart_suggestions_in_notifications_flags|Configuration flags for the automatic generation of smart replies and smart actions in notifications. This is encoded as a key=value list, separated by commas.|0 = off; 1 = on
sms_outgoing_check_interval_ms|The interval in milliseconds at which to check the number of SMS sent out without asking for use permit, to limit the un-authorized SMS usage.|milliseconds
sms_outgoing_check_max_count|The number of outgoing SMS sent without asking for user permit (of SMS_OUTGOING_CHECK_INTERVAL_MS
sms_short_code_confirmation|Used to disable SMS short code confirmation - defaults to true. True indcates we will do the check, etc. Set to false to disable.
sms_short_code_rule|Used to select which country we use to determine premium sms codes.
sms_short_codes_content_url|URL for sms short code updates|web address (URL)
sms_short_codes_metadata_url|URL for sms short code update metadata|web address (URL)
soft_ap_timeout_enabled|Deprecated. Whether soft AP will shut down after a timeout period when no devices are connected.|0 = off; 1 = on
sound_trigger_detection_service_op_timeout|Timeout for a single SoundTriggerDetectionService operation (in ms).
speed_label_cache_eviction_age_millis|Deprecated. Value to specify how long in milliseconds to retain seen score cache curves to be used when generating SSID only bases score curves.|milliseconds
sqlite_compatibility_wal_flags|Configuration flags for SQLite Compatibility WAL. Encoded as a key-value list, separated by commas.|0 = off; 1 = on
stay_on_while_plugged_in|Keeps the screen on while charging (developer option "Stay awake").|0 = off; 1 = while on a charger; 2 = while on USB; 4 = while on wireless; 8 = while docked; add them for several, 15 = all
stem_primary_button_double_press|Overrides internal R.integer.config_doublePressOnStemPrimaryBehavior. Allowable values detailed in frameworks/base/core/res/res/values/config.xml. Used by PhoneWindowManager.
stem_primary_button_long_press|Overrides internal R.integer.config_longPressOnStemPrimaryBehavior. Allowable values detailed in frameworks/base/core/res/res/values/config.xml. Used by PhoneWindowManager.
stem_primary_button_short_press|Overrides internal R.integer.config_shortPressOnStemPrimaryBehavior. Allowable values detailed in frameworks/base/core/res/res/values/config.xml. Used by PhoneWindowManager.
stem_primary_button_triple_press|Overrides internal R.integer.config_triplePressOnStemPrimaryBehavior. Allowable values detailed in frameworks/base/core/res/res/values/config.xml. Used by PhoneWindowManager.
storage_settings_clobber_threshold|Flag to set the timeout for when to refresh the storage settings cached data.|whole number
streaming_verifier_timeout|Timeout for package verification during streaming installations.
stylus_ever_used|Indicates whether a stylus has ever been used on the device.|0 = off; 1 = on
subscription_mode|The CDMA subscription mode 0 = RUIM/SIM (default) 1 = NV|0 = RUIM/SIM (default; 1 = NV
sync_manager_constants|Hidden tuning values of background sync.|text: name=value pairs separated by commas
sys_free_storage_log_interval|The interval in minutes after which the amount of free storage left on the device is logged to the event log|minutes
sys_traced|traced global setting. This controls weather the deamons: traced and traced_probes run. This links the sys.traced system property.
sys_uidcpupower|UidCpuPower global setting. This links the sys.uidcpupower system property.
system_server_watchdog_timeout_ms|Timeout for the system server watchdog.
sysui_demo_allowed|Allows System UI demo mode, which shows a clean status bar for screenshots.|0 = off; 1 = on
sysui_tuner_demo_on|Demo mode is on right now.|0 = off; 1 = on
tcp_default_init_rwnd|Used to select TCP's default initial receiver window size in segments - defaults to a build config value.
tether_dun_apn|Used to hold a gservices-provisioned apn value for DUN. If set, or the corresponding build config values are set it will override the APN DB values.|a list, items separated by , or :
tether_dun_required|Tethering needs the special DUN mobile connection.|0 = no; 1 = yes; -1 = decided by the carrier
tether_enable_legacy_dhcp_server|Use the old dnsmasq DHCP server for tethering instead of the framework implementation. Integer values are interpreted as boolean, and the absence of an explicit setting is interpreted as /false/.|0 = off; 1 = on
tether_offload_disabled|Switches off hardware speed-up for tethering.|0 = speed-up on; 1 = speed-up off
tether_supported|Allows tethering (hotspot and USB sharing).|0 = no; 1 = yes
text_classifier_action_model_params|A serialized string of params that will be loaded into a text classifier action model.
text_classifier_constants|TextClassifier specific settings. This is encoded as a key=value list, separated by commas. String[] types like entity_list_default use ":" as delimiter for values.|0 = off; 1 = on
theater_mode_on|Theater mode.|0 = off; 1 = on
time_only_mode_constants|Time Only Mode specific settings. This is encoded as a key=value list, separated by commas.|0 = off; 1 = on
time_remaining_estimate_based_on_usage|Deprecated. A boolean indicating whether TIME_REMAINING_ESTIMATE_MILLIS is customized to the device's usage or using global models. See BATTERY_ESTIMATES_LAST_UPDATE_TIME for the last time this value was updated.|0 = off; 1 = on
time_remaining_estimate_millis|Deprecated. A long value indicating how much longer the system battery is estimated to last in millis. See BATTERY_ESTIMATES_LAST_UPDATE_TIME for the last time this value was updated.
transition_animation_scale|Speed of the animation between screens (developer option "Transition animation scale").|decimal; 1 = normal; 0 = no animation; 0.5 = twice as fast; 2 = twice as slow
trusted_sound|URI for the "device is trusted" sound, which is played when the device enters the trusted state without unlocking.|web address (URL)
tzinfo_content_url|URL for tzinfo (time zone) updates|web address (URL)
tzinfo_metadata_url|URL for tzinfo (time zone) update metadata|web address (URL)
ungaze_sleep_enabled|Whether of not to send keycode sleep for ungaze when Home is the foreground activity on watch type devices.|0 = off; 1 = on
uninstalled_instant_app_max_cache_period|The max period for caching uninstalled instant apps in milliseconds.|milliseconds
uninstalled_instant_app_min_cache_period|The min period for caching uninstalled instant apps in milliseconds.|milliseconds
unlock_sound|URI for the "device unlocked" sound.|web address (URL)
unused_static_shared_lib_min_cache_period|The min period for caching unused static shared libs in milliseconds.|milliseconds
updatable_driver_all_apps|Updatable driver global preference for all Apps. 0 = Default 1 = All Apps use updatable production driver 2 = All apps use updatable prerelease driver 3 = All Apps use system graphics driver|0 = Default; 1 = All Apps use updatable production driver; 2 = All apps use updatable prerelease driver; 3 = All Apps use system graphics driver
updatable_driver_prerelease_opt_in_apps|List of Apps selected to use updatable prerelease driver. i.e. ,,...,
updatable_driver_production_allowlist|Apps on the allowlist that are allowed to use updatable production driver. The string is a list of application package names, seperated by comma. i.e. ,,...,
updatable_driver_production_denylist|Apps on the denylist that are forbidden to use updatable production driver.
updatable_driver_production_denylists|List of denylists, each denylist is a denylist for a specific version of updatable production driver.
updatable_driver_production_opt_in_apps|List of Apps selected to use updatable production driver. i.e. ,,...,
updatable_driver_production_opt_out_apps|List of Apps selected not to use updatable production driver. i.e. ,,...,
updatable_driver_sphal_libraries|List of libraries in sphal accessible by updatable driver The string is a list of library names, separated by colon. i.e. ::...:
usb_mass_storage_enabled|USB mass storage mode (very old phones).|0 = off; 1 = on
use_google_mail|Shows "Google Mail" instead of "Gmail".|0 or empty = no; any value = yes
use_open_wifi_package|Deprecated. The package name of the application that connect and secures high quality open wifi networks automatically.|package name
user_absent_radios_off_for_small_battery_enabled|Whether or not to enable the User Absent, Radios Off feature on small battery devices.|0 = off; 1 = on
user_absent_touch_off_for_small_battery_enabled|Whether or not to enable the User Absent, Touch Off feature on small battery devices.|0 = off; 1 = on
user_disabled_hdr_formats|A comma-separated list of HDR formats that have been disabled by the user. If present, these formats will not be reported to apps, even if the display supports them.|a list, items separated by , or :
user_preferred_refresh_rate|Screen refresh rate you chose.|Hz, e.g. 60 or 120; 0 = automatic
user_preferred_resolution_height|The resolution height chosen by the user.
user_preferred_resolution_width|The resolution width chosen by the user.
user_switcher_enabled|Shows the user switcher so people can switch accounts.|0 = off; 1 = on
uwb_enabled|Ultra-wideband radio (precise nearby device finding).|0 = off; 1 = on
verifier_default_response|Default response code for package verification.
verifier_setting_visible|Show package verification setting in the Settings app. 1 = show (default) 0 = hide|1 = show (default; 0 = hide
verifier_timeout|Timeout for package verification.
verifier_verify_adb_installs|Checks apps installed over ADB for harm.|0 = off; 1 = on
verify_integrity_for_rule_provider|Run integrity checks for integrity rule providers. 0 = bypass integrity verification on installs from rule providers (default) 1 = perform integrity verification on installs from rule providers
wait_for_debugger|Makes the debug app wait for a debugger before it starts (developer option).|0 = off; 1 = on
warning_temperature|Battery temperature at which the "phone is hot" warning appears.|degrees Celsius, e.g. 45
webview_data_reduction_proxy_key|Webview Data reduction proxy key.
webview_multiprocess|Runs web content in its own process (developer option).|0 = off; 1 = on
webview_provider|The app that provides WebView, the engine apps use to show web pages.|package name; empty = the system default
wifi_always_requested|Keeps Wi-Fi connected even when a better network such as Ethernet is available.|0 = off; 1 = on
wifi_badging_thresholds|The thresholds of the wifi throughput badging (SD, HD etc.) as a comma-delimited list of colon-delimited key-value pairs.
wifi_bounce_delay_override_ms|Milliseconds to wait before bouncing Wi-Fi after settings is restored. Note that after the caller is done with this, they should call delete to clean up any value that they may have written.
wifi_connected_mac_randomization_enabled|Deprecated. Setting to enable connected MAC randomization in Wi-Fi; disabled by default, and setting to 1 will enable it. In the future, additional values may be supported.|0 = off; 1 = on
wifi_country_code|The country the Wi-Fi radio uses to know which channels are allowed.|two letters, e.g. US
wifi_device_owner_configs_lockdown|This setting controls whether WiFi configurations created by a Device Owner app should be locked down (that is, be editable or removable only by the Device Owner App, not even by Settings app).
wifi_display_certification_on|Whether Wifi display certification mode is enabled/disabled 0=disabled. 1=enabled.|0 = off; 1 = on
wifi_display_on|Wireless display (screen casting).|0 = off; 1 = on
wifi_display_wps_config|WPS Configuration method used by Wifi display, this setting only takes effect when WIFI_DISPLAY_CERTIFICATION_ON is 1 (enabled).
wifi_enhanced_auto_join|whether frameworks handles wifi auto-join
wifi_ephemeral_out_of_range_timeout_ms|Timeout for ephemeral networks when all known BSSIDs go out of range.
wifi_framework_scan_interval_ms|The interval in milliseconds to issue wake up scans when wifi needs to connect. This is necessary to connect to an access point when device is on the move and the screen is off.|milliseconds
wifi_frequency_band|The operational wifi frequency band Set to one of WIFI_FREQUENCY_BAND_AUTO, WIFI_FREQUENCY_BAND_5GHZ or WIFI_FREQUENCY_BAND_2GHZ
wifi_idle_ms|The interval in milliseconds after which Wi-Fi is considered idle. When idle, it is possible for the device to be switched from Wi-Fi to the mobile data network.|milliseconds
wifi_max_dhcp_retry_count|How many times to retry getting an address from the router.|whole number
wifi_migration_completed|Value to specify if wifi settings migration is complete or not. Note: This should only be used from within WifiMigration class.|0 = off; 1 = on
wifi_mobile_data_transition_wakelock_timeout_ms|Maximum amount of time in milliseconds to hold a wakelock while waiting for mobile data connectivity to be established after a disconnect from Wi-Fi.|milliseconds
wifi_network_show_rssi|whether settings show RSSI
wifi_networks_available_notification_on|Notifies you when open Wi-Fi networks are nearby (older setting).|0 = off; 1 = on
wifi_networks_available_repeat_delay|Deprecated. Delay (in seconds) before repeating the Wi-Fi networks available notification. Connecting to a network will reset the timer.|seconds
wifi_num_open_networks_kept|Deprecated. When the number of open networks exceeds this number, the least-recently-used excess networks will be removed.
wifi_on|Wi-Fi.|0 = off; 1 = on; 2 = on although airplane mode is on; 3 = off because airplane mode is on
wifi_on_when_proxy_disconnected|Whether or not to turn on Wifi when proxy is disconnected.|0 = off; 1 = on
wifi_p2p_device_name|Deprecated. The Wi-Fi peer-to-peer device name
wifi_p2p_pending_factory_reset|Deprecated. Indicate whether factory reset request is pending.|0 = off; 1 = on
wifi_scan_always_enabled|Lets apps scan for Wi-Fi even when Wi-Fi is off (improves location).|0 = off; 1 = on
wifi_scan_interval_p2p_connected_ms|The interval in milliseconds to scan at supplicant when p2p is connected|milliseconds
wifi_scan_throttle_enabled|Limits how often apps may scan for Wi-Fi.|0 = off; 1 = on
wifi_score_params|Deprecated. Parameters to adjust the performance of framework wifi scoring methods.|text: name=value pairs separated by commas
wifi_sleep_policy|Whether Wi-Fi stays on while the screen is off (older phones).|0 = turn off with the screen; 1 = stay on while charging; 2 = always stay on
wifi_supplicant_scan_interval_ms|The interval in milliseconds to scan as used by the wifi supplicant|milliseconds
wifi_verbose_logging_enabled|More detailed Wi-Fi logs.|0 = off; 1 = on
wifi_wakeup_enabled|Turns Wi-Fi back on near networks you saved.|0 = off; 1 = on
wifi_watchdog_on|Watches Wi-Fi quality and leaves poor networks.|0 = off; 1 = on
wifi_watchdog_poor_network_test_enabled|Setting to turn off poor network avoidance on Wi-Fi. Feature is enabled by default and the setting needs to be set to 0 to disable it.
window_animation_scale|Speed of window open and close animations (developer option "Window animation scale").|decimal; 1 = normal; 0 = no animation; 0.5 = twice as fast; 2 = twice as slow
wireless_charging_started_sound|URI for the "wireless charging started" sound.|web address (URL)
wm_display_settings_path|Path to the WindowManager display settings file. If unset, the default file path will be used.
wtf_is_fatal|Makes serious "should never happen" log messages crash the app (developer option).|0 = no; 1 = yes
zen_duration|How long Do Not Disturb stays on when you switch it on from Quick Settings.|minutes; 0 = until you turn it off; -1 = ask each time
zen_mode|Do Not Disturb.|0 = off; 1 = priority interruptions only; 2 = total silence; 3 = alarms only
zen_mode_config_etag|Version stamp of your Do Not Disturb rules. Android keeps this by itself.|number
zen_mode_ringer_level|The ringer mode from before Do Not Disturb turned on.|0 = silent; 1 = vibrate; 2 = normal
zram_enabled|Compressed swap memory (zram).|0 = off; 1 = on; takes effect after a restart`,
secure: `accessibility_allow_diagonal_scrolling|While magnified, lets you pan the zoomed screen diagonally.|0 = off; 1 = on
accessibility_autoclick_delay|How long the mouse pointer must stay still before it clicks by itself.|milliseconds, e.g. 600
accessibility_autoclick_enabled|Clicks by itself when the mouse pointer stops moving.|0 = off; 1 = on
accessibility_bounce_keys|Ignores a repeated press of the same key on a physical keyboard within this time.|milliseconds; 0 = off
accessibility_button_mode|Where the accessibility shortcut button lives.|0 = in the navigation bar; 1 = floating button; 2 = use the navigation gesture
accessibility_button_target_component|Setting specifying the accessibility service or feature to be toggled via the accessibility button in the navigation bar.
accessibility_button_targets|The accessibility features the accessibility button starts.|list of component names separated by :
accessibility_captioning_background_color|Background colour behind captions.|colour as a whole number (ARGB)
accessibility_captioning_edge_color|Edge colour of caption text.|colour as a whole number (ARGB)
accessibility_captioning_edge_type|Edge style of caption text.|0 = none; 1 = outline; 2 = drop shadow; 3 = raised; 4 = depressed
accessibility_captioning_enabled|Shows captions (subtitles) in video.|0 = off; 1 = on
accessibility_captioning_font_scale|Size of caption text.|decimal; 1 = normal; 0.25 to 2 are usual
accessibility_captioning_foreground_color|Colour of caption text.|colour as a whole number (ARGB)
accessibility_captioning_locale|Language of captions.|locale, e.g. en_US; empty = the phone language
accessibility_captioning_preset|Caption style preset.|-1 = custom; 0 to 4 = the presets in Settings
accessibility_captioning_typeface|Font of captions.|DEFAULT; MONOSPACE; SANS_SERIF; SERIF
accessibility_captioning_window_color|Colour of the window behind captions.|colour as a whole number (ARGB)
accessibility_display_daltonizer|Colour correction type.|-1 = off; 0 = grayscale; 11 = red-weak (protanomaly); 12 = green-weak (deuteranomaly); 13 = blue-weak (tritanomaly)
accessibility_display_daltonizer_enabled|Colour correction for colour blindness.|0 = off; 1 = on
accessibility_display_daltonizer_saturation_level|How strong the colour correction is.|whole number, 0 to 10
accessibility_display_inversion_enabled|Colour inversion.|0 = off; 1 = on
accessibility_display_magnification_auto_update|Deprecated. Unused mangnification setting
accessibility_display_magnification_edge_haptic_enabled|Whether the feature that the device will fire a haptic when users scroll and hit the edge of the screen is enabled.|0 = off; 1 = on
accessibility_display_magnification_enabled|Magnify the screen with a triple tap.|0 = off; 1 = on
accessibility_display_magnification_navbar_enabled|Deprecated. Setting that specifies whether the display magnification is enabled via a shortcut affordance within the system's navigation area.|0 = off; 1 = on
accessibility_display_magnification_scale|How much the magnifier zooms.|decimal, 1 to 8; 2 is the default
accessibility_enabled|Whether any accessibility service is switched on. Android keeps this by itself.|0 = off; 1 = on|lock
accessibility_floating_menu_fade_enabled|The floating accessibility button fades when not used.|0 = off; 1 = on
accessibility_floating_menu_icon_type|Shape of the floating accessibility button.|0 = full circle; 1 = half circle
accessibility_floating_menu_migration_tooltip_prompt|Prompts the user to the Accessibility button is replaced with the floating menu. 0 = disabled 1 = enabled|0 = disabled; 1 = enabled
accessibility_floating_menu_opacity|How see-through the floating button gets when it fades.|decimal, 0 (invisible) to 1 (solid)
accessibility_floating_menu_size|Size of the floating accessibility button.|0 = small; 1 = large
accessibility_font_scaling_has_been_changed|Whether you ever changed the text size.|0 = no; 1 = yes
accessibility_force_invert_color_enabled|Forces dark appearance on apps that have no dark theme.|0 = off; 1 = on
accessibility_gesture_targets|The accessibility features the accessibility gesture starts.|list of component names separated by :
accessibility_interactive_ui_timeout_ms|How long controls that need a tap stay on screen ("Time to take action").|milliseconds; 0 = use the app's own time
accessibility_key_gesture_targets|Setting specifying the accessibility services, accessibility shortcut targets, or features to be toggled via a keyboard shortcut gesture.|a list, items separated by , or :
accessibility_large_pointer_icon|Bigger mouse pointer.|0 = normal; 1 = large
accessibility_magnification_always_on_enabled|Keeps the magnifier on when you switch apps.|0 = off; 1 = on
accessibility_magnification_capability|Which magnification modes are allowed.|1 = full screen; 2 = a window; 3 = both
accessibility_magnification_follow_typing_enabled|Whether the following typing focus feature for magnification is enabled.|0 = off; 1 = on
accessibility_magnification_joystick_enabled|Whether the magnification joystick controller feature is enabled.|0 = off; 1 = on
accessibility_magnification_mode|Which magnification mode the shortcut starts.|1 = full screen; 2 = a window; 3 = switch between them
accessibility_magnification_two_finger_triple_tap_enabled|Setting that specifies whether the display magnification is enabled via a system-wide two fingers triple tap gesture.|0 = off; 1 = on
accessibility_mouse_keys_enabled|Whether to enable mouse keys for Physical Keyboard accessibility. If set to true, key presses (of the mouse keys) on physical keyboard will control mouse pointer on the display.|0 = off; 1 = on
accessibility_non_interactive_ui_timeout_ms|How long messages that need no action stay on screen ("Time to read").|milliseconds; 0 = use the app's own time
accessibility_pinch_to_zoom_anywhere_enabled|For pinch to zoom anywhere feature. If true, you should be able to pinch to magnify the window anywhere.
accessibility_qs_targets|Setting specifying the accessibility services, accessibility shortcut targets, or features to be toggled via a tile in the quick settings panel.|a list, items separated by , or :
accessibility_shortcut_dialog_shown|Setting specifying if the accessibility shortcut dialog has been shown to this user.
accessibility_shortcut_on_lock_screen|Allows the accessibility shortcut on the lock screen.|0 = no; 1 = yes
accessibility_shortcut_target_service|The accessibility feature the volume-key shortcut starts.|component name, e.g. com.google.android.marvin.talkback/...TalkBackService
accessibility_show_window_magnification_prompt|Whether to show the window magnification prompt dialog when the user uses full-screen magnification first time after database is upgraded.|0 = off; 1 = on
accessibility_single_finger_panning_enabled|For magnification feature where panning can be controlled with a single finger. If true, you can pan using a single finger gesture.
accessibility_slow_keys|Ignores key presses shorter than this on a physical keyboard.|milliseconds; 0 = off
accessibility_soft_keyboard_mode|Whether the on-screen keyboard shows while a hardware keyboard is used.|0 = default; 1 = hidden
accessibility_sticky_keys|Sticky keys: Shift, Ctrl and Alt stay pressed for the next key.|0 = off; 1 = on
active_unlock_on_biometric_fail|Whether or not active unlock triggers on biometric failure.|0 = off; 1 = on
active_unlock_on_face_acquire_info|If active unlock triggers on biometric failures, include the following acquired info as a "biometric failure". See BiometricFaceConstants. Acquired codes should be separated by a pipe.
active_unlock_on_face_errors|If active unlock triggers on biometric failures, include the following error codes as a biometric failure. See BiometricFaceConstants. Error codes should be separated by a pipe. For example: "1/4/5".
active_unlock_on_unlock_intent|Whether or not active unlock triggers on unlock intent.|0 = off; 1 = on
active_unlock_on_unlock_intent_legacy|Whether or not active unlock triggers on legacy unlock intents.|0 = off; 1 = on
active_unlock_on_unlock_intent_when_biometric_enrolled|If active unlock triggers on biometric failures, then also request active unlock on unlock intent when each setting (BiometricType) is the only biometric type enrolled.|0 = None; 1 = Any face; 2 = Any fingerprint; 3 = Under display fingerprint
active_unlock_on_wake|Lets a trusted device unlock the phone when the screen wakes.|0 = off; 1 = on
active_unlock_wakeups_considered_unlock_intents|If active unlock triggers on unlock intents, then also request active unlock on these wake-up reasons. See WakeReason for value mappings. WakeReasons should be separated by a pipe.
active_unlock_wakeups_to_force_dismiss_keyguard|If active unlock triggers and succeeds on these wakeups, force dismiss keyguard on these wake reasons. See WakeReason for value mappings. WakeReasons should be separated by a pipe.
adaptive_charging_enabled|Adaptive Charging: slows charging overnight to protect the battery.|0 = off; 1 = on
adaptive_connectivity_enabled|Adaptive connectivity: switches between mobile and Wi-Fi to save power.|0 = off; 1 = on
adaptive_sleep|Screen Attention: keeps the screen on while you look at it.|0 = off; 1 = on
advanced_protection_mode|Advanced Protection mode.|0 = off; 1 = on
allow_primary_gaia_account_removal_for_tests|1 if it is allowed to remove the primary GAIA account. 0 by default.
allowed_geolocation_origins|Websites that may use your location by default in the browser.|list separated by spaces
always_on_vpn_app|The app set as always-on VPN.|package name; empty = none
always_on_vpn_lockdown|Blocks all traffic that does not go through the always-on VPN.|0 = off; 1 = on
always_on_vpn_lockdown_whitelist|Apps that may use the network while VPN lockdown is on.|list of package names separated by commas
ambient_context_consent_component|Current provider of the component for requesting ambient context consent. Default value in @string/config_defaultAmbientContextConsentComponent. No VALIDATOR as this setting will not be backed up.
ambient_context_event_array_key|Current provider of the intent extra key for the event code int array while requesting ambient context consent. Default value in @string/config_ambientContextEventArrayExtraKey.
ambient_context_package_name_key|Current provider of the intent extra key for the caller's package name while requesting ambient context consent. No VALIDATOR as this setting will not be backed up.
android_id|The ID this phone gives to apps (one per app signing key and user). Changing it makes apps treat the phone as new.|16 hexadecimal characters|break
anr_show_background|Shows "not responding" dialogs for apps in the background.|0 = off; 1 = on
assist_disclosure_enabled|Shows a glow when the assistant reads the screen.|0 = off; 1 = on
assist_gesture_enabled|The squeeze or assistant gesture.|0 = off; 1 = on
assist_gesture_sensitivity|How hard to squeeze for the assistant gesture.|decimal, 0 to 1
assist_gesture_setup_complete|Indicates whether the Assist Gesture Deferred Setup has been completed.|0 = off; 1 = on
assist_gesture_silence_alerts_enabled|Whether the assist gesture should silence alerts.|0 = off; 1 = on
assist_gesture_wake_enabled|Whether the assist gesture should wake the phone.|0 = off; 1 = on
assist_long_press_home_enabled|Whether the assistant can be triggered by long-pressing the home button|0 = off; 1 = on
assist_screenshot_enabled|Lets the assistant use a screenshot of the screen.|0 = off; 1 = on
assist_structure_enabled|Lets the assistant see the text and layout of the current app.|0 = off; 1 = on
assist_touch_gesture_enabled|Whether the assistant can be triggered by a touch gesture.|0 = off; 1 = on
assistant|The app that answers when you call the assistant.|component name; empty = none
attentive_timeout|How long the phone waits before sleeping when you are not looking at it.|milliseconds
audio_device_inventory|Internal collection of audio device inventory items The device item stored are AdiDeviceState
audio_safe_csd_as_a_feature_enabled|Stores a boolean that defines whether the CSD as a feature is enabled or not.|0 = off; 1 = on
auto_revoke_disabled|Turns off removing permissions from apps you do not use.|0 = permissions are removed; 1 = they are kept
autofill_field_classification|Boolean indicating if Autofill supports field classification.|0 = off; 1 = on
autofill_service|The app that fills in passwords and forms.|component name; empty = none
autofill_service_search_uri|This is the query URI for finding a auto fill service to install.
autofill_user_data_max_category_count|Defines value returned by getMaxCategoryCount().
autofill_user_data_max_field_classification_size|Defines value returned by getMaxFieldClassificationIdsSize().
autofill_user_data_max_user_data_size|Defines value returned by getMaxUserDataSize().
autofill_user_data_max_value_length|Defines value returned by getMaxValueLength().
autofill_user_data_min_value_length|Defines value returned by getMinValueLength().
automatic_storage_manager_bytes_cleared|How many bytes the automatic storage manager has cleared out.
automatic_storage_manager_days_to_retain|Storage manager deletes backed-up photos and videos older than this.|days: 30, 60 or 90
automatic_storage_manager_enabled|Storage manager frees space by itself.|0 = off; 1 = on
automatic_storage_manager_last_run|Last run time for the automatic storage manager.
automatic_storage_manager_turned_off_by_policy|If the automatic storage manager has been disabled by policy.
aware_enabled|Controls whether aware is enabled.|0 = off; 1 = on
aware_lock_enabled|Controls whether aware_lock is enabled.|0 = off; 1 = on
aware_tap_pause_gesture_count|Number of successful "Motion Sense" tap gestures to pause media.
aware_tap_pause_touch_count|Number of touch interactions to pause media when a "Motion Sense" gesture could have been used.
back_gesture_inset_scale_left|Scale factor for the back gesture inset size on the left side of the screen.
back_gesture_inset_scale_right|Scale factor for the back gesture inset size on the right side of the screen.
background_data|Deprecated. Whether background data usage is allowed.|0 = off; 1 = on
backup_auto_restore|Restores an app's data when it is reinstalled.|0 = off; 1 = on
backup_enabled|Backs up app data and settings to your account.|0 = off; 1 = on
backup_local_transport_parameters|Local transport parameters so we can configure it for tests. This is encoded as a key=value list, separated by commas. The following keys are supported: fake_encryption_flag (boolean)|0 = off; 1 = on
backup_manager_constants|Backup manager behavioral parameters. This is encoded as a key=value list, separated by commas.|0 = off; 1 = on
backup_provisioned|Indicates whether settings backup has been fully provisioned.|0 = unprovisioned; 1 = fully provisioned
backup_scheduling_enabled|Controls whether framework backup scheduling is enabled.|0 = off; 1 = on
backup_transport|The service that backs up and restores data.|component name
biometric_app_enabled|Lets apps use fingerprint or face to sign in.|0 = off; 1 = on
biometric_debug_enabled|Whether or not debugging is enabled.|0 = off; 1 = on
biometric_face_virtual_enabled|Whether or not face virtual sensors are enabled.|0 = off; 1 = on
biometric_fingerprint_virtual_enabled|Whether or not fingerprint virtual sensors are enabled.|0 = off; 1 = on
biometric_keyguard_enabled|Lets fingerprint or face unlock the screen.|0 = off; 1 = on
biometric_virtual_enabled|Whether or not both fingerprint and face virtual sensors are enabled.|0 = off; 1 = on
bluetooth_addr_valid|This is used by Bluetooth Manager to store whether adapter address is valid
bluetooth_address|The Bluetooth address of this phone. Android keeps this by itself.|address like AA:BB:CC:DD:EE:FF
bluetooth_le_broadcast_app_source_name|This is used by LocalBluetoothLeBroadcast to store the app source name.
bluetooth_le_broadcast_code|This is used by LocalBluetoothLeBroadcast to store the broadcast code.
bluetooth_le_broadcast_fallback_active_device_address|This is used by LocalBluetoothLeBroadcast to store the fallback active device address.
bluetooth_le_broadcast_improve_compatibility|This is used by LocalBluetoothLeBroadcast to downgrade the broadcast quality to improve compatibility. 0 = false 1 = true|0 = false; 1 = true
bluetooth_le_broadcast_name|This is used by LocalBluetoothLeBroadcast to store the broadcast name.
bluetooth_le_broadcast_program_info|This is used by LocalBluetoothLeBroadcast to store the broadcast program info.
bluetooth_name|The Bluetooth name of this phone.|text
bluetooth_on_while_driving|Flag to set if the system should predictively attempt to re-enable Bluetooth while the user is driving.
bubble_important_conversations|When enabled conversations marked as favorites will be set to bubble. The value 1 - enable, 0 - disable|1 = enable; 0 = disable
bugreport_in_power_menu|Shows "Take bug report" in the power menu.|0 = hidden; 1 = shown
call_screening_default_component|Specifies the component name currently configured to be the default call screening application
camera_autorotate|Rotates the screen using the camera to see how you hold the phone.|0 = off; 1 = on
camera_double_tap_power_gesture_disabled|Turns off "double-press the power button to open the camera".|0 = gesture works; 1 = gesture turned off
camera_double_twist_to_flip_enabled|Whether the camera double twist gesture to flip between front and back mode should be enabled.|0 = off; 1 = on
camera_extensions_fallback|Whether to enable camera extensions software fallback.|0 = off; 1 = on
camera_gesture_disabled|Turns off the camera launch gesture.|0 = gesture works; 1 = gesture turned off
camera_lift_trigger_enabled|Opens the camera when you lift the phone.|0 = off; 1 = on
carrier_apps_handled|The latest SDK version that CarrierAppUtils#disableCarrierAppsUntilPrivileged has been executed for.
charge_optimization_mode|Integer property that determines which charging optimization mode is applied. [0-10] inclusive representing different modes, where 0 is the default indicating no optimization mode is applied.
charging_sounds_enabled|Plays a sound when charging starts.|0 = off; 1 = on
charging_vibration_enabled|Vibrates when charging starts.|0 = off; 1 = on
clipboard_show_access_notifications|Shows a message when an app reads what you copied.|0 = off; 1 = on
clock_seconds|Shows seconds in the status bar clock.|0 = off; 1 = on
cmas_additional_broadcast_pkg|Specifies additional package name for broadcasting the CMAS messages.
communal_mode_enabled|Control whether communal mode is allowed on this device.
communal_mode_trusted_networks|An array of SSIDs of Wi-Fi networks that, when connected, are considered safe to enable the communal mode.
compat_ui_education_showing|Whether any Compat UI Education is currently showing. 1 if true, 0 or unset otherwise.|0 = off; 1 = on
connectivity_release_pending_intent_delay_ms|The number of milliseconds to hold on to a PendingIntent based request.
content_capture_enabled|Content capture, which lets the system see on-screen text for suggestions.|0 = off; 1 = on
contextual_screen_timeout_enabled|Keeps the screen on longer when it is needed.|0 = off; 1 = on
contextual_search_package|String property which contains the package name of the contextual search provider supplied by individual OEM's R.string.config_defaultContextualSearchPackageName.
contrast_level|Colour contrast of the system theme.|decimal, -1 (lowest) to 1 (highest); 0 = standard
controls_enabled|Device controls (smart home) in the power menu.|0 = off; 1 = on
credential_service|Apps that provide passkeys and passwords.|list of component names separated by :
credential_service_primary|The currently selected primary credential service flattened ComponentName.
cross_profile_calendar_enabled|Whether parent profile can access remote calendar data in managed profile.|0 = off; 1 = on
custom_bugreport_handler_app|The package name for the custom bugreport handler app. This app must be bugreport allow-listed. This is currently used only by Power Menu short press.|package name
custom_bugreport_handler_user|The user id for the custom bugreport handler app. This is currently used only by Power Menu short press.
dark_mode_dialog_seen|Boolean indicating if the dark mode dialog shown on first toggle has been seen.|0 = off; 1 = on
dark_theme_custom_end_time|When the dark theme turns off on a custom schedule.|milliseconds after midnight, e.g. 25200000 = 7:00
dark_theme_custom_start_time|When the dark theme turns on on a custom schedule.|milliseconds after midnight, e.g. 72000000 = 20:00
default_device_input_method|Used only by InputMethodManagerService as a temporary data store of DEFAULT_INPUT_METHOD while a virtual-device-specific input method is set as default.
default_input_method|The keyboard that is in use.|component name, e.g. com.google.android.inputmethod.latin/...LatinIME|lock
default_note_task_profile|Preferred default user profile to use with the notes task button shortcut.
default_voice_input_method|The getId() ID of the default voice input method. This stores the last known default voice IME. If the related system config value changes, this is reset by InputMethodManagerService.
device_paired|Has this pairable device been paired or upgraded from a previously paired system.
device_state_rotation_lock|Rotation lock setting keyed on device state.
dialer_default_application|The app that makes calls.|package name
disable_secure_windows|Whether or not secure windows should be disabled. This only works on debuggable builds. When this setting is set to a non-zero value, all windows are treated as non-secure.|0 = off; 1 = on
disabled_print_services|Print services that are switched off.|list of component names separated by :
disabled_system_input_methods|Built-in keyboards that are switched off.|list of keyboard ids separated by :
display_density_forced|Screen density you set with "wm density".|dots per inch, e.g. 420; empty = the phone's own
display_white_balance_enabled|Display white balance.|0 = off; 1 = on
dnd_settings_migrated|If 1, DND default allowed packages have been updated
dock_setup_state|Defines the user's current state of dock setup. The possible states are defined in DockSetupState.
docked_clock_face|Indicates which clock face to show on lock screen and AOD while docked.
double_tap_power_button_gesture|What a double press of the power button does.|0 = open the camera; 1 = open the wallet
double_tap_power_button_gesture_enabled|Double-press the power button for a shortcut.|0 = off; 1 = on
double_tap_to_wake|Wakes the screen when you double-tap it.|0 = off; 1 = on
doze_always_on|Always-on display.|0 = off; 1 = on
doze_enabled|Allows the display to show a low-power screen (ambient display).|0 = off; 1 = on
doze_pulse_on_double_tap|Ambient display shows when you double-tap the screen.|0 = off; 1 = on
doze_pulse_on_long_press|Ambient display shows when you long-press the screen.|0 = off; 1 = on
doze_pulse_on_pick_up|Ambient display shows when you pick the phone up.|0 = off; 1 = on
doze_quick_pickup_gesture|Gesture that wakes up the display on quick pickup, toggling between STATE_OFF and STATE_DOZE.
doze_tap_gesture|Ambient display shows when you tap the screen.|0 = off; 1 = on
doze_wake_display_gesture|Gesture that wakes up the display, toggling between STATE_OFF and STATE_DOZE.
doze_wake_screen_gesture|Wakes the screen with a gesture.|0 = off; 1 = on
emergency_assistance_application|The app used for emergency assistance.|package name
emergency_gesture_enabled|The emergency gesture (press power button quickly several times).|0 = off; 1 = on
emergency_gesture_sound_enabled|Plays a sound while the emergency gesture counts down.|0 = off; 1 = on
emergency_gesture_ui_last_started_millis|The last time the emergency gesture UI was started.
emergency_gesture_ui_showing|Whether the emergency gesture UI is currently showing.|0 = off; 1 = on
emergency_thermal_alert_disabled|Whether the emergency thermal alert would be disabled (0: default) or not (1).|0 = off; 1 = on
enabled_accessibility_audio_description_by_default|Whether select sound track with audio description by default.|0 = off; 1 = on
enabled_accessibility_services|Accessibility services that are on.|list of component names separated by :|lock
enabled_input_methods|Keyboards that are switched on.|list of keyboard ids separated by :|lock
enabled_notification_assistant|The app that sorts and suggests actions for notifications.|component name
enabled_notification_listeners|Apps that may read all your notifications.|list of component names separated by :
enabled_notification_policy_access_packages|Apps that may change Do Not Disturb.|list of package names separated by :
enabled_print_services|Print services that are switched on.|list of component names separated by :
enabled_vr_listeners|Services that may know when VR mode is on.|list of component names separated by :
enhanced_voice_privacy_enabled|Whether the enhanced voice privacy mode is enabled. 0 = normal voice privacy 1 = enhanced voice privacy|0 = normal voice privacy; 1 = enhanced voice privacy
even_dimmer_activated|Extra dim: lets the screen go dimmer than the lowest brightness.|0 = off; 1 = on
even_dimmer_min_nits|Setting that specifies which nits level Even Dimmer should allow the screen brightness to go down to.
extra_automatic_power_save_mode|Whether battery saver is currently set to different schedule mode.|0 = off; 1 = on
extra_low_power_warning_acknowledged|You saw the extreme battery saver warning.|0 = no; 1 = yes
face_unlock_always_require_confirmation|Face unlock asks you to tap Confirm.|0 = off; 1 = on
face_unlock_app_enabled|Face unlock may be used inside apps.|0 = off; 1 = on
face_unlock_attention_required|Whether or not face unlock requires attention. This is a cached value, the source of truth is obtained through the HAL.|0 = off; 1 = on
face_unlock_dismisses_keyguard|Whether or not face unlock dismisses the keyguard.|0 = off; 1 = on
face_unlock_diversity_required|Whether or not face unlock requires a diverse set of poses during enrollment. This is a cached value, the source of truth is obtained through the HAL.|0 = off; 1 = on
face_unlock_keyguard_enabled|Face unlock may unlock the screen.|0 = off; 1 = on
face_unlock_re_enroll|Whether or not a user should re enroll their face. Face unlock re enroll. 0 = No re enrollment. 1 = Re enrollment is required.|0 = No re enrollment; 1 = Re enrollment is required
fingerprint_side_fps_auth_downtime|The time (in millis) that a power event will ignore future authentications (for side fingerprint)
fingerprint_side_fps_bp_power_window|The time (in millis) to wait for a power button before sending a successful auth in biometric prompt(for side fingerprint)
fingerprint_side_fps_enroll_tap_window|The time (in millis) that a finger tap will wait for a power button before dismissing the power dialog during enrollment(for side fingerprint)
fingerprint_side_fps_kg_power_window|The time (in millis) to wait for a power button before sending a successful auth in to keyguard(for side fingerprint)
flashlight_available|A flashlight can be turned on now. Android keeps this by itself.|0 = no; 1 = yes
flashlight_enabled|The flashlight is on.|0 = off; 1 = on
font_weight_adjustment|Bold text.|0 = normal; 300 = bold (small steps are added to the font weight)
game_dashboard_always_on|Shows the Game Dashboard shortcut in every game.|0 = off; 1 = on
glanceable_hub_enabled|Defines the enabled state for the glanceable hub.
global_actions_panel_available|Whether the Global Actions Panel can be toggled on or off in Settings.|0 = off; 1 = on
global_actions_panel_debug_enabled|Enables debug mode for the Global Actions Panel.
global_actions_panel_enabled|Whether the Global Actions Panel is enabled.|0 = off; 1 = on
hdmi_cec_set_menu_language_denylist|Setting to store denylisted system languages by the CEC confirmation dialog.
hearing_aid_call_routing|Where call sound plays when a hearing aid is connected.|0 = default; 1 = hearing aid; 2 = phone speaker
hearing_aid_media_routing|Where media sound plays when a hearing aid is connected.|0 = default; 1 = hearing aid; 2 = phone speaker
hearing_aid_notification_routing|Where notification sound plays when a hearing aid is connected.|0 = default; 1 = hearing aid; 2 = phone speaker
hearing_aid_ringtone_routing|Where the ringtone plays when a hearing aid is connected.|0 = default; 1 = hearing aid; 2 = phone speaker
hide_privatespace_entry_point|Controls whether to hide private space entry point in All Apps|0 = off; 1 = on
high_text_contrast_enabled|High contrast text.|0 = off; 1 = on
hinge_angle_lidevent_enabled|Whether hinge angle lidevent is enabled.|0 = off; 1 = on
hub_mode_tutorial_state|Defines the user's current state of navigating through the hub mode tutorial. Some possible states are defined in HubModeTutorialState.
hush_gesture_used|You have used the "hush" gesture once.|0 = no; 1 = yes
icon_blacklist|Status bar icons that are hidden.|icon names separated by commas, e.g. rotate,headset; empty = none hidden
immersive_mode_confirmations|Apps for which you accepted the "full screen" hint.|text, e.g. confirmed or a package list
in_call_notification_enabled|Plays a sound during calls when a notification comes in.|0 = off; 1 = on
incall_back_button_behavior|What the Back button does during a call.|0 = nothing; 1 = ends the call
incall_power_button_behavior|What the power button does during a call.|1 = turn the screen off; 2 = hang up
input_method_selector_visibility|Setting to record the visibility of input method selector
input_methods_subtype_history|Which keyboard language you used last for each keyboard.|text kept by Android
install_non_market_apps|Allows installing apps from outside the store (older switch; now chosen per app).|0 = no; 1 = yes
instant_apps_enabled|Instant apps (open apps without installing).|0 = off; 1 = on
key_repeat_delay|Time between repeats while a key on a physical keyboard is held.|milliseconds
key_repeat_enabled|Repeats a key held down on a physical keyboard.|0 = off; 1 = on
key_repeat_timeout|Time before a held key starts to repeat.|milliseconds
keyguard_slice_uri|Uri of the slice that's presented on the keyguard. Defaults to a slice with the date and next alarm.
known_trust_agents_initialized|Set to 1 by the system after the list of known trust agents have been initialized.
last_setup_shown|Indicates the version for which the setup wizard was last shown. The version gets bumped for each release when there is new setup information to show.
launcher_taskbar_education_showing|Whether the Taskbar Education is about to be shown or is currently showing. 1 if true, 0 or unset otherwise.|0 = off; 1 = on
location_access_check_delay_millis|Deprecated. Delay between granting location access and checking it.
location_access_check_interval_millis|Deprecated. How often to check for location access.
location_changer|Which app or setting last changed the location mode.|whole number code; Android keeps this by itself
location_mode|Location mode (older setting; now only on or off).|0 = off; 1 = device only (GPS); 2 = battery saving; 3 = high accuracy
location_providers_allowed|Location providers that are on (older setting).|list separated by commas, e.g. gps,network|lock
location_time_zone_detection_enabled|The current location time zone detection enabled state for the user. See getTimeZoneCapabilitiesAndConfig for access. See updateTimeZoneConfiguration to update.
lock_biometric_weak_flags|Deprecated. A flag containing settings used for biometric weak
lock_pattern_autolock|Deprecated. Whether autolock is enabled (0 = false, 1 = true)|0 = false; 1 = true
lock_pattern_visible_pattern|Deprecated. Whether lock pattern is visible as user enters (0 = false, 1 = true)|0 = false; 1 = true
lock_screen_allow_private_notifications|Shows private notification content on the lock screen.|0 = hide the content; 1 = show it
lock_screen_allow_remote_input|Lets you reply to notifications from the lock screen.|0 = no; 1 = yes
lock_screen_appwidget_ids|Deprecated. Ids of the user-selected appwidgets on the lockscreen (comma-delimited).
lock_screen_custom_clock_face|Indicates which clock face to show on lock screen and AOD formatted as a serialized JSONObject with the format: {"clock": id, "_applied_timestamp": timestamp}
lock_screen_fallback_appwidget_id|Deprecated. Id of the appwidget shown on the lock screen when appwidgets are disabled.
lock_screen_lock_after_timeout|How long after the screen turns off before it locks.|milliseconds; 0 = at once
lock_screen_notification_minimalism|Indicates whether to minimalize the number of notifications to show on the lockscreen.|0 = off; 1 = on
lock_screen_owner_info|Text shown on the lock screen, such as owner contact details.|text
lock_screen_owner_info_enabled|Shows the owner text on the lock screen.|0 = off; 1 = on
lock_screen_show_notifications|Shows notifications on the lock screen.|0 = off; 1 = on
lock_screen_show_only_unseen_notifications|Hides notifications on the lock screen that you already saw.|0 = off; 1 = on
lock_screen_show_qr_code_scanner|Shows the QR scanner shortcut on the lock screen.|0 = off; 1 = on
lock_screen_show_silent_notifications|Shows silent notifications on the lock screen.|0 = off; 1 = on
lock_screen_sticky_appwidget|Deprecated. Index of the lockscreen appwidget to restore, -1 if none.
lock_to_app_exit_locked|Locks the screen when you leave screen pinning.|0 = off; 1 = on
lockscreen_allow_trivial_controls|Whether trivial home controls can be used without authentication|0 = off; 1 = on
lockscreen_show_controls|Shows device controls on the lock screen.|0 = off; 1 = on
lockscreen_show_wallet|Shows the wallet on the lock screen.|0 = off; 1 = on
lockscreen_use_double_line_clock|Whether to use the lockscreen double-line clock|0 = off; 1 = on
lockscreen_weather_enabled|Whether lockscreen weather is enabled.|0 = off; 1 = on
logging_id|Deprecated. The Logging ID (a unique 64-bit value) as a hex string. Used as a pseudonymous identifier for logging.
long_press_timeout|How long a touch must last to count as a long press.|milliseconds, e.g. 400 (short), 500 (default), 1500 (long)
low_power_manual_activation_count|The number of times (integer) the user has manually enabled battery saver.
low_power_warning_acknowledged|Whether the "first time battery saver warning" dialog needs to be shown (0: default) or not (1).|0 = off; 1 = on
managed_profile_contact_remote_search|Whether parent user can access remote contact in managed profile.|0 = off; 1 = on
managed_provisioning_dpc_downloaded|Indicates whether a DPC has been downloaded during provisioning.|0 = off; 1 = on
mandatory_biometrics|Requires biometrics for sensitive actions.|0 = off; 1 = on
mandatory_biometrics_requirements_satisfied|Whether or not requirements for mandatory biometrics is satisfied.|0 = off; 1 = on
manual_ringer_toggle_count|Number of times the user has manually clicked the ringer toggle
match_content_frame_rate|Changes the screen refresh rate to suit videos.|0 = never; 1 = only when it looks seamless; 2 = always
media_controls_lock_screen|Shows media controls on the lock screen.|0 = off; 1 = on
minimal_post_processing_allowed|Lets games and video turn off screen image processing.|0 = no; 1 = yes
mirror_built_in_display|Whether to mirror the built-in display on all connected displays.|0 = off; 1 = on
mock_location|Allows apps to fake the location (developer option, older setting).|0 = off; 1 = on
mount_play_not_snd|Whether or not alert sounds are played on StorageManagerService events. (0 = false, 1 = true)|0 = false; 1 = true
mount_ums_autostart|Whether or not UMS auto-starts on UMS host detection. (0 = false, 1 = true)|0 = false; 1 = true
mount_ums_notify_enabled|Whether or not a notification is displayed while UMS is enabled. (0 = false, 1 = true)|0 = false; 1 = true
mount_ums_prompt|Whether or not a notification is displayed on UMS host detection. (0 = false, 1 = true)|0 = false; 1 = true
multi_press_timeout|Longest gap between two taps to count as a double tap.|milliseconds, e.g. 300
nas_settings_updated|If nonzero, nas has not been updated to reflect new changes.
nav_bar_force_visible|Keeps the navigation bar visible, even in full-screen.|0 = off; 1 = on
nav_bar_kids_mode|Navigation bar for kids mode.|0 = off; 1 = on
navigation_mode|How you navigate.|0 = three buttons; 1 = two buttons; 2 = gestures
navigation_mode_restore|The value is from another(source) device's NAVIGATION_MODE during restore. It's supposed to be written only by SettingsHelper. This setting should not be added into backup array.|-1 = Can't get value from restore(default; 2 = fully gestural
nearby_fast_pair_settings_devices_component|Current provider of Fast Pair saved devices page. Default value in @string/config_defaultNearbyFastPairSettingsDevicesComponent. No VALIDATOR as this setting will not be backed up.
nearby_sharing_component|The app that provides sharing with nearby devices.|component name
nearby_sharing_slice_uri|Nearby Sharing Slice URI for the SliceProvider to read Nearby Sharing scan results and then draw the UI.
nfc_payment_default_component|Deprecated. The default NFC payment component
nfc_payment_foreground|Pays with the app on screen instead of the default payment app.|0 = no; 1 = yes
night_display_activated|Night Light is on now.|0 = off; 1 = on
night_display_auto_mode|When Night Light turns on by itself.|0 = never; 1 = on a custom schedule; 2 = from sunset to sunrise
night_display_color_temperature|Warmth of Night Light.|kelvin, about 2596 (very warm) to 4082 (mild)
night_display_custom_end_time|When Night Light turns off on a custom schedule.|milliseconds after midnight, e.g. 25200000 = 7:00
night_display_custom_start_time|When Night Light turns on on a custom schedule.|milliseconds after midnight, e.g. 79200000 = 22:00
night_display_last_activated_time|A String representing the LocalDateTime when Night display was last activated. Use to decide whether to apply the current activated state after a reboot or user change.|milliseconds
notification_badging|Notification dots on app icons.|0 = off; 1 = on
notification_bubbles|Chat bubbles for notifications.|0 = off; 1 = on
notification_dismiss_rtl|Swipe notifications right to left to dismiss them.|0 = no; 1 = yes
notification_history_enabled|Keeps a history of notifications for the last 24 hours.|0 = off; 1 = on
notified_non_accessibility_category_services|List of the notified non-accessibility category accessibility services.
num_rotation_suggestions_accepted|The number of accepted rotation suggestions. Used to determine if the user has been introduced to rotation suggestions.
odi_captions_enabled|Live Caption.|0 = off; 1 = on
odi_captions_volume_ui_enabled|Setting to indicate live caption button show or hide in the volume rocker.
on_device_inference_unbind_timeout_ms|Timeout to be used for unbinding to the configured remote OnDeviceSandboxedInferenceService if there are no requests in the queue. A value of -1 represents to never unbind.
on_device_intelligence_idle_timeout_ms|Timeout that represents maximum idle time before which a callback should be populated.
on_device_intelligence_unbind_timeout_ms|Timeout to be used for unbinding to the configured remote OnDeviceIntelligenceService if there are no requests in the queue. A value of -1 represents to never unbind.
one_handed_mode_activated|One-handed mode is active right now.|0 = no; 1 = yes
one_handed_mode_enabled|One-handed mode.|0 = off; 1 = on
one_handed_mode_timeout|How long one-handed mode stays on after the last touch.|seconds: 4, 8 or 12; 0 = never ends
one_handed_tutorial_show_count|Internal use, one handed mode tutorial showed times.
packages_to_clear_data_before_full_restore|List of packages, which data need to be unconditionally cleared before full restore.|text
parental_control_enabled|No longer supported.
parental_control_last_update|No longer supported.
parental_control_redirect_url|No longer supported.
payment_service_search_uri|This is the query URI for finding a NFC payment service to install.
people_strip|Shows a strip of people at the top of notifications.|0 = off; 1 = on
power_menu_locked_show_content|Shows cards and controls in the power menu while locked.|0 = hide; 1 = show
preferred_tty_mode|Preferred TTY (text telephone) mode.|0 = off; 1 = full; 2 = hearing carry over; 3 = voice carry over
print_service_search_uri|This is the query URI for finding a print service to install.
private_space_auto_lock|Store auto lock value for private space. The possible values are defined in PrivateSpaceAutoLockOption.
qs_auto_tiles|Quick Settings tiles that were added automatically.|list separated by commas
qs_media_recommend|Controls whether contextual suggestions can be shown in the media controls.|0 = off; 1 = on
qs_media_resumption|Shows resumable media in Quick Settings.|0 = off; 1 = on
reduce_bright_colors_activated|Extra dim (Reduce Bright Colors) is on.|0 = off; 1 = on
reduce_bright_colors_level|How strong Extra dim is.|percent, 0 to 100
reduce_bright_colors_persist_across_reboots|Setting that specifies whether Reduce Bright Colors should persist across reboots.|0 = off; 1 = on
release_compress_blocks_on_install|Whether or not compress blocks should be released on install.|0 = off; 1 = on
reminder_exp_learning_event_count|How many times the Assistant has been triggered using the touch gesture.
reminder_exp_learning_time_elapsed|How long Assistant handles have enabled in milliseconds.|milliseconds
rtt_calling_mode|Real-time text calls.|0 = off; 1 = on
screen_off_udfps_enabled|Whether or not the UDFPS device is enabling the screen off unlock settings.|0 = off; 1 = on
screen_resolution_mode|Screen resolution you chose.|0 = unset; 1 = high resolution; 2 = full resolution
screensaver_activate_on_dock|Starts the screen saver while docked.|0 = off; 1 = on
screensaver_activate_on_sleep|Starts the screen saver while charging.|0 = off; 1 = on
screensaver_complications_enabled|Whether complications are enabled to be shown over the screensaver by the user.|0 = off; 1 = on
screensaver_components|The screen saver in use.|list of component names separated by commas
screensaver_default_component|If screensavers are enabled, the default screensaver component.
screensaver_enabled|Screen saver (daydream).|0 = off; 1 = on
screensaver_home_controls_enabled|Whether home controls are enabled to be shown over the screensaver by the user.|0 = off; 1 = on
search_all_entrypoints_enabled|Whether all entrypoints (e.g. long-press home, long-press nav handle) can trigger contextual search.|0 = off; 1 = on
search_global_search_activity|The app used for system-wide search.|component name
search_max_results_per_source|The number of suggestions GlobalSearch will ask each non-web search source for.
search_max_results_to_display|The maximum number of suggestions returned by GlobalSearch.
search_max_shortcuts_returned|The maximum number of shortcuts shown by GlobalSearch.
search_max_source_event_age_millis|The maximum age of log data used for source ranking in GlobalSearch.
search_max_stat_age_millis|The maximum age of log data used for shortcuts in GlobalSearch.
search_min_clicks_for_source_ranking|The minimum number of clicks needed to rank a source in GlobalSearch.
search_min_impressions_for_source_ranking|The minimum number of impressions needed to rank a source in GlobalSearch.
search_num_promoted_sources|The number of promoted sources in GlobalSearch.
search_per_source_concurrent_query_limit|The maximum number of concurrent suggestion queries to each source.
search_prefill_millis|The maximum number of milliseconds that GlobalSearch shows the previous results after receiving a new query.
search_promoted_source_deadline_millis|The number of milliseconds that GlobalSearch will wait for suggestions from promoted sources before continuing with all other sources.
search_query_thread_core_pool_size|The size of the core thread pool for suggestion queries in GlobalSearch.
search_query_thread_max_pool_size|The maximum size of the thread pool for suggestion queries in GlobalSearch.
search_shortcut_refresh_core_pool_size|The size of the core thread pool for shortcut refreshing in GlobalSearch.
search_shortcut_refresh_max_pool_size|The maximum size of the thread pool for shortcut refreshing in GlobalSearch.
search_source_timeout_millis|The number of milliseconds before GlobalSearch aborts search suggesiton queries.
search_thread_keepalive_seconds|The maximun time that excess threads in the GlobalSeach thread pools will wait before terminating.
search_web_results_override_limit|The number of suggestions the GlobalSearch will ask the web search source for.
secure_frp_mode|Deprecated. Indicates whether the device is under restricted secure FRP mode. Secure FRP mode is enabled when the device is under FRP. On solving of FRP challenge, device is removed from this mode.|0 = off; 1 = on
selected_input_method_subtype|The keyboard language in use.|whole number (a hash); -1 = automatic
selected_spell_checker|The spell checker in use.|component name
selected_spell_checker_subtype|hashCode() of the selected subtype of the selected spell checker service which is one of the services managed by the text service manager.
sfps_performant_auth_enabled_v2|Whether or not a SFPS device is enabling the performant auth setting. The "_V2" suffix was added to re-introduce the default behavior for users. See b/265264294 fore more details.|0 = off; 1 = on
show_first_crash_dialog_dev_option|Shows a dialog when an app crashes (developer option).|0 = off; 1 = on
show_ime_with_hard_keyboard|Shows the on-screen keyboard while a physical keyboard is connected.|0 = off; 1 = on
show_media_when_bypassing|Whether or not media is shown automatically when bypassing as a heads up.|0 = off; 1 = on
show_note_about_notification_hiding|Set by the system to track if the user needs to see the call to action for the lockscreen notification policy.
show_notification_snooze|Shows snooze options on notifications.|0 = off; 1 = on
show_qr_code_scanner_setting|Whether or not to enable qr code code scanner setting to enable/disable lockscreen entry point. Any value apart from null means setting needs to be enabled|0 = off; 1 = on
show_rotation_suggestions|The small button that offers to rotate the screen when auto-rotate is off.|0 = off; 1 = on
silence_alarms_gesture_count|Count of successful silence alarms gestures.
silence_alarms_touch_count|Count of non-gesture interaction.
silence_call_gesture_count|Count of successful silence call gestures.
silence_call_touch_count|Count of non-gesture interaction.
silence_gesture|Flip or tap gesture that silences alarms and calls.|0 = off; 1 = on
silence_timer_gesture_count|Count of successful silence timer gestures.
silence_timer_touch_count|Count of non-gesture interaction.
skip_accessibility_shortcut_dialog_timeout_restriction|Setting specifying if the timeout restriction getAccessibilityShortcutKeyTimeout() of the accessibility shortcut dialog is skipped.
skip_first_use_hints|Asks apps to skip their welcome hints.|0 = show hints; 1 = skip hints
skip_gesture|Gesture that skips media.
skip_gesture_count|Count of successful gestures.
skip_gesture_direction|Direction to advance media for skip gesture
skip_touch_count|Count of non-gesture interaction.
sleep_timeout|How long the phone is idle before it goes to sleep fully.|milliseconds; -1 = never
sms_default_application|The app that handles text messages.|package name
spatial_audio_enabled|Spatial audio.|0 = off; 1 = on
speak_password|Reads passwords aloud while accessibility is on.|0 = off; 1 = on
spell_checker_enabled|Spell checker.|0 = off; 1 = on
status_bar_show_vibrate_icon|Shows the vibrate icon in the status bar.|0 = off; 1 = on
stylus_buttons_enabled|Lets the stylus buttons do things.|0 = off; 1 = on
stylus_handwriting_enabled|Handwriting with a stylus in text fields.|0 = off; 1 = on
stylus_pointer_icon_enabled|Toggle for enabling stylus pointer icon. Pointer icons for styluses will only be be shown when this is enabled.
suggested.completed_category.|The prefix for a category name that indicates whether a suggested action from that category was marked as completed.|0 = off; 1 = on
suppress_auto_battery_saver_suggestion|0 (default) Auto battery saver suggestion has not been suppressed. 1) it has been suppressed.
suppress_doze|Prevents the phone from entering ambient display (doze).|0 = off; 1 = on
swipe_bottom_to_notification_enabled|Swipe down on the bottom edge to open notifications.|0 = off; 1 = on
sync_parent_sounds|Defines whether managed profile ringtones should be synced from it's parent profile 0 = ringtones are not synced 1 = ringtones are synced from the profile's parent (default) This value is only used for managed profiles.
system_navigation_keys_enabled|Whether SystemUI navigation keys is enabled.|0 = off; 1 = on
sysui_nav_bar|The buttons of the navigation bar and their order.|text, e.g. space;back,home;recent
sysui_qs_tiles|Quick Settings tiles and their order.|list of tile names separated by commas, e.g. wifi,bt,dnd,flashlight
tap_gesture|Tap to check the phone.|0 = off; 1 = on
taps_app_to_exit|For user taps app to exit One-Handed Mode.
theme_customization_overlay_packages|Map of android.theme.customization.* categories to the enabled overlay package for that category, formatted as a serialized JSONObject.
timeout_to_dock_user|The duration of timeout, in milliseconds, to switch from a non-Dock User to the Dock User when the device is docked.|milliseconds
touch_exploration_enabled|TalkBack touch exploration.|0 = off; 1 = on|lock
touch_exploration_granted_accessibility_services|Services allowed to use touch exploration.|list of component names separated by :
trackpad_gesture_back_enabled|Indicates whether the trackpad back gesture is enabled.|0 = off; 1 = on
trackpad_gesture_home_enabled|Indicates whether the trackpad home gesture is enabled.|0 = off; 1 = on
trackpad_gesture_notification_enabled|Indicates whether the trackpad notification gesture is enabled.|0 = off; 1 = on
trackpad_gesture_overview_enabled|Indicates whether the trackpad overview gesture is enabled.|0 = off; 1 = on
trackpad_gesture_quick_switch_enabled|Indicates whether the trackpad quick switch gesture is enabled.|0 = off; 1 = on
trust_agents_initialized|Set to 1 by the system after trust agents have been initialized.
tts_default_country|Deprecated. Default text-to-speech country.
tts_default_lang|Deprecated. Default text-to-speech language.
tts_default_locale|Stores the default tts locales on a per engine basis. Stored as a comma seperated list of values, each value being of the form engine_name:locale for example, ttsengine:esp-ESP.
tts_default_pitch|Pitch of the text-to-speech voice.|percent; 100 = normal
tts_default_rate|Speed of the text-to-speech voice.|percent; 100 = normal
tts_default_synth|The text-to-speech engine in use.|package name
tts_default_variant|Deprecated. Default text-to-speech locale variant.
tts_enabled_plugins|Space delimited list of plugin packages that are enabled.
tts_use_defaults|Deprecated. Setting to always use the default text-to-speech settings regardless of the application settings. 1 = override application settings, 0 = use application settings (if specified).|1 = override application settings; 0 = use application settings (if specified
tty_mode_enabled|Whether the TTY mode mode is enabled. 0 = disabled 1 = enabled|0 = disabled; 1 = enabled
tv_app_uses_non_system_inputs|Whether TV app uses non-system inputs. The value is boolean (1 or 0), where 1 means non-system TV inputs are allowed, and 0 means non-system TV inputs are not allowed.|0 = off; 1 = on
tv_input_custom_labels|List of custom TV input labels. This is a string containing pairs. TV input id and custom name are encoded by encode(String) and separated by ','. Each pair is separated by ':'.
tv_input_hidden_inputs|List of TV inputs that are currently hidden. This is a string containing the IDs of all hidden TV inputs. Each ID is encoded by encode(String) and separated by ':'.
tv_user_setup_complete|Whether the current user has been set up via setup wizard (0 = false, 1 = true) This value differs from USER_SETUP_COMPLETE in that it can be reset back to 0 in case SetupWizard has been re-enabled on TV devices.|0 = off; 1 = on
ui_night_mode|Dark theme.|0 = automatic (follows Battery Saver or the schedule); 1 = off; 2 = on; 3 = custom schedule
ui_night_mode_custom_type|Which kind of custom schedule the dark theme follows.|0 = unset; 1 = a schedule; 2 = bedtime mode
ui_night_mode_last_computed|The last computed night mode bool the last time the phone was on
ui_night_mode_override_off|The current night mode that has been overridden to turn off by the system. Owned and controlled by UiModeManagerService. Constants are as per UiModeManager.
ui_night_mode_override_on|The current night mode that has been overridden to turn on by the system. Owned and controlled by UiModeManagerService. Constants are as per UiModeManager.
ui_translation_enabled|Toggle to enable/disable for the apps to use the Ui translation for Views. The value indicates whether the Ui translation is enabled by the user.|whole number
unknown_sources_default_reversed|Reverses the default for installing apps from unknown sources.|0 = no; 1 = yes
unsafe_volume_music_active_ms|Persisted playback time after a user confirmation of an unsafe volume level.
usb_audio_automatic_routing_disabled|Stops sound moving to USB audio devices by itself.|0 = it moves; 1 = it stays
user_setup_complete|The first-time setup of this user is finished: leave this at 1.|1 = finished; 0 = not finished|break
user_setup_personalization_state|Defines the user's current state of device personalization. The possible states are defined in UserSetupPersonalization.
v_to_u_restore_allowlist|List of system components that support restore in a V-> U OS downgrade but do not have RestoreAnyVersion set to true. Value set before system restore.|a list, items separated by , or :
v_to_u_restore_denylist|List of system components that have RestoreAnyVersion set to true but do not support restore in a V-> U OS downgrade. Value set before system restore.|a list, items separated by , or :
visual_query_accessibility_detection_enabled|Whether or not the accessibility data streaming is enbled for the setAccessibilityDetectionData.|0 = off; 1 = on
voice_interaction_service|The voice assistant service in use.|component name
voice_recognition_service|The speech recognition service in use.|component name
volume_dialog_dismiss_timeout|How long the volume panel stays on screen.|milliseconds
volume_hush_gesture|What pressing power and volume up does.|0 = nothing; 1 = vibrate; 2 = mute
vr_display_mode|Behavior of the display while in VR mode. One of VR_DISPLAY_MODE_LOW_PERSISTENCE or VR_DISPLAY_MODE_OFF.
wake_gesture_enabled|Wakes the screen when you lift the phone.|0 = off; 1 = on
wear_talkback_enabled|Is talkback service enabled or not. 0 == no, 1 == yes|0 = no; 1 = yes
wifi_watchdog_acceptable_packet_loss_percentage|Deprecated. The acceptable packet loss percentage (range 0 - 100) before trying another AP on the same network.
wifi_watchdog_ap_count|Deprecated. The number of access points required for a network in order for the watchdog to monitor it.
wifi_watchdog_background_check_delay_ms|Deprecated. The delay between background checks.
wifi_watchdog_background_check_enabled|Deprecated. Whether the Wi-Fi watchdog is enabled for background checking even after it thinks the user has connected to a good access point.|0 = off; 1 = on
wifi_watchdog_background_check_timeout_ms|Deprecated. The timeout for a background ping
wifi_watchdog_initial_ignored_ping_count|Deprecated. The number of initial pings to perform that *may* be ignored if they fail. Again, if these fail, they will *not* be used in packet loss calculation.
wifi_watchdog_max_ap_checks|Deprecated. The maximum number of access points (per network) to attempt to test. If this number is reached, the watchdog will no longer monitor the initial connection state for the network.
wifi_watchdog_ping_count|Deprecated. The number of pings to test if an access point is a good connection.
wifi_watchdog_ping_delay_ms|Deprecated. The delay between pings.
wifi_watchdog_ping_timeout_ms|Deprecated. The timeout per ping.
wifi_watchdog_watch_list|Deprecated. A comma-separated list of SSIDs for which the Wi-Fi watchdog should be enabled.|a list, items separated by , or :
zen_duration|How long Do Not Disturb stays on when you switch it on from Quick Settings.|minutes; 0 = until you turn it off; -1 = ask each time`,
system: `accelerometer_rotation|Auto-rotate: turns the screen when you turn the phone.|0 = off (screen keeps user_rotation); 1 = on
adaptive_sleep|Deprecated. Control whether to enable adaptive sleep mode.
advanced_settings|Advanced settings mode.|0 = no; 1 = yes
alarm_alert|The default alarm sound.|address of a sound file (URI)
alarm_vibration_intensity|How strong alarm vibration is.|0 = off; 1 = low; 2 = medium; 3 = high
apply_ramping_ringer|Ringtone starts quiet and gets louder.|0 = off; 1 = on
auto_caps|Capitalises the first letter of a sentence in text fields.|0 = off; 1 = on
auto_launch_media_controls|Controls whether auto-launching media controls is enabled on wearable devices. The valid values for this key are: 0 (disabled) or 1 (enabled).|0 = off; 1 = on
auto_punctuate|Two spaces become a full stop and a space.|0 = off; 1 = on
auto_replace|Corrects typing mistakes automatically.|0 = off; 1 = on
bluetooth_discoverability|Whether other devices may find and connect to this phone by Bluetooth.|0 = neither; 1 = connectable but not visible; 2 = visible and connectable
bluetooth_discoverability_timeout|How long Bluetooth stays visible to other devices.|seconds; 0 = never ends
camera_flash_notification|Flashes the camera light for notifications.|0 = off; 1 = on
cw_bt_settings_pref|Controls whether bluetooth is on or off on wearable devices. The valid values for this key are: 0 (disabled) or 1 (enabled).|0 = off; 1 = on
debug.enable_enhanced_calling|When 1, Telecom enhanced call blocking functionality is enabled. When 0, enhanced call blocking functionality is disabled.
device_font_scale|Default scaling factor for fonts for the specific device, float. The value is read from the def_device_font_scale configuration property.|decimal number
dim_screen|Deprecated. Whether or not to dim the screen. 0=no 1=yes|0 = off; 1 = on
display_color_mode|The display color mode.
display_color_mode_vendor_hint|Hint to decide whether restored vendor color modes are compatible with the new device. If unset or a match is not made, only the standard color modes will be restored.
dtmf_tone|Keypad tones while dialling.|0 = off; 1 = on
dtmf_tone_type|Length of keypad tones (CDMA).|0 = normal; 1 = long
egg_mode|I am the lolrus. Nonzero values indicate that the user has a bukkit. Backward-compatible with PrefGetPreference(prefAllowEasterEggs).
end_button_behavior|What the end-call (power) button does when you are not in a call.|0 = nothing; 1 = go to the home screen; 2 = turn the screen off and lock it; 3 = go to the home screen, or turn the screen off if you are already there
fold_lock_behavior_setting|Control lock behavior on fold If this isn't set, the system falls back to a device specific default.
font_scale|Text size.|decimal; 1.0 = normal; 0.85 = small; 1.15 = large; 1.3 = largest
haptic_feedback_enabled|Vibrates when you touch buttons and keys.|0 = off; 1 = on
haptic_feedback_intensity|How strong touch vibration is.|0 = off; 1 = low; 2 = medium; 3 = high
hardware_haptic_feedback_intensity|The intensity of haptic feedback vibrations for interaction with hardware components from the device, like buttons and sensors, if configurable.|0 = Vibration is disabled; 1 = Weak vibrations; 2 = Medium vibrations; 3 = Strong vibrations
hearing_aid|Hearing aid compatibility.|0 = off; 1 = on
hide_rotation_lock_toggle_for_accessibility|Hides the rotation lock button in Quick Settings.|0 = shown; 1 = hidden
input_gain_index_settings|The mapping of input device to its input gain index.
keyboard_vibration_enabled|Vibrates when you press keyboard keys.|0 = off; 1 = on
locale_preferences|The information of locale preference. This records user's preference to avoid unsynchronized and existing locale preference in Category).
lock_to_app_enabled|Screen pinning: pins one app to the screen.|0 = off; 1 = on
lockscreen.disabled|Whether the lockscreen should be completely disabled.|0 = off; 1 = on
lockscreen_sounds_enabled|Plays sounds when the screen locks and unlocks.|0 = off; 1 = on
master_balance|Stereo balance of all sound.|decimal, -1 = all left; 0 = centred; 1 = all right
master_mono|Plays all sound as mono.|0 = stereo; 1 = mono
media_button_receiver|Persistent store for the system default media button event receiver.
media_vibration_intensity|How strong vibration in media and games is.|0 = off; 1 = low; 2 = medium; 3 = high
min_refresh_rate|The lowest screen refresh rate allowed.|Hz, e.g. 60; 0 = no minimum
mode_ringer_streams_affected|The sounds that are muted in silent or vibrate mode.|whole number, one bit per sound type
mouse_reverse_vertical_scrolling|Whether to enable reversed vertical scrolling for connected mice. When enabled, scrolling down on the mouse wheel will move the screen up and vice versa.|0 = off; 1 = on
mouse_swap_primary_button|Whether to enable swapping the primary button for connected mice. When enabled, right clicking will be the primary button and left clicking will be the secondary button (e.g. show menu).|0 = off; 1 = on
multi_audio_focus_enabled|Lets several apps play sound at once.|0 = off; 1 = on
mute_streams_affected|The sounds that the mute button silences.|whole number, one bit per sound type
next_alarm_formatted|Deprecated. A formatted string of the next alarm that is set, or the empty string if there is no alarm set.
notification_cooldown_all|When enabled, notification cooldown will apply to all notifications. Otherwise cooldown will only apply to conversations.
notification_cooldown_enabled|When enabled, notifications attention effects: sound, vibration, flashing will have a cooldown timer. The value 1 - enable, 0 - disable|1 = enable; 0 = disable
notification_cooldown_vibrate_unlocked|When enabled, notification attention effects will be restricted to vibration only as long as the screen is unlocked. The value 1 - enable, 0 - disable|1 = enable; 0 = disable
notification_light_pulse|The notification light pulses while a notification waits.|0 = off; 1 = on
notification_sound|The default notification sound.|address of a sound file (URI)
notification_vibration_intensity|How strong notification vibration is.|0 = off; 1 = low; 2 = medium; 3 = high
notifications_use_ring_volume|Notifications use the ringer volume.|0 = separate volume; 1 = same as the ringer
peak_refresh_rate|The highest screen refresh rate allowed.|Hz, e.g. 120; large value or empty = as high as possible
pointer_fill_style|Pointer fill style, specified by PointerIconVectorStyleFill constants.
pointer_location|Shows the touch pointer position on screen (developer option).|0 = off; 1 = on
pointer_scale|Pointer scale setting. This float value represents the scale by which the size of the pointer increases.|decimal number
pointer_speed|Speed of the mouse or touch pointer.|whole number, -7 (slowest) to 7 (fastest); 0 = normal
pointer_stroke_style|Pointer stroke style, specified by PointerIconVectorStyleStroke constants.
power_sounds_enabled|Plays sounds for low battery alerts.|0 = off; 1 = on
preferred_region|User can change the region from region settings. This records user's preferred region. E.g. : if user's locale is en-US, this will record US
ring_vibration_intensity|How strong ringtone vibration is.|0 = off; 1 = low; 2 = medium; 3 = high
ringtone|The default ringtone.|address of a sound file (URI)
screen_auto_brightness_adj|Adjustment to automatic brightness.|decimal, -1 (darker) to 1 (brighter); 0 = none
screen_brightness|Screen brightness.|0 or 1 (dimmest) to 255 (brightest); some phones go higher
screen_brightness_for_als|The screen backlight brightness for automatic mode. Value should be one of: SCREEN_BRIGHTNESS_AUTOMATIC_BRIGHT SCREEN_BRIGHTNESS_AUTOMATIC_NORMAL SCREEN_BRIGHTNESS_AUTOMATIC_DIM
screen_brightness_mode|Adaptive brightness.|0 = manual; 1 = automatic
screen_flash_notification|Flashes the screen for notifications.|0 = off; 1 = on
screen_flash_notification_color_global|Integer property that specifes the color for screen flash notification as a packed 32-bit color.
screen_off_timeout|How long the screen stays on without touch before it turns off.|milliseconds, e.g. 15000, 30000, 60000, 600000
setup_wizard_has_run|The first-time setup has run.|0 = not yet; 1 = done|break
show_key_presses|Shows key presses on screen (developer option).|0 = off; 1 = on
show_password|Shows typed password characters for a moment.|0 = off; 1 = on
show_rotary_input|Show rotary input dispatched to focused windows on the screen. 0 = no 1 = yes|0 = no; 1 = yes
show_touches|Shows a dot where you touch the screen (developer option).|0 = off; 1 = on
sip_call_options|When to use internet (SIP) calls.|SIP_ALWAYS = always; SIP_ADDRESS_ONLY = only for SIP addresses
sip_receive_calls|Receives internet (SIP) calls.|0 = no; 1 = yes
sound_effects_enabled|Plays sounds when you touch the screen.|0 = off; 1 = on
status_bar_show_battery_percent|Shows the battery percentage in the status bar.|0 = hide it; 1 = show it
system_locales|The languages of the phone, in order.|list of locale tags separated by commas, e.g. en-US,de-DE
time_12_24|12 or 24 hour clock.|12 = 12 hour; 24 = 24 hour; unset = by the language
touchpad_natural_scrolling|Natural scrolling on a touchpad.|0 = off; 1 = on
touchpad_pointer_speed|Touchpad pointer speed.|whole number, -7 (slowest) to 7 (fastest)
touchpad_right_click_zone|Whether to enable a right-click zone on touchpads. When set to 1, pressing to click in a section on the right-hand side of the touchpad will result in a context click (a.k.a. right click).|0 = off; 1 = on
touchpad_system_gestures|Whether to enable system gestures (three- and four-finger swipes) on touchpads.|0 = off; 1 = on
touchpad_tap_dragging|Whether to enable tap dragging on touchpads.|0 = off; 1 = on
touchpad_tap_to_click|Tap to click on a touchpad.|0 = off; 1 = on
touchpad_three_finger_tap_customization|Whether to enable three finger tap customization on touchpads.|0 = off; 1 = on
touchpad_visualizer|Show touchpad input visualization on screen. 0 = no 1 = yes|0 = no; 1 = yes
tty_mode|Teletypewriter (TTY) mode for calls.|0 = off; 1 = full; 2 = hearing carry over; 3 = voice carry over
unread_notification_dot_indicator|Shows a dot for unread notifications (watches).|0 = off; 1 = on
user_rotation|Screen orientation used when auto-rotate is off.|0 = portrait; 1 = landscape (left); 2 = upside down; 3 = landscape (right)
vibrate_in_silent|Allows vibration while the phone is in silent mode.|0 = no; 1 = yes
vibrate_input_devices|Sends vibration to attached game controllers too.|0 = no; 1 = yes
vibrate_on|Whether vibration is on for events. Android keeps this by itself.|whole number
vibrate_when_ringing|Vibrates as well as ringing for calls.|0 = off; 1 = on
volume_a11y|Accessibility volume (index).|whole number, from 0 to the maximum of that sound
volume_alarm|Alarm volume.|whole number, 0 (silent) up to the maximum (often 7)
volume_assistant|Assistant volume.|whole number, 0 up to the maximum
volume_bluetooth_sco|Bluetooth call volume.|whole number, 0 up to the maximum
volume_master|Master volume.|decimal, 0 to 1
volume_music|Media volume.|whole number, 0 up to the maximum (often 15)
volume_notification|Notification volume.|whole number, 0 up to the maximum
volume_ring|Ringer volume.|whole number, 0 (silent) up to the maximum
volume_system|System sounds volume.|whole number, 0 up to the maximum
volume_voice|Call volume.|whole number, 0 up to the maximum
wallpaper_activity|Deprecated. Name of activity to use for wallpaper on the home screen.
wear_accessibility_gesture_enabled|If the triple press gesture for toggling accessibility is enabled. Set to 1 for true and 0 for false. This setting is used only internally.|0 = off; 1 = on
wear_accessibility_gesture_enabled_during_oobe|If the triple press gesture for toggling accessibility is enabled during OOBE. Set to 1 for true and 0 for false. This setting is used only internally.|0 = off; 1 = on
wear_tts_prewarm_enabled|If the text-to-speech pre-warm is enabled. Set to 1 for true and 0 for false. This setting is used only internally.|0 = off; 1 = on
when_to_make_wifi_calls|When to place calls over Wi-Fi.|0 = always use Wi-Fi calling; 1 = ask every time; 2 = never
wifi_static_dns1|Deprecated. If using static IP, the primary DNS's IP address. Example: "192.168.1.1"
wifi_static_dns2|Deprecated. If using static IP, the secondary DNS's IP address. Example: "192.168.1.2"
wifi_static_gateway|Deprecated. If using static IP, the gateway's IP address. Example: "192.168.1.1"
wifi_static_ip|Deprecated. The static IP address. Example: "192.168.1.51"
wifi_static_netmask|Deprecated. If using static IP, the net mask. Example: "255.255.255.0"
wifi_use_static_ip|Uses a fixed IP address for Wi-Fi (older setting).|0 = off; 1 = on
window_orientation_listener_log|Log raw orientation data from WindowOrientationListener for use with the orientationplot.py tool. 0 = no 1 = yes|0 = no; 1 = yes`
};
if (window.onHsInfoLoaded) window.onHsInfoLoaded();

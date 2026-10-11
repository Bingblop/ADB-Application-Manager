// Shared by the Settings-tab tests: a mock AndroidBridge with a fake settings database behind settingsList / settingsOp.
module.exports.initScript = function () {
  // runs in the page before anything else
  const g = {
    adb_enabled: '1', adb_wifi_enabled: '0', airplane_mode_on: '0', wifi_on: '1', bluetooth_on: '1', mobile_data: '1', data_roaming: '0',
    auto_time: '1', auto_time_zone: '1', stay_on_while_plugged_in: '7', private_dns_mode: 'opportunistic', private_dns_specifier: '',
    development_settings_enabled: '1', device_name: "Sam's phone", zen_mode: '0', low_power: '0', low_power_trigger_level: '15',
    window_animation_scale: '1.0', transition_animation_scale: '1.0', animator_duration_scale: '1.0', always_finish_activities: '0',
    hidden_api_policy: 'null', cached_apps_freezer: 'enabled', device_provisioned: '1', boot_count: '42', heads_up_notifications_enabled: '1',
    some_flag_true: 'true', some_flag_False: 'False', some_flag_UPPER: 'ON', some_yes: 'yes', empty_one: '', multi_line: 'first line\nsecond line',
    long_value: 'x'.repeat(900), captive_portal_http_url: 'http://connectivitycheck.gstatic.com/generate_204',
    'weird.key:with-chars/and+more': 'v'
  };
  for (let i = 0; i < 420; i++) g['sample_global_' + String(i).padStart(3, '0')] = String(i % 5 === 0 ? i % 2 : 'value ' + i);
  const sec = {
    accessibility_enabled: '0', enabled_accessibility_services: 'com.example/.Svc:com.other/.Svc2', default_input_method: 'com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME',
    android_id: '0123456789abcdef', ui_night_mode: '2', location_mode: '3', navigation_mode: '2', doze_enabled: '1', user_setup_complete: '1',
    long_press_timeout: '400', screensaver_enabled: '0', lock_screen_allow_private_notifications: '1', enabled_notification_listeners: 'com.a/.L'
  };
  for (let i = 0; i < 180; i++) sec['sec_sample_' + String(i).padStart(3, '0')] = String(i % 3 === 0 ? 'true' : 'abc' + i);
  const sys = {
    screen_brightness: '128', screen_brightness_mode: '1', screen_off_timeout: '60000', accelerometer_rotation: '1', user_rotation: '0', font_scale: '1.0',
    haptic_feedback_enabled: '1', sound_effects_enabled: '0', time_12_24: '24', volume_music_speaker: '7', volume_ring_speaker: '5', volume_alarm_speaker: '6', volume_bluetooth_sco_bt_sco: '3',
    peak_refresh_rate: '120.0', show_touches: '0', pointer_location: '0', ringtone: 'content://settings/system/ringtone', notification_sound: 'content://media/internal/audio/media/12'
  };
  for (let i = 0; i < 70; i++) sys['sys_sample_' + String(i).padStart(2, '0')] = String(i % 2);
  window.__db = { global: g, secure: sec, system: sys };
  window.__calls = { list: [], op: [] };
  window.__mode = { priv: true };
  window.__deny = {};            // 'ns/key' -> error text: the put is refused
  window.__revert = {};          // 'ns/key' -> value Android quietly puts back
  window.__delay = 20;
  window.__noAnswer = false;
  const base = {
    vibrate() {}, loadPreferences() { return '{}'; }, savePreferences() {}, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, loadPackages() { return '[]'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
    getWorkingMode() {
      const p = window.__mode.priv;
      return JSON.stringify({ activeMode: p ? 'adb_tcp' : 'unprivileged', modeAvailable: p, isPrivileged: p, configuredMode: 'auto', adbTcp: { connected: p, port: 5555 }, adbWireless: { connected: false, port: 0 }, shizuku: { installed: false, running: false, authorized: false }, rootAvailable: false });
    },
    hasAllFilesAccess() { return true; },
    __kv: (window.__kvInit || {}), loadSetting(k) { return this.__kv[k] || ''; }, saveSetting(k, v) { this.__kv[k] = v; },
    copyToClipboard(t) { window.__copied = t; }, shareText(subject, text) { window.__shared = { subject, text }; },
    openUrl() {},
    settingsList(req, ns) {
      window.__calls.list.push(ns);
      if (window.__noAnswer) return 'started';
      setTimeout(() => {
        const t = window.__db[ns];
        window.onSettingsList(JSON.stringify({ req, ns, ok: true, entries: Object.keys(t).map(k => [k, t[k]]), mode: 'adb_tcp', ms: 12 }));
      }, window.__delay);
      return 'started';
    },
    settingsOp(req, op, ns, key, value) {
      window.__calls.op.push({ op, ns, key, value });
      if (window.__unknown && window.__unknown[ns + '/' + key]) {          // the link drops: the change is applied (or not) but no read-back comes
        const u = window.__unknown[ns + '/' + key]; delete window.__unknown[ns + '/' + key];
        setTimeout(() => { if (u.apply && op === 'put') window.__db[ns][key] = value; window.onSettingsOp(JSON.stringify({ req, op, ns, key, ok: false, unknown: true, error: 'No answer from the device', mode: 'adb_tcp' })); }, window.__delay);
        return 'started';
      }
      if (!window.__db[ns]) return 'error: Unknown settings table';
      if (window.__noAnswer) return 'started';
      setTimeout(() => {
        const t = window.__db[ns], id = ns + '/' + key;
        let r = { req, op, ns, key, ok: true, mode: 'adb_tcp' };
        if (op === 'put') {
          r.requested = value;
          if (window.__deny[id]) { r.ok = false; r.value = t[key] === undefined ? 'null' : t[key]; r.error = window.__deny[id]; r.answer = 'Exception occurred while executing \'put\':\n' + window.__deny[id]; r.advice = 'Android refused the change. Some phones (Xiaomi, Redmi, POCO) only allow it once "USB debugging (Security settings)" is on in Developer options.'; }
          else if (window.__revert[id] !== undefined) { r.ok = false; r.value = window.__revert[id]; r.error = 'Android did not keep the new value'; t[key] = window.__revert[id]; }
          else { t[key] = value; r.value = value; }
        } else if (op === 'delete') {
          if (window.__deny[id]) { r.ok = false; r.value = t[key]; r.error = window.__deny[id]; }
          else { r.deleted = key in t ? 1 : 0; delete t[key]; r.value = 'null'; }
        } else { r.value = key in t ? t[key] : 'null'; }
        window.onSettingsOp(JSON.stringify(r));
      }, window.__delay);
      return 'started';
    }
  };
  window.AndroidBridge = base;
};

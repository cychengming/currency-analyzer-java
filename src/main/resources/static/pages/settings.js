// Settings Page Module

const SETTINGS_DEFAULTS = Object.freeze({
	trend_threshold: 2,
	detection_period: 30,
	check_interval: 900,
	alert_email: '',
	enable_alerts: true,
	enable_sound: true,
});

let _settingsBaseline = { ...SETTINGS_DEFAULTS };

function _settingsFieldMap() {
	return {
		trend_threshold: document.getElementById('trendThreshold'),
		detection_period: document.getElementById('detectionPeriod'),
		check_interval: document.getElementById('checkInterval'),
		alert_email: document.getElementById('alertEmail'),
		enable_alerts: document.getElementById('enableAlerts'),
		enable_sound: document.getElementById('enableSound'),
	};
}

function _readSettingsForm() {
	const fields = _settingsFieldMap();
	return {
		trend_threshold: Number(fields.trend_threshold?.value || SETTINGS_DEFAULTS.trend_threshold),
		detection_period: Number(fields.detection_period?.value || SETTINGS_DEFAULTS.detection_period),
		check_interval: Number(fields.check_interval?.value || SETTINGS_DEFAULTS.check_interval),
		alert_email: String(fields.alert_email?.value || '').trim(),
		enable_alerts: !!fields.enable_alerts?.checked,
		enable_sound: !!fields.enable_sound?.checked,
	};
}

function _applySettingsForm(values) {
	const fields = _settingsFieldMap();
	if (fields.trend_threshold) fields.trend_threshold.value = String(values.trend_threshold);
	if (fields.detection_period) fields.detection_period.value = String(values.detection_period);
	if (fields.check_interval) fields.check_interval.value = String(values.check_interval);
	if (fields.alert_email) fields.alert_email.value = String(values.alert_email || '');
	if (fields.enable_alerts) fields.enable_alerts.checked = !!values.enable_alerts;
	if (fields.enable_sound) fields.enable_sound.checked = !!values.enable_sound;
}

function _settingsDirty(current) {
	return JSON.stringify(current) !== JSON.stringify(_settingsBaseline);
}

function _describeSettings(current) {
	const cadenceMap = {
		300: 'fast monitoring',
		900: 'balanced monitoring',
		1800: 'reduced monitoring',
		3600: 'hourly monitoring',
	};

	const parts = [
		`Threshold ${Number(current.trend_threshold).toFixed(1)}%`,
		`window ${current.detection_period}d`,
		cadenceMap[current.check_interval] || `interval ${current.check_interval}s`,
		current.enable_alerts ? 'alerts enabled' : 'alerts paused',
		current.enable_sound ? 'sound on' : 'sound off',
	];

	if (current.alert_email) {
		parts.push(`email ${current.alert_email}`);
	} else {
		parts.push('no email configured');
	}

	return parts.join(' · ');
}

function updateSettingsSummary() {
	const summaryEl = document.getElementById('settingsSummary');
	if (!summaryEl) return;

	const current = _readSettingsForm();
	summaryEl.textContent = _describeSettings(current);

	if (_settingsDirty(current)) {
		setInlineBanner('settingsNotice', 'You have unsaved changes in Settings.', 'warning');
	} else if (document.getElementById('settingsNotice')?.textContent === 'You have unsaved changes in Settings.') {
		hideInlineBanner('settingsNotice');
	}
}

function syncSettingsPageState(settings) {
	_settingsBaseline = {
		trend_threshold: Number(settings.trend_threshold ?? SETTINGS_DEFAULTS.trend_threshold),
		detection_period: Number(settings.detection_period ?? SETTINGS_DEFAULTS.detection_period),
		check_interval: Number(settings.check_interval ?? SETTINGS_DEFAULTS.check_interval),
		alert_email: String(settings.alert_email || ''),
		enable_alerts: settings.enable_alerts !== false,
		enable_sound: settings.enable_sound !== false,
	};

	updateSettingsSummary();
}

function applySettingsPreset(name) {
	const presets = {
		balanced: { trend_threshold: 2, detection_period: 30, check_interval: 900, enable_alerts: true, enable_sound: true },
		active: { trend_threshold: 1, detection_period: 14, check_interval: 300, enable_alerts: true, enable_sound: true },
		quiet: { trend_threshold: 3.5, detection_period: 30, check_interval: 1800, enable_alerts: true, enable_sound: false },
	};

	const preset = presets[name];
	if (!preset) return;
	const current = _readSettingsForm();
	_applySettingsForm({ ...current, ...preset });
	setInlineBanner('settingsNotice', `Applied ${name} preset. Save Settings to keep it.`, 'info');
	updateSettingsSummary();
}

function resetSettingsDefaults() {
	_applySettingsForm(SETTINGS_DEFAULTS);
	setInlineBanner('settingsNotice', 'Restored recommended defaults. Save Settings to apply them.', 'info');
	updateSettingsSummary();
}

function initSettingsPage() {
	const fields = Object.values(_settingsFieldMap()).filter(Boolean);
	for (const field of fields) {
		if (field.dataset.settingsBound === '1') continue;
		field.dataset.settingsBound = '1';
		field.addEventListener('input', updateSettingsSummary);
		field.addEventListener('change', updateSettingsSummary);
	}
	updateSettingsSummary();
}

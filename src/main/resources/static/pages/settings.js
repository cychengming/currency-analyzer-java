// Settings & Access Management Module

const SETTINGS_DEFAULTS = Object.freeze({
    trend_threshold: 2,
    detection_period: 30,
    check_interval: 900,
    alert_email: '',
    enable_alerts: true,
    enable_sound: true,
});

let _settingsBaseline = { ...SETTINGS_DEFAULTS };
let _lastGeneratedInvite = { code: '', link: '' };
let _resetTargetUser = null;

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

function _roleDescription(role) {
    if (role === 'ADMIN') {
        return 'ADMIN — Full workspace administration, shared RAG uploads, global settings, personal Alerts 2.0, and AI Assistant.';
    }
    if (role === 'VIEWER') {
        return 'VIEWER — Read-only access to markets, forecasts, knowledge base, and alert views (AI Assistant and writes disabled).';
    }
    return 'MEMBER — Full market views, personal Alerts 2.0 rules/inbox, and AI Assistant within daily quota.';
}

function syncSettingsAccessByRole(user) {
    if (!user) return;
    const isAdmin = Boolean(user.isAdmin);
    const summaryEl = document.getElementById('accountRoleSummary');
    if (summaryEl) {
        const display = user.displayName ? `${user.displayName} (${user.username})` : user.username;
        summaryEl.textContent = `${display} · ${_roleDescription(user.role || 'MEMBER')}`;
    }

    const actions = document.getElementById('globalSettingsActions');
    const readOnlyHint = document.getElementById('globalSettingsReadOnlyHint');
    if (actions) actions.hidden = !isAdmin;
    if (readOnlyHint) readOnlyHint.hidden = isAdmin;

    Object.values(_settingsFieldMap()).forEach(field => {
        if (field) field.disabled = !isAdmin;
    });

    const adminCard = document.getElementById('adminAccessCard');
    if (adminCard) {
        adminCard.hidden = !isAdmin;
    }
    if (isAdmin) {
        refreshAccessManagement();
    }
}

window.syncSettingsAccessByRole = syncSettingsAccessByRole;

async function adminApiRequest(endpoint, options = {}) {
    const method = (options.method || 'GET').toUpperCase();
    const headers = { 'Content-Type': 'application/json', ...options.headers };
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && typeof getCsrfToken === 'function') {
        const token = await getCsrfToken();
        if (token) headers['X-XSRF-TOKEN'] = token;
    }
    const response = await fetch(`${API_BASE}${endpoint}`, {
        ...options,
        method,
        headers,
        credentials: 'include'
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
        throw new Error(payload.error || `Request failed (${response.status})`);
    }
    return payload;
}

async function submitOwnPasswordChange() {
    const currentEl = document.getElementById('currentPasswordInput');
    const newEl = document.getElementById('newPasswordInput');
    const currentPassword = currentEl ? currentEl.value : '';
    const newPassword = newEl ? newEl.value : '';

    hideInlineBanner('accountPasswordNotice');
    try {
        await adminApiRequest('/api/auth/change-password', {
            method: 'POST',
            body: JSON.stringify({
                current_password: currentPassword,
                new_password: newPassword
            })
        });
        if (currentEl) currentEl.value = '';
        if (newEl) newEl.value = '';
        setInlineBanner('accountPasswordNotice', 'Password updated successfully.', 'success');
    } catch (error) {
        setInlineBanner('accountPasswordNotice', error.message || 'Unable to update password.', 'error');
    }
}

async function refreshAccessManagement() {
    const adminCard = document.getElementById('adminAccessCard');
    if (!adminCard || adminCard.hidden || !window.currentUser?.isAdmin) return;
    try {
        const [users, invites] = await Promise.all([
            adminApiRequest('/api/admin/users'),
            adminApiRequest('/api/admin/invites')
        ]);
        renderAdminUsersTable(users);
        renderAdminInvitesTable(invites);
    } catch (error) {
        setInlineBanner('adminAccessNotice', error.message || 'Unable to load users and invites.', 'error');
    }
}

function formatEnUsTime(iso) {
    if (!iso) return 'Never';
    const parsed = new Date(iso);
    if (Number.isNaN(parsed.getTime())) return '—';
    return parsed.toLocaleString('en-US', { dateStyle: 'medium', timeStyle: 'short' });
}

function renderAdminUsersTable(users) {
    const tbody = document.getElementById('adminUsersTableBody');
    if (!tbody) return;
    tbody.replaceChildren();
    users.forEach(user => {
        tbody.appendChild(buildAdminUserRow(user));
    });
}

function buildAdminUserRow(user) {
    const tr = document.createElement('tr');

    const userTd = document.createElement('td');
    const nameStrong = document.createElement('strong');
    nameStrong.textContent = user.username;
    userTd.appendChild(nameStrong);
    if (user.display_name) {
        const sub = document.createElement('div');
        sub.className = 'muted-copy';
        sub.textContent = user.display_name;
        userTd.appendChild(sub);
    }

    const roleTd = document.createElement('td');
    const roleSelect = document.createElement('select');
    roleSelect.className = 'rbac-inline-select';
    roleSelect.setAttribute('aria-label', `Role for ${user.username}`);
    ['ADMIN', 'MEMBER', 'VIEWER'].forEach(roleOption => {
        const opt = document.createElement('option');
        opt.value = roleOption;
        opt.textContent = roleOption;
        opt.selected = user.role === roleOption;
        roleSelect.appendChild(opt);
    });
    roleTd.appendChild(roleSelect);

    const statusTd = document.createElement('td');
    const statusPill = document.createElement('span');
    statusPill.className = user.status === 'DISABLED' ? 'status-pill status-pill-danger' : 'status-pill status-pill-ok';
    statusPill.textContent = user.status || 'ACTIVE';
    statusTd.appendChild(statusPill);

    const loginTd = document.createElement('td');
    loginTd.textContent = formatEnUsTime(user.last_login_at);

    const actionsTd = document.createElement('td');
    actionsTd.className = 'mkt-row-actions';
    actionsTd.appendChild(buildActionButton('Save Role', 'btn-secondary', () => updateAdminUser(user.id, roleSelect.value, user.status)));

    const toggleTargetStatus = user.status === 'DISABLED' ? 'ACTIVE' : 'DISABLED';
    const toggleLabel = user.status === 'DISABLED' ? 'Enable' : 'Disable';
    actionsTd.appendChild(buildActionButton(toggleLabel, 'btn-secondary', () => updateAdminUser(user.id, roleSelect.value, toggleTargetStatus)));
    actionsTd.appendChild(buildActionButton('Reset Password', 'btn-secondary', () => startAdminPasswordReset(user)));

    tr.append(userTd, roleTd, statusTd, loginTd, actionsTd);
    return tr;
}

function buildActionButton(label, className, onClick) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = className;
    btn.textContent = label;
    btn.addEventListener('click', onClick);
    return btn;
}

function renderAdminInvitesTable(invites) {
    const tbody = document.getElementById('adminInvitesTableBody');
    if (!tbody) return;
    tbody.replaceChildren();
    if (!invites.length) {
        const emptyRow = document.createElement('tr');
        const cell = document.createElement('td');
        cell.colSpan = 6;
        cell.className = 'muted-copy';
        cell.textContent = 'No invites generated yet.';
        emptyRow.appendChild(cell);
        tbody.appendChild(emptyRow);
        return;
    }
    invites.forEach(invite => {
        tbody.appendChild(buildAdminInviteRow(invite));
    });
}

function buildAdminInviteRow(invite) {
    const tr = document.createElement('tr');

    const prefixTd = document.createElement('td');
    prefixTd.className = 'app-mono-muted';
    prefixTd.textContent = `${invite.code_prefix}...`;
    if (invite.note) {
        const noteEl = document.createElement('div');
        noteEl.className = 'muted-copy';
        noteEl.textContent = invite.note;
        prefixTd.appendChild(noteEl);
    }

    const roleTd = document.createElement('td');
    roleTd.textContent = invite.role;

    const stateTd = document.createElement('td');
    stateTd.textContent = invite.state;

    const expiresTd = document.createElement('td');
    expiresTd.textContent = formatEnUsTime(invite.expires_at);

    const usedTd = document.createElement('td');
    usedTd.textContent = invite.used_by || '—';

    const actionTd = document.createElement('td');
    if (invite.state === 'PENDING') {
        actionTd.appendChild(buildActionButton('Revoke', 'btn-secondary', () => revokeInvite(invite.id)));
    } else {
        actionTd.textContent = '—';
    }

    tr.append(prefixTd, roleTd, stateTd, expiresTd, usedTd, actionTd);
    return tr;
}

async function createAdminUser() {
    const usernameEl = document.getElementById('adminNewUsername');
    const displayEl = document.getElementById('adminNewDisplayName');
    const passwordEl = document.getElementById('adminNewPassword');
    const roleEl = document.getElementById('adminNewRole');
    hideInlineBanner('adminAccessNotice');
    try {
        const created = await adminApiRequest('/api/admin/users', {
            method: 'POST',
            body: JSON.stringify({
                username: usernameEl?.value || '',
                display_name: displayEl?.value || '',
                password: passwordEl?.value || '',
                role: roleEl?.value || 'MEMBER'
            })
        });
        if (usernameEl) usernameEl.value = '';
        if (displayEl) displayEl.value = '';
        if (passwordEl) passwordEl.value = '';
        setInlineBanner('adminAccessNotice', `Created ${created.role} account "${created.username}".`, 'success');
        await refreshAccessManagement();
    } catch (error) {
        setInlineBanner('adminAccessNotice', error.message || 'Unable to create user.', 'error');
    }
}

async function updateAdminUser(userId, role, status) {
    hideInlineBanner('adminAccessNotice');
    try {
        const updated = await adminApiRequest(`/api/admin/users/${encodeURIComponent(userId)}`, {
            method: 'PUT',
            body: JSON.stringify({ role, status })
        });
        setInlineBanner('adminAccessNotice', `Updated "${updated.username}" (${updated.role}, ${updated.status}).`, 'success');
        await refreshAccessManagement();
    } catch (error) {
        setInlineBanner('adminAccessNotice', error.message || 'Unable to update user.', 'error');
    }
}

function startAdminPasswordReset(user) {
    _resetTargetUser = user;
    const box = document.getElementById('adminResetBox');
    const label = document.getElementById('adminResetLabel');
    const input = document.getElementById('adminResetPasswordInput');
    if (label) label.textContent = `New password for ${user.username}:`;
    if (input) input.value = '';
    if (box) box.hidden = false;
    if (input) input.focus();
}

function cancelAdminPasswordReset() {
    _resetTargetUser = null;
    const box = document.getElementById('adminResetBox');
    const input = document.getElementById('adminResetPasswordInput');
    if (input) input.value = '';
    if (box) box.hidden = true;
}

async function applyAdminPasswordReset() {
    if (!_resetTargetUser) return;
    const input = document.getElementById('adminResetPasswordInput');
    hideInlineBanner('adminAccessNotice');
    try {
        await adminApiRequest(`/api/admin/users/${encodeURIComponent(_resetTargetUser.id)}/reset-password`, {
            method: 'POST',
            body: JSON.stringify({ password: input?.value || '' })
        });
        setInlineBanner('adminAccessNotice', `Password reset for "${_resetTargetUser.username}".`, 'success');
        cancelAdminPasswordReset();
    } catch (error) {
        setInlineBanner('adminAccessNotice', error.message || 'Unable to reset password.', 'error');
    }
}

async function generateInviteCode() {
    const roleEl = document.getElementById('inviteRoleSelect');
    const expiryEl = document.getElementById('inviteExpirySelect');
    const noteEl = document.getElementById('inviteNoteInput');
    hideInlineBanner('adminAccessNotice');
    try {
        const created = await adminApiRequest('/api/admin/invites', {
            method: 'POST',
            body: JSON.stringify({
                role: roleEl?.value || 'MEMBER',
                expires_in_hours: Number(expiryEl?.value || 72),
                note: noteEl?.value || ''
            })
        });
        if (noteEl) noteEl.value = '';
        const inviteCode = created.invite_code || '';
        const inviteLink = `${window.location.origin}/login.html?invite=${encodeURIComponent(inviteCode)}`;
        _lastGeneratedInvite = { code: inviteCode, link: inviteLink };
        const box = document.getElementById('generatedInviteBox');
        const codeEl = document.getElementById('generatedInviteCode');
        if (codeEl) codeEl.textContent = inviteCode;
        if (box) box.hidden = false;
        setInlineBanner('adminAccessNotice', `Generated single-use ${created.invite?.role || 'MEMBER'} invite.`, 'success');
        await refreshAccessManagement();
    } catch (error) {
        setInlineBanner('adminAccessNotice', error.message || 'Unable to generate invite.', 'error');
    }
}

async function revokeInvite(inviteId) {
    hideInlineBanner('adminAccessNotice');
    try {
        await adminApiRequest(`/api/admin/invites/${encodeURIComponent(inviteId)}`, { method: 'DELETE' });
        setInlineBanner('adminAccessNotice', 'Invite revoked.', 'info');
        await refreshAccessManagement();
    } catch (error) {
        setInlineBanner('adminAccessNotice', error.message || 'Unable to revoke invite.', 'error');
    }
}

async function copyGeneratedInvite(kind) {
    const value = kind === 'link' ? _lastGeneratedInvite.link : _lastGeneratedInvite.code;
    if (!value || !navigator.clipboard) return;
    try {
        await navigator.clipboard.writeText(value);
        setInlineBanner('adminAccessNotice', kind === 'link' ? 'Invite link copied.' : 'Invite code copied.', 'success');
    } catch {
        setInlineBanner('adminAccessNotice', 'Clipboard copy was blocked by the browser.', 'warning');
    }
}

window.refreshAccessManagement = refreshAccessManagement;

function initSettingsPage() {
    const fields = Object.values(_settingsFieldMap()).filter(Boolean);
    for (const field of fields) {
        if (field.dataset.settingsBound === '1') continue;
        field.dataset.settingsBound = '1';
        field.addEventListener('input', updateSettingsSummary);
        field.addEventListener('change', updateSettingsSummary);
    }
    updateSettingsSummary();
    if (window.currentUser && window.currentUser.username) {
        syncSettingsAccessByRole(window.currentUser);
    }
}

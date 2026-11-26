/* React-based login, invite redemption, and optional open registration (no build step)
   Uses the existing backend endpoints:
   - POST /api/auth/login
   - POST /api/auth/register
   - GET  /api/auth/status
*/

(function () {
    const API_BASE = window.location.origin;
    const h = React.createElement;
    const { useEffect, useState } = React;

    function initialInviteFromUrl() {
        try {
            const params = new URLSearchParams(window.location.search || '');
            return (params.get('invite') || '').trim();
        } catch {
            return '';
        }
    }

    function LoginApp() {
        const urlInvite = initialInviteFromUrl();
        const [tab, setTab] = useState(urlInvite ? 'register' : 'login');
        const [busy, setBusy] = useState(false);

        const [loginUsername, setLoginUsername] = useState('');
        const [loginPassword, setLoginPassword] = useState('');
        const [loginError, setLoginError] = useState('');

        const [registerUsername, setRegisterUsername] = useState('');
        const [registerDisplayName, setRegisterDisplayName] = useState('');
        const [registerPassword, setRegisterPassword] = useState('');
        const [inviteCode, setInviteCode] = useState(urlInvite);
        const [registerError, setRegisterError] = useState('');

        const [registrationEnabled, setRegistrationEnabled] = useState(false);
        const [inviteMode, setInviteMode] = useState(Boolean(urlInvite));
        const [infoMessage, setInfoMessage] = useState('');

        async function checkStatus() {
            try {
                const res = await fetch(`${API_BASE}/api/auth/status`, { credentials: 'include' });
                const data = await res.json();
                if (data && data.logged_in) {
                    window.location.href = '/dashboard.html';
                    return;
                }
                const enabled = data?.registration_enabled !== false;
                setRegistrationEnabled(enabled);
                if (!enabled && !urlInvite) {
                    setTab('login');
                }
            } catch {
                // ignore
            }
        }

        useEffect(() => {
            checkStatus();
        }, []);

        async function handleLoginSubmit(e) {
            e.preventDefault();
            setInfoMessage('');
            setLoginError('');

            if (!loginUsername || !loginPassword) {
                setLoginError('Please enter username and password');
                return;
            }

            try {
                setBusy(true);
                const response = await fetch(`${API_BASE}/api/auth/login`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    credentials: 'include',
                    body: JSON.stringify({ username: loginUsername, password: loginPassword })
                });
                const result = await response.json();
                if (result && result.success) {
                    window.location.href = '/dashboard.html';
                } else {
                    setLoginError(result.error || 'Login failed');
                }
            } catch (err) {
                setLoginError('Error logging in: ' + (err?.message || String(err)));
            } finally {
                setBusy(false);
            }
        }

        async function handleRegisterSubmit(e) {
            e.preventDefault();
            setInfoMessage('');
            setRegisterError('');

            if (!registerUsername || !registerPassword) {
                setRegisterError('Please enter username and password');
                return;
            }
            if (!registrationEnabled && !inviteCode.trim()) {
                setRegisterError('Invite code is required');
                return;
            }

            try {
                setBusy(true);
                const response = await fetch(`${API_BASE}/api/auth/register`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    credentials: 'include',
                    body: JSON.stringify({
                        username: registerUsername,
                        password: registerPassword,
                        display_name: registerDisplayName,
                        invite_code: inviteCode
                    })
                });
                const result = await response.json();

                if (result && result.success) {
                    setRegisterUsername('');
                    setRegisterDisplayName('');
                    setRegisterPassword('');
                    setInviteCode('');
                    setInviteMode(false);
                    setTab('login');
                    setInfoMessage('Account created! Please login.');
                } else {
                    setRegisterError(result.error || 'Registration failed');
                }
            } catch (err) {
                setRegisterError('Error registering: ' + (err?.message || String(err)));
            } finally {
                setBusy(false);
            }
        }

        function switchMode(nextTab, nextInviteMode) {
            setInfoMessage('');
            setLoginError('');
            setRegisterError('');
            setTab(nextTab);
            setInviteMode(nextInviteMode);
        }

        function renderTabButton(id, label) {
            return h('button', {
                key: id,
                className: 'tab-btn' + (tab === id ? ' active' : ''),
                onClick: () => switchMode(id, id === 'register' && !registrationEnabled),
                type: 'button',
                disabled: busy
            }, label);
        }

        const showRegisterForm = (registrationEnabled && tab === 'register') || inviteMode;

        function renderLoginForm() {
            return h('form', { key: 'loginForm', onSubmit: handleLoginSubmit }, [
                h('div', { className: 'form-group', key: 'lu' }, [
                    h('label', null, 'Username'),
                    h('input', {
                        type: 'text',
                        value: loginUsername,
                        onChange: (e) => setLoginUsername(e.target.value),
                        placeholder: 'Enter username',
                        autoComplete: 'username',
                        disabled: busy
                    })
                ]),
                h('div', { className: 'form-group', key: 'lp' }, [
                    h('label', null, 'Password'),
                    h('input', {
                        type: 'password',
                        value: loginPassword,
                        onChange: (e) => setLoginPassword(e.target.value),
                        placeholder: 'Enter password',
                        autoComplete: 'current-password',
                        disabled: busy
                    })
                ]),
                h('button', { className: 'btn-primary', type: 'submit', disabled: busy, key: 'btn' }, busy ? 'Please wait…' : 'Login'),
                h('div', { className: 'error-msg', key: 'err' }, loginError)
            ]);
        }

        function renderRegisterForm() {
            const needInvite = !registrationEnabled;
            return h('form', { key: 'registerForm', onSubmit: handleRegisterSubmit }, [
                h('div', { className: 'form-group', key: 'ri' }, [
                    h('label', null, needInvite ? 'Invite Code' : 'Invite Code (optional)'),
                    h('input', {
                        type: 'text',
                        value: inviteCode,
                        onChange: (e) => setInviteCode(e.target.value),
                        placeholder: 'CA-XXXX-XXXX-XXXX',
                        autoComplete: 'off',
                        disabled: busy
                    })
                ]),
                h('div', { className: 'form-group', key: 'ru' }, [
                    h('label', null, 'Username'),
                    h('input', {
                        type: 'text',
                        value: registerUsername,
                        onChange: (e) => setRegisterUsername(e.target.value),
                        placeholder: 'Choose username (min 3 chars)',
                        autoComplete: 'username',
                        disabled: busy
                    })
                ]),
                h('div', { className: 'form-group', key: 'rd' }, [
                    h('label', null, 'Display Name (optional)'),
                    h('input', {
                        type: 'text',
                        value: registerDisplayName,
                        onChange: (e) => setRegisterDisplayName(e.target.value),
                        placeholder: 'e.g. Alex Chen',
                        autoComplete: 'name',
                        disabled: busy
                    })
                ]),
                h('div', { className: 'form-group', key: 'rp' }, [
                    h('label', null, 'Password'),
                    h('input', {
                        type: 'password',
                        value: registerPassword,
                        onChange: (e) => setRegisterPassword(e.target.value),
                        placeholder: 'Choose password (min 6 chars)',
                        autoComplete: 'new-password',
                        disabled: busy
                    })
                ]),
                h('button', { className: 'btn-primary', type: 'submit', disabled: busy, key: 'btn' }, busy ? 'Please wait…' : 'Create Account'),
                h('div', { className: 'error-msg', key: 'err' }, registerError)
            ]);
        }

        function renderInviteFooter() {
            if (registrationEnabled) return null;
            return h('div', { key: 'inviteToggle', className: 'login-invite-toggle' },
                h('button', {
                    type: 'button',
                    className: 'app-link-btn',
                    disabled: busy,
                    onClick: () => switchMode(inviteMode ? 'login' : 'register', !inviteMode)
                }, inviteMode ? 'Back to Login' : 'Have an invite code? Create account')
            );
        }

        return h('div', { className: 'login-container' },
            h('div', { className: 'login-card' }, [
                h('h1', { key: 'title' }, '📈 Currency Analyzer'),
                infoMessage
                    ? h('div', { key: 'info', style: { marginBottom: 12, color: '#93c5fd', fontSize: 13, textAlign: 'center' } }, infoMessage)
                    : null,
                registrationEnabled
                    ? h('div', { className: 'tabs', key: 'tabs' }, [
                        renderTabButton('login', 'Login'),
                        renderTabButton('register', 'Register')
                    ])
                    : null,
                showRegisterForm ? renderRegisterForm() : renderLoginForm(),
                renderInviteFooter()
            ].filter(Boolean))
        );
    }

    function mount() {
        const mountEl = document.getElementById('reactLoginRoot');
        if (!mountEl) return;
        const root = ReactDOM.createRoot(mountEl);
        root.render(h(LoginApp));
    }

    document.addEventListener('DOMContentLoaded', mount);
})();

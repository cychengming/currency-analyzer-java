// Authentication & Role Context Module

window.currentUser = window.currentUser || {
    username: null,
    displayName: null,
    role: 'MEMBER',
    isAdmin: false,
    canWrite: true,
    canUseAi: true
};

async function handleLogin() {
    const username = document.getElementById('loginUsername').value;
    const password = document.getElementById('loginPassword').value;
    const errorDiv = document.getElementById('loginError');

    if (!username || !password) {
        errorDiv.textContent = 'Please enter username and password';
        return;
    }

    try {
        const response = await fetch(`${API_BASE}/api/auth/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
            body: JSON.stringify({ username, password })
        });

        const result = await response.json();

        if (result.success) {
            errorDiv.textContent = '';
            window.location.href = '/dashboard.html';
        } else {
            errorDiv.textContent = result.error || 'Login failed';
        }
    } catch (error) {
        console.error('Login error:', error);
        errorDiv.textContent = 'Error logging in: ' + error.message;
    }
}

async function handleRegister() {
    const username = document.getElementById('registerUsername').value;
    const password = document.getElementById('registerPassword').value;
    const errorDiv = document.getElementById('registerError');

    if (!username || !password) {
        errorDiv.textContent = 'Please enter username and password';
        return;
    }

    try {
        const response = await fetch(`${API_BASE}/api/auth/register`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
            body: JSON.stringify({ username, password })
        });

        const result = await response.json();

        if (result.success) {
            errorDiv.textContent = '';
            document.getElementById('registerUsername').value = '';
            document.getElementById('registerPassword').value = '';
            document.getElementById('loginError').textContent = 'Registration successful! Please login.';
            switchTab('login');
        } else {
            errorDiv.textContent = result.error || 'Registration failed';
        }
    } catch (error) {
        console.error('Register error:', error);
        errorDiv.textContent = 'Error registering: ' + error.message;
    }
}

async function handleLogout() {
    try {
        const csrfToken = typeof getCsrfToken === 'function' ? await getCsrfToken() : null;
        await fetch(`${API_BASE}/api/auth/logout`, {
            method: 'POST',
            headers: csrfToken ? { 'X-XSRF-TOKEN': csrfToken } : {},
            credentials: 'include'
        });
        window.location.href = '/login.html';
    } catch (error) {
        console.error('Logout error:', error);
        showToast('Error logging out', 'error');
    }
}

function renderSidebarUserCard(user) {
    const userInfo = document.getElementById('userInfo');
    if (!userInfo) return;

    const label = user.displayName ? `${user.displayName} (${user.username})` : user.username;
    const badge = document.createElement('span');
    badge.className = `role-badge role-badge-${String(user.role || 'MEMBER').toLowerCase()}`;
    badge.textContent = user.role || 'MEMBER';

    const nameSpan = document.createElement('span');
    nameSpan.className = 'app-user-label';
    nameSpan.textContent = `Logged in as: ${label}`;

    userInfo.replaceChildren(nameSpan, badge);
}

function applyRoleUiState(user) {
    const startBtn = document.getElementById('startBtn');
    const stopBtn = document.getElementById('stopBtn');
    const aiButton = document.getElementById('aiChatOpenButton');

    if (!user.isAdmin) {
        if (startBtn) startBtn.hidden = true;
        if (stopBtn) stopBtn.hidden = true;
    }
    if (!user.canUseAi && aiButton) {
        aiButton.hidden = true;
    }
    if (typeof window.syncSettingsAccessByRole === 'function') {
        window.syncSettingsAccessByRole(user);
    }
}

async function checkAuth() {
    try {
        const response = await fetch(`${API_BASE}/api/auth/status`, {
            credentials: 'include'
        });
        const result = await response.json();

        if (result && result.logged_in) {
            const user = {
                username: result.username,
                displayName: result.display_name || null,
                role: result.role || 'MEMBER',
                isAdmin: Boolean(result.is_admin),
                canWrite: result.can_write !== false,
                canUseAi: result.can_use_ai !== false
            };
            window.currentUser = user;
            renderSidebarUserCard(user);
            applyRoleUiState(user);
            window.dispatchEvent(new CustomEvent('ca:auth-ready', { detail: user }));
            return true;
        }
        if (window.location.pathname !== '/login.html' && !window.location.pathname.endsWith('index.html')) {
            window.location.href = '/login.html';
        }
        return false;
    } catch (error) {
        console.error('Error checking auth:', error);
        return false;
    }
}

document.addEventListener('DOMContentLoaded', () => {
    if (document.getElementById('dashboard')) {
        checkAuth();
    }
});

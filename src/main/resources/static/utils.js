// Shared Utilities
const API_BASE = window.location.origin;
const DASHBOARD_UI_STATE_KEY = 'currencyAnalyzer.dashboardState';
const DASHBOARD_ONBOARDING_KEY = 'currencyAnalyzer.onboardingDismissed';

// Unified asset list — single source of truth for all pages.
// When liveRates are available they take precedence; this is the fallback.
const DEFAULT_PAIRS = [
    'EUR/USD', 'GBP/USD', 'USD/JPY', 'USD/CHF', 'AUD/USD', 'USD/CAD', 'NZD/USD',
    'GOLD/USD', 'SILVER/USD', 'COPPER/USD', 'WHEAT/USD', 'SOYBEAN/USD', 'CORN/USD',
    'WTI/USD', 'BRENT/USD', 'NDX/USD'
];

/**
 * Return the current list of available asset pairs.
 * Prefers live data keys; falls back to DEFAULT_PAIRS.
 */
function getAvailablePairs() {
    const live = Object.keys(window.liveRates || {}).sort();
    return (live && live.length) ? live : DEFAULT_PAIRS;
}

function showToast(message, type = 'success') {
    const toast = document.getElementById('toast');
    if (!toast) return;
    toast.textContent = message;
    toast.className = `toast ${type} show`;
    setTimeout(() => toast.classList.remove('show'), 3000);
}

function setInlineBanner(id, message, tone = 'info') {
    const el = document.getElementById(id);
    if (!el) return;
    if (!message) {
        el.style.display = 'none';
        el.textContent = '';
        el.className = 'inline-banner';
        return;
    }

    el.style.display = 'block';
    el.textContent = message;
    el.className = `inline-banner inline-banner-${tone}`;
}

function hideInlineBanner(id) {
    setInlineBanner(id, '');
}

function switchTab(tab, evt) {
    const tabBtns = document.querySelectorAll('.tab-btn');
    const tabContents = document.querySelectorAll('.tab-content');

    tabBtns.forEach(btn => btn.classList.remove('active'));
    tabContents.forEach(content => content.classList.remove('active'));

    const e = evt || window.event;
    if (e && e.target) {
        e.target.classList.add('active');
    }
    document.getElementById(tab + 'Tab').classList.add('active');
}

function switchPage(page, evt) {
    const navTabs = document.querySelectorAll('.nav-tab');
    const pages = document.querySelectorAll('.page');

    navTabs.forEach(tab => tab.classList.remove('active'));
    pages.forEach(p => p.classList.remove('active'));

    const e = evt || window.event;
    const activeTab = (e && e.target && e.target.classList.contains('nav-tab'))
        ? e.target
        : document.querySelector(`.nav-tab[data-page="${page}"]`);
    if (activeTab) {
        activeTab.classList.add('active');
    }

    const pageEl = document.getElementById(page);
    if (pageEl) {
        pageEl.classList.add('active');
    }

    try {
        const existing = JSON.parse(localStorage.getItem(DASHBOARD_UI_STATE_KEY) || '{}');
        existing.activePage = page;
        localStorage.setItem(DASHBOARD_UI_STATE_KEY, JSON.stringify(existing));
    } catch (_) {
        // Ignore localStorage errors.
    }

    if (typeof window.handleDashboardPageChange === 'function') {
        window.handleDashboardPageChange(page);
    }
}

async function fetchAPI(endpoint, options = {}) {
    try {
        const method = (options.method || 'GET').toUpperCase();
        const headers = {
            'Content-Type': 'application/json',
            ...options.headers
        };
        if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
            const csrfToken = await getCsrfToken();
            if (csrfToken) {
                headers['X-XSRF-TOKEN'] = csrfToken;
            }
        }

        const response = await fetch(`${API_BASE}${endpoint}`, {
            ...options,
            headers,
            credentials: 'include',
        });

        if (response.status === 401) {
            if (window.location.pathname !== '/login.html' && !window.location.pathname.endsWith('index.html')) {
                window.location.href = '/login.html';
            }
            return null;
        }

        if (!response.ok) {
            console.error(`API Error: ${response.status} ${response.statusText} for ${endpoint}`);
            return null;
        }

        const contentType = response.headers.get('content-type') || '';
        if (!contentType.includes('application/json')) {
            console.error(`Unexpected response content type for ${endpoint}: ${contentType}`);
            return null;
        }

        return await response.json();
    } catch (error) {
        console.error('API Error:', error);
        return null;
    }
}

async function getCsrfToken() {
    const cookieToken = readCookie('XSRF-TOKEN');
    if (cookieToken) {
        return cookieToken;
    }

    const response = await fetch(`${API_BASE}/api/auth/csrf`, {
        credentials: 'include'
    });
    if (!response.ok) {
        return null;
    }

    const payload = await response.json();
    return payload.token || readCookie('XSRF-TOKEN');
}

function readCookie(name) {
    const prefix = `${name}=`;
    const cookie = document.cookie
        .split(';')
        .map(value => value.trim())
        .find(value => value.startsWith(prefix));
    return cookie ? decodeURIComponent(cookie.slice(prefix.length)) : null;
}

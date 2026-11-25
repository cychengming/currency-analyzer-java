// Binance-Style Markets (2-Step Table -> Detail) + Per-User Alerts 2.0 Module

const PAIR_METADATA = Object.freeze({
    'EUR/USD':     { name: 'Euro / US Dollar',        cat: 'FX',      dec: 4 },
    'GBP/USD':     { name: 'British Pound / USD',     cat: 'FX',      dec: 4 },
    'USD/JPY':     { name: 'US Dollar / Japanese Yen',cat: 'FX',      dec: 2 },
    'USD/CHF':     { name: 'US Dollar / Swiss Franc', cat: 'FX',      dec: 4 },
    'AUD/USD':     { name: 'Australian Dollar / USD', cat: 'FX',      dec: 4 },
    'USD/CAD':     { name: 'US Dollar / Canadian $',  cat: 'FX',      dec: 4 },
    'NZD/USD':     { name: 'New Zealand Dollar / USD',cat: 'FX',      dec: 4 },
    'GOLD/USD':    { name: 'Gold Spot',               cat: 'Metals',  dec: 2 },
    'SILVER/USD':  { name: 'Silver Spot',             cat: 'Metals',  dec: 2 },
    'COPPER/USD':  { name: 'Copper Futures',          cat: 'Metals',  dec: 3 },
    'WTI/USD':     { name: 'WTI Crude Oil',           cat: 'Energy',  dec: 2 },
    'BRENT/USD':   { name: 'Brent Crude Oil',         cat: 'Energy',  dec: 2 },
    'NDX/USD':     { name: 'Nasdaq-100 Index',        cat: 'Indices', dec: 1 },
    'SPX/USD':     { name: 'S&P 500 Index',           cat: 'Indices', dec: 1 },
    'WHEAT/USD':   { name: 'Wheat Futures',           cat: 'Agri',    dec: 2 },
    'SOYBEAN/USD': { name: 'Soybean Futures',         cat: 'Agri',    dec: 2 },
    'CORN/USD':    { name: 'Corn Futures',            cat: 'Agri',    dec: 2 }
});

const alertsV2State = {
    rules: [],
    inbox: [],
    unreadCount: 0,
    alertEmail: '',
    favorites: new Set(),
    marketCategory: 'all',
    marketSubView: 'list',
    detailSubTab: 'rules',
    inboxFilter: 'all',
    editingRuleId: null,
    modalHistory: [],
    modalMin: 0,
    modalMax: 100,
    initialized: false
};

function escapeHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function pairMeta(pair) {
    const known = PAIR_METADATA[pair];
    if (known) return known;
    return { name: pair, cat: 'FX', dec: 4 };
}

function formatRate(value, pair) {
    const num = Number(value);
    if (!Number.isFinite(num)) return '—';
    const dec = pairMeta(pair).dec;
    return num.toLocaleString('en-US', { minimumFractionDigits: dec, maximumFractionDigits: dec });
}

function formatPct(value) {
    const num = Number(value);
    if (!Number.isFinite(num)) return '0.00%';
    return `${num >= 0 ? '+' : ''}${num.toFixed(2)}%`;
}

function formatConditionSummary(rule) {
    if (rule.condition_type === 'cross_above') {
        return `Crosses Above ${formatRate(rule.target_value, rule.pair)}`;
    }
    if (rule.condition_type === 'cross_below') {
        return `Crosses Below ${formatRate(rule.target_value, rule.pair)}`;
    }
    if (rule.condition_type === 'pct_move') {
        return `${rule.window_days || 30}d Move ≥ ${Number(rule.target_value || 0).toFixed(1)}%`;
    }
    if (rule.condition_type === 'ma_crossover') {
        return `MA${rule.ma_short_period || 20}/${rule.ma_long_period || 50} (${(rule.signal_type || 'both').replace('_', ' ')})`;
    }
    return rule.condition_type || 'Alert';
}

function formatFrequencySummary(rule) {
    if (rule.frequency !== 'RECURRING') return 'Notify Once';
    const sec = Number(rule.cooldown_seconds || 3600);
    if (sec >= 86400) return 'Recurring · Daily';
    if (sec >= 3600) return `Recurring · ${Math.round(sec / 3600)}h`;
    return `Recurring · ${Math.max(1, Math.round(sec / 60))}m`;
}

function formatRuleStatusBadge(rule) {
    if (rule.status === 'PAUSED') {
        return '<span class="mkt-badge mkt-badge-muted">Paused</span>';
    }
    if (rule.status === 'TRIGGERED') {
        return '<span class="mkt-badge mkt-badge-primary">Triggered (Off)</span>';
    }
    if (Number(rule.cooldown_remaining_seconds || 0) > 0) {
        const mins = Math.max(1, Math.ceil(Number(rule.cooldown_remaining_seconds) / 60));
        return `<span class="mkt-badge mkt-badge-warn">Cooldown · ${mins}m</span>`;
    }
    return '<span class="mkt-badge mkt-badge-up">Armed</span>';
}

function formatRuleDistance(rule) {
    if (rule.status === 'TRIGGERED') return 'Done (Off)';
    if (rule.status === 'PAUSED') return 'Paused';
    if (rule.distance_pct !== null && rule.distance_pct !== undefined && Number.isFinite(Number(rule.distance_pct))) {
        return formatPct(rule.distance_pct);
    }
    return 'Watching';
}

async function apiRequestWithError(endpoint, options = {}) {
    const method = (options.method || 'GET').toUpperCase();
    const headers = { 'Content-Type': 'application/json', ...options.headers };
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
        const csrf = await getCsrfToken();
        if (csrf) headers['X-XSRF-TOKEN'] = csrf;
    }
    const response = await fetch(`${API_BASE}${endpoint}`, {
        ...options,
        method,
        headers,
        credentials: 'include'
    });
    if (response.status === 401) {
        window.location.href = '/login.html';
        return { ok: false, error: 'Not authenticated' };
    }
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
        return { ok: false, status: response.status, error: body.error || `Request failed (${response.status})`, body };
    }
    return { ok: true, status: response.status, data: body };
}

function initMarketsAndAlertsV2() {
    if (alertsV2State.initialized) return;
    alertsV2State.initialized = true;

    bindGlobalTopbarEvents();
    bindMarketsEvents();
    bindAlertsWorkspaceEvents();
    bindRuleModalEvents();
}

function bindGlobalTopbarEvents() {
    const searchInput = document.getElementById('globalPairSearch');
    const searchResults = document.getElementById('globalPairResults');
    if (searchInput && searchResults) {
        searchInput.addEventListener('input', () => renderGlobalSearchResults(searchInput.value));
        document.addEventListener('click', evt => {
            if (!searchInput.contains(evt.target) && !searchResults.contains(evt.target)) {
                searchResults.hidden = true;
            }
        });
    }

    const bellBtn = document.getElementById('topBellBtn');
    const bellPop = document.getElementById('topBellPopover');
    if (bellBtn && bellPop) {
        bellBtn.addEventListener('click', () => {
            bellPop.hidden = !bellPop.hidden;
            bellBtn.setAttribute('aria-expanded', String(!bellPop.hidden));
        });
        document.addEventListener('click', evt => {
            if (!bellBtn.contains(evt.target) && !bellPop.contains(evt.target)) {
                bellPop.hidden = true;
                bellBtn.setAttribute('aria-expanded', 'false');
            }
        });
    }

    const bellMarkAll = document.getElementById('topBellMarkAllBtn');
    if (bellMarkAll) {
        bellMarkAll.addEventListener('click', markAllInboxRead);
    }
}

function bindMarketsEvents() {
    const pills = document.getElementById('mktCategoryPills');
    if (pills) {
        pills.addEventListener('click', evt => {
            const btn = evt.target.closest('[data-mkt-cat]');
            if (!btn) return;
            alertsV2State.marketCategory = btn.dataset.mktCat;
            pills.querySelectorAll('[data-mkt-cat]').forEach(el => el.classList.toggle('active', el === btn));
            renderMarketsTable();
        });
    }

    const filterInput = document.getElementById('mktTableFilter');
    if (filterInput) {
        filterInput.addEventListener('input', renderMarketsTable);
    }

    const detailSelect = document.getElementById('detailPairSelect');
    if (detailSelect) {
        detailSelect.addEventListener('change', () => openMarketDetail(detailSelect.value));
    }

    const detailStar = document.getElementById('detailStarBtn');
    if (detailStar) {
        detailStar.addEventListener('click', () => toggleFavoritePair(selectedPair));
    }

    const detailSetAlert = document.getElementById('detailSetAlertBtn');
    if (detailSetAlert) {
        detailSetAlert.addEventListener('click', () => openAlertRuleModal(null, selectedPair));
    }

    const detailSubAdd = document.getElementById('detailSubAddRuleBtn');
    if (detailSubAdd) {
        detailSubAdd.addEventListener('click', () => openAlertRuleModal(null, selectedPair));
    }

    const detailAskAi = document.getElementById('detailAskAiBtn');
    if (detailAskAi) {
        detailAskAi.addEventListener('click', () => askAiAboutPair(selectedPair));
    }

    const tfContainer = document.getElementById('detailTimeframePills');
    if (tfContainer) {
        tfContainer.addEventListener('click', evt => {
            const btn = evt.target.closest('[data-tf-days]');
            if (!btn) return;
            const days = Number(btn.dataset.tfDays);
            if (!Number.isFinite(days) || days <= 0) return;
            timeframeUnit = 'days';
            timeframeValue = days;
            writeDashboardState({ timeframeUnit, timeframeValue });
            tfContainer.querySelectorAll('[data-tf-days]').forEach(el => el.classList.toggle('active', el === btn));
            fetchHistoricalData();
        });
    }

    document.querySelectorAll('[data-detail-tab]').forEach(btn => {
        btn.addEventListener('click', () => {
            alertsV2State.detailSubTab = btn.dataset.detailTab;
            document.querySelectorAll('[data-detail-tab]').forEach(el => el.classList.toggle('active', el === btn));
            document.getElementById('detailPanelRules').hidden = alertsV2State.detailSubTab !== 'rules';
            document.getElementById('detailPanelSignals').hidden = alertsV2State.detailSubTab !== 'signals';
            document.getElementById('detailPanelActions').hidden = alertsV2State.detailSubTab !== 'actions';
        });
    });
}

function bindAlertsWorkspaceEvents() {
    const newBtn = document.getElementById('alertsNewRuleBtn');
    if (newBtn) newBtn.addEventListener('click', () => openAlertRuleModal(null, selectedPair));

    const saveEmailBtn = document.getElementById('alertsSaveEmailBtn');
    if (saveEmailBtn) saveEmailBtn.addEventListener('click', savePersonalAlertEmail);

    const testEmailBtn = document.getElementById('alertsTestEmailBtn');
    if (testEmailBtn) testEmailBtn.addEventListener('click', sendPersonalTestEmail);

    const markAllBtn = document.getElementById('alertsMarkAllReadBtn');
    if (markAllBtn) markAllBtn.addEventListener('click', markAllInboxRead);

    const clearBtn = document.getElementById('alertsClearInboxBtn');
    if (clearBtn) clearBtn.addEventListener('click', clearPersonalInbox);

    document.querySelectorAll('[data-inbox-filter]').forEach(btn => {
        btn.addEventListener('click', () => {
            alertsV2State.inboxFilter = btn.dataset.inboxFilter;
            document.querySelectorAll('[data-inbox-filter]').forEach(el => el.classList.toggle('active', el === btn));
            renderAlertsV2Inbox();
        });
    });
}

function bindRuleModalEvents() {
    const modal = document.getElementById('alertRuleModal');
    if (!modal) return;

    document.getElementById('alertModalCloseBtn')?.addEventListener('click', closeAlertRuleModal);
    document.getElementById('alertModalCancelBtn')?.addEventListener('click', closeAlertRuleModal);
    modal.addEventListener('click', evt => {
        if (evt.target === modal) closeAlertRuleModal();
    });
    document.addEventListener('keydown', evt => {
        if (evt.key === 'Escape' && !modal.hidden) closeAlertRuleModal();
    });

    document.getElementById('alertModalPair')?.addEventListener('change', async () => {
        const pair = document.getElementById('alertModalPair').value;
        await loadModalSparkline(pair);
        seedModalDefaultTarget(pair, document.getElementById('alertModalCondition').value);
        updateModalPreview();
    });

    document.getElementById('alertModalCondition')?.addEventListener('change', () => {
        const pair = document.getElementById('alertModalPair').value;
        const cond = document.getElementById('alertModalCondition').value;
        seedModalDefaultTarget(pair, cond);
        updateModalPreview();
    });

    document.getElementById('alertModalTargetSlider')?.addEventListener('input', () => {
        const pair = document.getElementById('alertModalPair').value;
        const cond = document.getElementById('alertModalCondition').value;
        const pct = Number(document.getElementById('alertModalTargetSlider').value) / 100;
        const numInput = document.getElementById('alertModalTargetNum');
        if (cond === 'pct_move') {
            numInput.value = (0.5 + pct * 14.5).toFixed(1);
        } else {
            const val = alertsV2State.modalMin + pct * (alertsV2State.modalMax - alertsV2State.modalMin);
            numInput.value = val.toFixed(pairMeta(pair).dec);
        }
        updateModalPreview();
    });

    document.getElementById('alertModalTargetNum')?.addEventListener('input', () => {
        syncSliderFromTargetInput();
        updateModalPreview();
    });

    ['alertModalWindow', 'alertModalFrequency', 'alertModalMaShort', 'alertModalMaLong', 'alertModalSignalType'].forEach(id => {
        document.getElementById(id)?.addEventListener('change', updateModalPreview);
    });

    document.getElementById('alertModalSaveBtn')?.addEventListener('click', saveAlertRuleFromModal);
    document.getElementById('alertModalDeleteBtn')?.addEventListener('click', deleteEditingRuleFromModal);
}

async function refreshAlertsV2() {
    const [rulesRes, inboxRes] = await Promise.all([
        apiRequestWithError('/api/alert-rules'),
        apiRequestWithError('/api/alert-inbox')
    ]);

    if (rulesRes.ok && Array.isArray(rulesRes.data)) {
        alertsV2State.rules = rulesRes.data;
    }
    if (inboxRes.ok && inboxRes.data) {
        alertsV2State.inbox = Array.isArray(inboxRes.data.alerts) ? inboxRes.data.alerts : [];
        alertsV2State.unreadCount = Number(inboxRes.data.unread_count || 0);
        alertsV2State.alertEmail = String(inboxRes.data.alert_email || '');
        alertsV2State.favorites = new Set(Array.isArray(inboxRes.data.favorites) ? inboxRes.data.favorites : []);
    }

    renderAllAlertsV2Views();
}

function renderAllAlertsV2Views() {
    updateUnreadBadges();
    renderBellPopover();
    renderAlertsV2Workspace();
    renderMarketsShell();
    if (alertsV2State.marketSubView === 'detail' && window.lastChartPoints && window.lastChartPoints.length > 0) {
        updateChart(window.lastChartPoints);
    }
}

function updateUnreadBadges() {
    const count = alertsV2State.unreadCount;
    const navBadge = document.getElementById('navAlertsUnreadBadge');
    if (navBadge) {
        navBadge.textContent = String(count);
        navBadge.hidden = count <= 0;
    }
    const bellBadge = document.getElementById('topBellCount');
    if (bellBadge) {
        bellBadge.textContent = String(count);
        bellBadge.hidden = count <= 0;
    }
}

function renderBellPopover() {
    const list = document.getElementById('topBellList');
    if (!list) return;
    if (alertsV2State.inbox.length === 0) {
        list.innerHTML = '<div class="muted-copy" style="padding:8px 0;">All caught up — no triggered alerts yet.</div>';
        return;
    }
    list.innerHTML = alertsV2State.inbox.slice(0, 5).map(item => `
        <div class="app-bell-item">
            <div style="display:flex; justify-content:space-between; gap:6px;">
                <strong>${escapeHtml(item.pair)} · ${escapeHtml(item.rule_name || item.alert_type || 'Alert')}</strong>
                <span class="muted-copy">${escapeHtml(formatShortTimestamp(item.timestamp))}</span>
            </div>
            <div class="muted-copy" style="margin-top:3px;">
                ${formatPct(item.percent_change)} (${formatRate(item.old_rate, item.pair)} → ${formatRate(item.new_rate, item.pair)})
            </div>
        </div>
    `).join('');
}

function renderGlobalSearchResults(rawQuery) {
    const box = document.getElementById('globalPairResults');
    if (!box) return;
    const q = String(rawQuery || '').trim().toUpperCase();
    if (!q) {
        box.hidden = true;
        return;
    }
    const pairs = getAvailablePairs().filter(pair => {
        const meta = pairMeta(pair);
        return pair.includes(q) || meta.name.toUpperCase().includes(q) || meta.cat.toUpperCase().includes(q);
    }).slice(0, 8);

    if (pairs.length === 0) {
        box.hidden = true;
        return;
    }
    box.innerHTML = pairs.map(pair => {
        const meta = pairMeta(pair);
        const quote = (window.liveRates || {})[pair];
        const chgHtml = quote
            ? `<span class="${quote.changePercent >= 0 ? 'positive' : 'negative'}">${formatPct(quote.changePercent)}</span>`
            : '';
        return `<button type="button" class="app-search-item" data-search-pair="${escapeHtml(pair)}">
            <span><strong>${escapeHtml(pair)}</strong> <span class="muted-copy">${escapeHtml(meta.name)} · ${escapeHtml(meta.cat)}</span></span>
            ${chgHtml}
        </button>`;
    }).join('');
    box.hidden = false;

    box.querySelectorAll('[data-search-pair]').forEach(btn => {
        btn.addEventListener('click', () => {
            document.getElementById('globalPairSearch').value = '';
            box.hidden = true;
            switchPage('overview');
            openMarketDetail(btn.dataset.searchPair);
        });
    });
}

function renderMarketsShell() {
    const pairs = getAvailablePairs();
    const countEl = document.getElementById('navMarketsCount');
    if (countEl) countEl.textContent = String(pairs.length);

    renderMarketHighlights();
    renderMarketsTable();
    if (alertsV2State.marketSubView === 'detail') {
        renderPairDetailHeaderAndTabs();
    }
}

function renderMarketHighlights() {
    const rates = window.liveRates || {};
    const pairs = Object.keys(rates);

    const moversEl = document.getElementById('mktHighlightMovers');
    if (moversEl) {
        const topMovers = [...pairs]
            .sort((a, b) => Math.abs(rates[b].changePercent) - Math.abs(rates[a].changePercent))
            .slice(0, 3);
        moversEl.innerHTML = topMovers.length === 0
            ? '<div class="muted-copy">Waiting for live rates...</div>'
            : topMovers.map(pair => {
                const pct = rates[pair].changePercent;
                return `<button type="button" class="mkt-highlight-row" onclick="openMarketDetail('${escapeHtml(pair)}')">
                    <span><strong>${escapeHtml(pair)}</strong> <span class="muted-copy">${escapeHtml(pairMeta(pair).name)}</span></span>
                    <span class="${pct >= 0 ? 'positive' : 'negative'}">${formatPct(pct)}</span>
                </button>`;
            }).join('');
    }

    const favCountEl = document.getElementById('mktHighlightFavCount');
    if (favCountEl) favCountEl.textContent = `${alertsV2State.favorites.size} pinned`;

    const favsEl = document.getElementById('mktHighlightFavs');
    if (favsEl) {
        const favs = Array.from(alertsV2State.favorites).slice(0, 3);
        favsEl.innerHTML = favs.length === 0
            ? '<div class="muted-copy">Click ☆ on any market below to pin favorites.</div>'
            : favs.map(pair => {
                const quote = rates[pair];
                const pctHtml = quote ? `<span class="${quote.changePercent >= 0 ? 'positive' : 'negative'}">${formatPct(quote.changePercent)}</span>` : '';
                return `<button type="button" class="mkt-highlight-row" onclick="openMarketDetail('${escapeHtml(pair)}')">
                    <span>★ <strong>${escapeHtml(pair)}</strong> <span class="muted-copy">${escapeHtml(pairMeta(pair).cat)}</span></span>
                    ${pctHtml}
                </button>`;
            }).join('');
    }

    const alertsEl = document.getElementById('mktHighlightAlerts');
    if (alertsEl) {
        const armed = alertsV2State.rules.filter(r => r.status === 'ARMED').slice(0, 3);
        alertsEl.innerHTML = armed.length === 0
            ? '<div class="muted-copy">No armed alert rules yet.</div>'
            : armed.map(rule => `<button type="button" class="mkt-highlight-row" onclick="openMarketDetail('${escapeHtml(rule.pair)}')">
                <span><strong>${escapeHtml(rule.pair)}</strong> <span class="muted-copy">${escapeHtml(rule.nickname || formatConditionSummary(rule))}</span></span>
                <span class="app-mono-muted">${escapeHtml(formatRuleDistance(rule))}</span>
            </button>`).join('');
    }
}

function renderMarketsTable() {
    const tbody = document.getElementById('marketsTableBody');
    if (!tbody) return;
    const rates = window.liveRates || {};
    const q = String(document.getElementById('mktTableFilter')?.value || '').trim().toUpperCase();
    const cat = alertsV2State.marketCategory;

    const pairs = getAvailablePairs().filter(pair => {
        const meta = pairMeta(pair);
        if (cat === 'fav' && !alertsV2State.favorites.has(pair)) return false;
        if (cat !== 'all' && cat !== 'fav' && meta.cat !== cat) return false;
        if (q && !pair.includes(q) && !meta.name.toUpperCase().includes(q)) return false;
        return true;
    });

    if (pairs.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" class="loading">No markets match this filter.</td></tr>';
        return;
    }

    tbody.innerHTML = pairs.map(pair => {
        const meta = pairMeta(pair);
        const quote = rates[pair];
        const isFav = alertsV2State.favorites.has(pair);
        const armedCount = alertsV2State.rules.filter(r => r.pair === pair && r.status === 'ARMED').length;
        const moveBadge = quote
            ? `<span class="mkt-badge ${quote.changePercent >= 0 ? 'mkt-badge-up' : 'mkt-badge-down'}">${formatPct(quote.changePercent)}</span>`
            : '<span class="mkt-badge mkt-badge-muted">—</span>';
        const alertBadge = armedCount > 0
            ? `<span class="mkt-badge mkt-badge-primary">${armedCount} armed</span>`
            : '<span class="muted-copy">—</span>';

        return `<tr data-market-row="${escapeHtml(pair)}">
            <td>
                <button type="button" class="mkt-star-btn ${isFav ? '' : 'unstarred'}" data-fav-toggle="${escapeHtml(pair)}" aria-label="Toggle favorite">${isFav ? '★' : '☆'}</button>
            </td>
            <td>
                <div class="mkt-pair-main">${escapeHtml(pair)}</div>
                <div class="mkt-pair-sub">${escapeHtml(meta.name)}</div>
            </td>
            <td><span class="mkt-badge mkt-badge-muted">${escapeHtml(meta.cat)}</span></td>
            <td>${moveBadge}</td>
            <td>${alertBadge}</td>
            <td style="text-align:right;">
                <div class="mkt-row-actions">
                    <button type="button" class="btn-secondary" data-quick-alert="${escapeHtml(pair)}">Alert</button>
                    <button type="button" class="btn-primary" data-open-detail="${escapeHtml(pair)}">Detail →</button>
                </div>
            </td>
        </tr>`;
    }).join('');

    tbody.querySelectorAll('[data-market-row]').forEach(tr => {
        tr.addEventListener('click', evt => {
            if (evt.target.closest('button')) return;
            openMarketDetail(tr.dataset.marketRow);
        });
    });
    tbody.querySelectorAll('[data-open-detail]').forEach(btn => {
        btn.addEventListener('click', () => openMarketDetail(btn.dataset.openDetail));
    });
    tbody.querySelectorAll('[data-fav-toggle]').forEach(btn => {
        btn.addEventListener('click', evt => {
            evt.stopPropagation();
            toggleFavoritePair(btn.dataset.favToggle);
        });
    });
    tbody.querySelectorAll('[data-quick-alert]').forEach(btn => {
        btn.addEventListener('click', evt => {
            evt.stopPropagation();
            openAlertRuleModal(null, btn.dataset.quickAlert);
        });
    });
}

function showMarketsListView() {
    alertsV2State.marketSubView = 'list';
    const listView = document.getElementById('marketsListView');
    const detailView = document.getElementById('marketDetailView');
    if (listView) listView.hidden = false;
    if (detailView) detailView.hidden = true;
    renderMarketsShell();
}

async function openMarketDetail(pair) {
    alertsV2State.marketSubView = 'detail';
    selectedPair = pair;
    window.selectedPair = pair;
    writeDashboardState({ selectedPair: pair });

    const listView = document.getElementById('marketsListView');
    const detailView = document.getElementById('marketDetailView');
    if (listView) listView.hidden = true;
    if (detailView) detailView.hidden = false;

    const selectedEl = document.getElementById('selectedPair');
    if (selectedEl) selectedEl.textContent = pair;

    renderPairDetailHeaderAndTabs();
    await fetchHistoricalData();
}

function renderPairDetailHeaderAndTabs() {
    const pair = selectedPair || getAvailablePairs()[0] || 'EUR/USD';
    const meta = pairMeta(pair);
    const quote = (window.liveRates || {})[pair];

    const selectEl = document.getElementById('detailPairSelect');
    if (selectEl) {
        const pairs = getAvailablePairs();
        selectEl.innerHTML = pairs.map(p => `<option value="${escapeHtml(p)}" ${p === pair ? 'selected' : ''}>${escapeHtml(p)}</option>`).join('');
        selectEl.value = pair;
    }

    const starBtn = document.getElementById('detailStarBtn');
    if (starBtn) {
        const isFav = alertsV2State.favorites.has(pair);
        starBtn.textContent = isFav ? '★' : '☆';
        starBtn.classList.toggle('unstarred', !isFav);
    }

    const catEl = document.getElementById('detailCategoryBadge');
    if (catEl) catEl.textContent = meta.cat;
    const nameEl = document.getElementById('detailAssetName');
    if (nameEl) nameEl.textContent = meta.name;

    const spotEl = document.getElementById('detailSpotPrice');
    const chgEl = document.getElementById('detailChangeText');
    if (quote) {
        if (spotEl) spotEl.textContent = formatRate(quote.rate, pair);
        if (chgEl) {
            const sign = quote.change >= 0 ? '+' : '';
            chgEl.textContent = `${sign}${formatRate(quote.change, pair)} (${formatPct(quote.changePercent)})`;
            chgEl.className = `mkt-stat-val ${quote.change >= 0 ? 'positive' : 'negative'}`;
        }
    } else {
        if (spotEl) spotEl.textContent = '—';
        if (chgEl) chgEl.textContent = '—';
    }

    const pairRules = alertsV2State.rules.filter(r => r.pair === pair);
    const armedCount = pairRules.filter(r => r.status === 'ARMED').length;
    const armedEl = document.getElementById('detailArmedCount');
    if (armedEl) armedEl.textContent = `${armedCount} active`;
    const tabCountEl = document.getElementById('detailTabRuleCount');
    if (tabCountEl) tabCountEl.textContent = String(pairRules.length);

    const priceOverlayCount = pairRules.filter(r =>
        r.status === 'ARMED' && (r.condition_type === 'cross_above' || r.condition_type === 'cross_below')
    ).length;
    const legendEl = document.getElementById('detailOverlayLegend');
    if (legendEl) {
        legendEl.textContent = priceOverlayCount > 0
            ? `Showing ${priceOverlayCount} armed alert target line${priceOverlayCount === 1 ? '' : 's'} on chart`
            : 'No armed price target lines on this pair yet';
    }

    const rulesPanel = document.getElementById('detailPanelRules');
    if (rulesPanel) {
        rulesPanel.innerHTML = pairRules.length === 0
            ? `<div class="muted-copy" style="padding:10px 0;">No alert rules for ${escapeHtml(pair)} yet. Click <strong>+ Set Alert</strong> above.</div>`
            : pairRules.map(renderRuleCardHtml).join('');
        wireRuleRowButtons(rulesPanel);
    }

    const sigPanel = document.getElementById('detailPanelSignals');
    if (sigPanel) {
        const pairSigs = alertsV2State.inbox.filter(a => a.pair === pair);
        sigPanel.innerHTML = pairSigs.length === 0
            ? `<div class="muted-copy" style="padding:10px 0;">No triggered alerts yet for ${escapeHtml(pair)}.</div>`
            : pairSigs.map(renderInboxCardHtml).join('');
        wireInboxCardButtons(sigPanel);
    }
}

function updatePairDetailPeriodStats(numericRates) {
    if (!Array.isArray(numericRates) || numericRates.length === 0) return;
    const high = Math.max(...numericRates);
    const low = Math.min(...numericRates);
    const highEl = document.getElementById('detailPeriodHigh');
    const lowEl = document.getElementById('detailPeriodLow');
    if (highEl) highEl.textContent = formatRate(high, selectedPair);
    if (lowEl) lowEl.textContent = formatRate(low, selectedPair);
}

function getArmedPriceAlertOverlayDatasets(pair, pointCount) {
    if (!pointCount || pointCount < 2) return [];
    return alertsV2State.rules
        .filter(r => r.pair === pair && r.status === 'ARMED'
            && (r.condition_type === 'cross_above' || r.condition_type === 'cross_below')
            && Number.isFinite(Number(r.target_value)))
        .map(rule => {
            const isAbove = rule.condition_type === 'cross_above';
            const color = isAbove ? '#34d399' : '#fbbf24';
            return {
                label: `${rule.nickname || rule.condition_type} (${formatRate(rule.target_value, pair)})`,
                data: new Array(pointCount).fill(Number(rule.target_value)),
                borderColor: color,
                borderWidth: 1.5,
                borderDash: [6, 4],
                pointRadius: 0,
                fill: false
            };
        });
}

function renderAlertsV2Workspace() {
    const emailInput = document.getElementById('alertsPersonalEmail');
    if (emailInput && document.activeElement !== emailInput) {
        emailInput.value = alertsV2State.alertEmail;
    }

    const countEl = document.getElementById('alertsV2RuleCount');
    if (countEl) countEl.textContent = `${alertsV2State.rules.length} rule${alertsV2State.rules.length === 1 ? '' : 's'}`;

    const rulesList = document.getElementById('alertsV2RulesList');
    if (rulesList) {
        rulesList.innerHTML = alertsV2State.rules.length === 0
            ? '<div class="muted-copy" style="padding:16px 0;">You have no alert rules yet. Click <strong>+ New Alert Rule</strong> to create one.</div>'
            : alertsV2State.rules.map(renderRuleCardHtml).join('');
        wireRuleRowButtons(rulesList);
    }

    renderAlertsV2Inbox();
}

function renderAlertsV2Inbox() {
    const inboxList = document.getElementById('alertsV2InboxList');
    if (!inboxList) return;
    const items = alertsV2State.inbox.filter(item =>
        alertsV2State.inboxFilter === 'unread' ? !item.read_at : true
    );
    inboxList.innerHTML = items.length === 0
        ? '<div class="muted-copy" style="padding:16px 0;">No alerts in this view.</div>'
        : items.map(renderInboxCardHtml).join('');
    wireInboxCardButtons(inboxList);
}

function renderRuleCardHtml(rule) {
    const toggleLabel = rule.status === 'ARMED' ? 'Pause' : 'Re-arm';
    return `<div class="rule-row-card">
        <div>
            <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
                <button type="button" class="app-link-btn" data-rule-open-pair="${escapeHtml(rule.pair)}">${escapeHtml(rule.pair)}</button>
                <strong>${escapeHtml(rule.nickname || rule.pair)}</strong>
                ${formatRuleStatusBadge(rule)}
            </div>
            <div class="rule-row-meta">
                <span>${escapeHtml(formatConditionSummary(rule))}</span>
                <span>· ${escapeHtml(formatFrequencySummary(rule))}</span>
                <span>· ${rule.notify_email ? 'In-App + Email' : 'In-App only'}</span>
            </div>
        </div>
        <div class="rule-row-actions">
            <span class="app-mono-muted" style="margin-right:4px;">${escapeHtml(formatRuleDistance(rule))}</span>
            <button type="button" class="btn-secondary" data-rule-edit="${escapeHtml(rule.id)}">Edit</button>
            <button type="button" class="btn-secondary" data-rule-toggle="${escapeHtml(rule.id)}">${toggleLabel}</button>
            <button type="button" class="btn-danger" data-rule-delete="${escapeHtml(rule.id)}">Delete</button>
        </div>
    </div>`;
}

function renderInboxCardHtml(item) {
    const unread = !item.read_at;
    return `<div class="signal-card">
        <div class="signal-card-head">
            <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
                <button type="button" class="app-link-btn" data-inbox-open-pair="${escapeHtml(item.pair)}">${escapeHtml(item.pair)}</button>
                <span class="mkt-badge mkt-badge-muted">${escapeHtml(item.rule_name || item.alert_type || 'Alert')}</span>
                ${unread ? '<span class="mkt-badge mkt-badge-warn">Unread</span>' : '<span class="mkt-badge mkt-badge-up">Read</span>'}
            </div>
            <span class="muted-copy">${escapeHtml(formatShortTimestamp(item.timestamp))}</span>
        </div>
        <div>
            <strong class="${Number(item.percent_change) >= 0 ? 'positive' : 'negative'}">${formatPct(item.percent_change)}</strong>
            <span class="muted-copy"> · ${formatRate(item.old_rate, item.pair)} → ${formatRate(item.new_rate, item.pair)}</span>
        </div>
        <div class="signal-card-foot">
            <span class="muted-copy">${item.email_sent ? 'In-App · Email delivered' : 'In-App'}</span>
            <div style="display:flex; gap:6px;">
                ${unread ? `<button type="button" class="btn-secondary" data-inbox-read="${item.id}">Mark Read</button>` : ''}
                <button type="button" class="btn-primary" data-inbox-ask="${item.id}">Ask AI</button>
            </div>
        </div>
    </div>`;
}

function wireRuleRowButtons(container) {
    container.querySelectorAll('[data-rule-open-pair]').forEach(btn => {
        btn.addEventListener('click', () => {
            switchPage('overview');
            openMarketDetail(btn.dataset.ruleOpenPair);
        });
    });
    container.querySelectorAll('[data-rule-edit]').forEach(btn => {
        btn.addEventListener('click', () => {
            const rule = alertsV2State.rules.find(r => r.id === btn.dataset.ruleEdit);
            if (rule) openAlertRuleModal(rule);
        });
    });
    container.querySelectorAll('[data-rule-toggle]').forEach(btn => {
        btn.addEventListener('click', () => toggleRuleStatus(btn.dataset.ruleToggle));
    });
    container.querySelectorAll('[data-rule-delete]').forEach(btn => {
        btn.addEventListener('click', () => deleteRuleById(btn.dataset.ruleDelete));
    });
}

function wireInboxCardButtons(container) {
    container.querySelectorAll('[data-inbox-open-pair]').forEach(btn => {
        btn.addEventListener('click', () => {
            switchPage('overview');
            openMarketDetail(btn.dataset.inboxOpenPair);
        });
    });
    container.querySelectorAll('[data-inbox-read]').forEach(btn => {
        btn.addEventListener('click', () => markSingleInboxRead(Number(btn.dataset.inboxRead)));
    });
    container.querySelectorAll('[data-inbox-ask]').forEach(btn => {
        btn.addEventListener('click', () => {
            const item = alertsV2State.inbox.find(a => a.id === Number(btn.dataset.inboxAsk));
            if (item) askAiAboutAlert(item);
        });
    });
}

async function toggleFavoritePair(pair) {
    if (!pair) return;
    const next = new Set(alertsV2State.favorites);
    if (next.has(pair)) next.delete(pair); else next.add(pair);
    alertsV2State.favorites = next;
    renderMarketsShell();

    const res = await apiRequestWithError('/api/alert-inbox/profile', {
        method: 'PUT',
        body: JSON.stringify({ favorites: Array.from(next) })
    });
    if (!res.ok) {
        showToast(res.error || 'Unable to save favorites', 'error');
        await refreshAlertsV2();
    }
}

async function savePersonalAlertEmail() {
    const email = String(document.getElementById('alertsPersonalEmail')?.value || '').trim();
    const res = await apiRequestWithError('/api/alert-inbox/profile', {
        method: 'PUT',
        body: JSON.stringify({ alert_email: email })
    });
    if (res.ok) {
        alertsV2State.alertEmail = res.data.alert_email || '';
        setInlineBanner('alertsV2Notice', 'Personal alert email saved.', 'success');
        showToast('Personal alert email saved', 'success');
    } else {
        setInlineBanner('alertsV2Notice', res.error || 'Failed to save email.', 'error');
    }
}

async function sendPersonalTestEmail() {
    setInlineBanner('alertsV2Notice', 'Sending test email to your personal address...', 'info');
    const res = await apiRequestWithError('/api/alert-inbox/test-email', { method: 'POST' });
    if (res.ok && res.data?.success) {
        setInlineBanner('alertsV2Notice', res.data.message || 'Test email sent.', 'success');
        showToast('Personal test email sent!', 'success');
    } else {
        const msg = res.data?.error || res.error || 'Could not send test email.';
        setInlineBanner('alertsV2Notice', msg, 'error');
    }
}

async function toggleRuleStatus(ruleId) {
    const rule = alertsV2State.rules.find(r => r.id === ruleId);
    if (!rule) return;
    const nextStatus = rule.status === 'ARMED' ? 'PAUSED' : 'ARMED';
    const res = await apiRequestWithError(`/api/alert-rules/${encodeURIComponent(ruleId)}`, {
        method: 'PUT',
        body: JSON.stringify({ status: nextStatus })
    });
    if (res.ok) {
        showToast(nextStatus === 'ARMED' ? 'Rule re-armed' : 'Rule paused', 'success');
        await refreshAlertsV2();
    } else {
        showToast(res.error || 'Failed to update rule', 'error');
    }
}

async function deleteRuleById(ruleId) {
    const res = await apiRequestWithError(`/api/alert-rules/${encodeURIComponent(ruleId)}`, {
        method: 'DELETE'
    });
    if (res.ok) {
        showToast('Alert rule deleted', 'success');
        await refreshAlertsV2();
    } else {
        showToast(res.error || 'Failed to delete rule', 'error');
    }
}

async function markSingleInboxRead(alertId) {
    const res = await apiRequestWithError(`/api/alert-inbox/${alertId}/read`, { method: 'POST' });
    if (res.ok) {
        await refreshAlertsV2();
    }
}

async function markAllInboxRead() {
    const res = await apiRequestWithError('/api/alert-inbox/read-all', { method: 'POST' });
    if (res.ok) {
        showToast('All alerts marked as read', 'success');
        await refreshAlertsV2();
    }
}

async function clearPersonalInbox() {
    if (!confirm('Clear your personal alert history?')) return;
    const res = await apiRequestWithError('/api/alert-inbox/clear', { method: 'DELETE' });
    if (res.ok) {
        showToast('Personal alert history cleared', 'success');
        await refreshAlertsV2();
    }
}

function askAiAboutPair(pair) {
    if (typeof window.openAiChatWithPrompt !== 'function') {
        showToast('AI assistant is not enabled on this server.', 'info');
        return;
    }
    const quote = (window.liveRates || {})[pair];
    const spotText = quote ? ` (live spot ${formatRate(quote.rate, pair)}, 24h ${formatPct(quote.changePercent)})` : '';
    window.openAiChatWithPrompt(
        `Analyze ${pair}${spotText} using recent price history and any relevant notes in our knowledge base. What key levels should I watch for alerts?`
    );
}

function askAiAboutAlert(item) {
    if (typeof window.openAiChatWithPrompt !== 'function') {
        showToast('AI assistant is not enabled on this server.', 'info');
        return;
    }
    window.openAiChatWithPrompt(
        `My alert "${item.rule_name || item.alert_type}" just triggered on ${item.pair}: moved ${formatPct(item.percent_change)} (${formatRate(item.old_rate, item.pair)} -> ${formatRate(item.new_rate, item.pair)}). Explain why this move matters using live data and relevant knowledge notes.`
    );
}

function openBacktestForSelectedPair() {
    switchPage('backtest');
    const btPairEl = document.getElementById('btPair');
    if (btPairEl && selectedPair) {
        btPairEl.value = selectedPair;
    }
}

// =========================================================================
// GLOBAL POPUP ALERT RULE BUILDER MODAL
// =========================================================================
async function openAlertRuleModal(ruleToEdit = null, presetPair = null) {
    const modal = document.getElementById('alertRuleModal');
    if (!modal) return;

    alertsV2State.editingRuleId = ruleToEdit ? ruleToEdit.id : null;
    hideInlineBanner('alertModalNotice');

    const pairSelect = document.getElementById('alertModalPair');
    const pairs = getAvailablePairs();
    pairSelect.innerHTML = pairs.map(p => `<option value="${escapeHtml(p)}">${escapeHtml(p)} — ${escapeHtml(pairMeta(p).name)}</option>`).join('');

    const targetPair = ruleToEdit ? ruleToEdit.pair : (presetPair || selectedPair || pairs[0] || 'EUR/USD');
    if (!pairs.includes(targetPair)) {
        const opt = document.createElement('option');
        opt.value = targetPair;
        opt.textContent = targetPair;
        pairSelect.appendChild(opt);
    }
    pairSelect.value = targetPair;

    document.getElementById('alertModalEmailHint').textContent = alertsV2State.alertEmail || 'set in Alerts';
    const deleteBtn = document.getElementById('alertModalDeleteBtn');

    if (ruleToEdit) {
        document.getElementById('alertModalTitle').textContent = 'Edit Alert Rule';
        document.getElementById('alertModalSaveBtn').textContent = 'Save Changes';
        deleteBtn.hidden = false;
        document.getElementById('alertModalCondition').value = ruleToEdit.condition_type || 'cross_above';
        document.getElementById('alertModalNickname').value = ruleToEdit.nickname || '';
        document.getElementById('alertModalWindow').value = String(ruleToEdit.window_days || 30);
        document.getElementById('alertModalMaShort').value = String(ruleToEdit.ma_short_period || 20);
        document.getElementById('alertModalMaLong').value = String(ruleToEdit.ma_long_period || 50);
        document.getElementById('alertModalSignalType').value = ruleToEdit.signal_type || 'both';
        document.getElementById('alertModalNotifyEmail').checked = ruleToEdit.notify_email !== false;
        const freqKey = ruleToEdit.frequency === 'RECURRING'
            ? `RECURRING:${pickNearestCooldown(ruleToEdit.cooldown_seconds)}`
            : 'ONCE:3600';
        document.getElementById('alertModalFrequency').value = freqKey;
    } else {
        document.getElementById('alertModalTitle').textContent = `New Alert Rule · ${targetPair}`;
        document.getElementById('alertModalSaveBtn').textContent = 'Create Rule';
        deleteBtn.hidden = true;
        document.getElementById('alertModalCondition').value = 'cross_above';
        document.getElementById('alertModalNickname').value = '';
        document.getElementById('alertModalWindow').value = '30';
        document.getElementById('alertModalMaShort').value = '20';
        document.getElementById('alertModalMaLong').value = '50';
        document.getElementById('alertModalSignalType').value = 'both';
        document.getElementById('alertModalFrequency').value = 'ONCE:3600';
        document.getElementById('alertModalNotifyEmail').checked = true;
    }

    modal.hidden = false;
    await loadModalSparkline(targetPair);

    if (ruleToEdit && ruleToEdit.target_value !== null && ruleToEdit.target_value !== undefined) {
        const cond = ruleToEdit.condition_type;
        document.getElementById('alertModalTargetNum').value = cond === 'pct_move'
            ? Number(ruleToEdit.target_value).toFixed(1)
            : Number(ruleToEdit.target_value).toFixed(pairMeta(targetPair).dec);
        syncSliderFromTargetInput();
    } else {
        seedModalDefaultTarget(targetPair, document.getElementById('alertModalCondition').value);
    }

    updateModalPreview();
}

function closeAlertRuleModal() {
    const modal = document.getElementById('alertRuleModal');
    if (modal) modal.hidden = true;
    alertsV2State.editingRuleId = null;
}

function pickNearestCooldown(seconds) {
    const sec = Number(seconds || 3600);
    if (sec >= 43200) return 86400;
    if (sec >= 7200) return 14400;
    return 3600;
}

async function loadModalSparkline(pair) {
    const quote = (window.liveRates || {})[pair];
    const spot = quote ? quote.rate : 1;
    let series = [];
    try {
        const data = await fetchAPI(`/api/historical?pair=${encodeURIComponent(pair)}&days=30`);
        if (Array.isArray(data)) {
            series = data.map(p => Number(p.rate)).filter(v => Number.isFinite(v) && v > 0);
        }
    } catch (_) {
        series = [];
    }
    if (series.length < 2) {
        series = [spot * 0.97, spot * 0.99, spot * 1.01, spot];
    }
    alertsV2State.modalHistory = series;
    const rawMin = Math.min(...series, spot);
    const rawMax = Math.max(...series, spot);
    const pad = Math.max((rawMax - rawMin) * 0.2, spot * 0.03);
    alertsV2State.modalMin = Math.max(0.0001, rawMin - pad);
    alertsV2State.modalMax = rawMax + pad;
}

function seedModalDefaultTarget(pair, cond) {
    const quote = (window.liveRates || {})[pair];
    const spot = quote ? quote.rate : (alertsV2State.modalHistory.at(-1) || 1);
    const numInput = document.getElementById('alertModalTargetNum');
    if (cond === 'pct_move') {
        numInput.value = '3.0';
    } else if (cond === 'cross_below') {
        numInput.value = (spot * 0.98).toFixed(pairMeta(pair).dec);
    } else {
        numInput.value = (spot * 1.02).toFixed(pairMeta(pair).dec);
    }
    syncSliderFromTargetInput();
}

function syncSliderFromTargetInput() {
    const cond = document.getElementById('alertModalCondition').value;
    const val = Number(document.getElementById('alertModalTargetNum').value);
    const slider = document.getElementById('alertModalTargetSlider');
    if (!Number.isFinite(val)) return;
    if (cond === 'pct_move') {
        slider.value = String(Math.max(0, Math.min(100, Math.round(((val - 0.5) / 14.5) * 100))));
    } else {
        const span = Math.max(0.00001, alertsV2State.modalMax - alertsV2State.modalMin);
        slider.value = String(Math.max(0, Math.min(100, Math.round(((val - alertsV2State.modalMin) / span) * 100))));
    }
}

function updateModalPreview() {
    const pair = document.getElementById('alertModalPair').value;
    const cond = document.getElementById('alertModalCondition').value;
    const targetVal = Number(document.getElementById('alertModalTargetNum').value);
    const quote = (window.liveRates || {})[pair];
    const spot = quote ? quote.rate : (alertsV2State.modalHistory.at(-1) || 0);
    const isPrice = cond === 'cross_above' || cond === 'cross_below';
    const isPct = cond === 'pct_move';
    const isMa = cond === 'ma_crossover';

    document.getElementById('alertModalTargetRow').hidden = isMa;
    document.getElementById('alertModalExtraRow').hidden = isPrice;
    document.getElementById('alertModalWindowWrap').hidden = !isPct;
    document.getElementById('alertModalMaShortWrap').hidden = !isMa;
    document.getElementById('alertModalMaLongWrap').hidden = !isMa;
    document.getElementById('alertModalSignalWrap').hidden = !isMa;

    document.getElementById('alertModalPreviewPair').textContent = pair;
    document.getElementById('alertModalSpot').textContent = spot > 0 ? formatRate(spot, pair) : '—';
    document.getElementById('alertModalRangeText').textContent = alertsV2State.modalHistory.length >= 2
        ? ` · 30d: ${formatRate(Math.min(...alertsV2State.modalHistory), pair)} – ${formatRate(Math.max(...alertsV2State.modalHistory), pair)}`
        : '';

    const freqVal = document.getElementById('alertModalFrequency').value;
    const freqNote = freqVal.startsWith('ONCE') ? 'then automatically turns off.' : 'with your selected cooldown.';

    if (isPrice && Number.isFinite(targetVal) && spot > 0) {
        applyPricePreviewState(pair, cond, targetVal, spot, freqNote);
        return;
    }
    if (isPct) {
        applyPercentPreviewState(pair, targetVal, spot, freqNote);
        return;
    }
    applyMaPreviewState(pair, spot, freqNote);
}

function applyPricePreviewState(pair, cond, targetVal, spot, freqNote) {
    const isBelow = cond === 'cross_below';
    const diff = targetVal - spot;
    const pct = (diff / spot) * 100;
    const warn = isBelow ? spot <= targetVal : spot >= targetVal;
    const directionText = isBelow ? 'below' : 'above';

    document.getElementById('alertModalTargetLabel').textContent = isBelow ? 'Target Price (Below)' : 'Target Price (Above)';
    document.getElementById('alertModalDistance').textContent = `Target ${diff >= 0 ? '+' : ''}${formatRate(diff, pair)} (${formatPct(pct)})`;
    document.getElementById('alertModalSliderPct').textContent = `${formatPct(pct)} from spot`;

    const badge = document.getElementById('alertModalBadge');
    badge.className = `status-pill ${warn ? 'status-pill-warning' : 'status-pill-success'}`;
    badge.textContent = warn ? 'Already past target' : `${Math.abs(pct).toFixed(2)}% away`;

    document.getElementById('alertModalHint').textContent = warn
        ? `Spot (${formatRate(spot, pair)}) is already past ${formatRate(targetVal, pair)}. It will wait for a fresh crossing.`
        : `Fires when ${pair} crosses ${directionText} ${formatRate(targetVal, pair)}, ${freqNote}`;

    drawModalSparklineCanvas(pair, targetVal, warn);
}

function applyPercentPreviewState(pair, targetVal, spot, freqNote) {
    const p = Number.isFinite(targetVal) ? targetVal : 3.0;
    const windowDays = document.getElementById('alertModalWindow').value;
    document.getElementById('alertModalTargetLabel').textContent = 'Move Threshold (%)';
    document.getElementById('alertModalDistance').textContent = `${windowDays}d move ≥ ${p.toFixed(1)}%`;
    document.getElementById('alertModalSliderPct').textContent = `±${p.toFixed(1)}%`;

    const badge = document.getElementById('alertModalBadge');
    badge.className = 'status-pill status-pill-success';
    badge.textContent = `≥ ${p.toFixed(1)}% move`;
    document.getElementById('alertModalHint').textContent = `Fires when ${pair} moves at least ${p.toFixed(1)}% over ${windowDays} days, ${freqNote}`;

    drawModalSparklineCanvas(pair, spot * (1 + p / 100), false);
}

function applyMaPreviewState(pair, spot, freqNote) {
    const maS = document.getElementById('alertModalMaShort').value;
    const maL = document.getElementById('alertModalMaLong').value;
    document.getElementById('alertModalDistance').textContent = `MA${maS} / MA${maL} crossover`;
    const badge = document.getElementById('alertModalBadge');
    badge.className = 'status-pill status-pill-success';
    badge.textContent = 'MA Crossover';
    document.getElementById('alertModalHint').textContent = `Fires when MA${maS} crosses MA${maL} on ${pair}, ${freqNote}`;
    drawModalSparklineCanvas(pair, spot, false);
}

function drawModalSparklineCanvas(pair, targetPrice, warn) {
    const canvas = document.getElementById('alertModalCanvas');
    if (!canvas) return;
    const series = alertsV2State.modalHistory;
    if (!series || series.length < 2) return;

    const dpr = window.devicePixelRatio || 1;
    const width = canvas.clientWidth || 560;
    const height = 120;
    canvas.width = width * dpr;
    canvas.height = height * dpr;
    const ctx = canvas.getContext('2d');
    ctx.scale(dpr, dpr);

    const pad = { top: 12, right: 68, bottom: 12, left: 8 };
    const plotW = width - pad.left - pad.right;
    const plotH = height - pad.top - pad.bottom;
    const minV = Math.min(alertsV2State.modalMin, targetPrice);
    const maxV = Math.max(alertsV2State.modalMax, targetPrice);
    const span = Math.max(0.00001, maxV - minV);
    const xFor = i => pad.left + (i / (series.length - 1)) * plotW;
    const yFor = v => pad.top + (1 - (v - minV) / span) * plotH;

    ctx.clearRect(0, 0, width, height);
    ctx.strokeStyle = 'rgba(148, 163, 184, 0.16)';
    ctx.lineWidth = 1;
    for (let g = 0; g <= 2; g++) {
        const y = pad.top + (g / 2) * plotH;
        ctx.beginPath(); ctx.moveTo(pad.left, y); ctx.lineTo(width - pad.right, y); ctx.stroke();
    }

    ctx.beginPath();
    series.forEach((v, i) => {
        const x = xFor(i);
        const y = yFor(v);
        if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    });
    ctx.strokeStyle = '#60a5fa';
    ctx.lineWidth = 2;
    ctx.stroke();

    const ty = Math.max(pad.top, Math.min(pad.top + plotH, yFor(targetPrice)));
    ctx.save();
    ctx.setLineDash([5, 4]);
    ctx.strokeStyle = warn ? '#fbbf24' : '#34d399';
    ctx.lineWidth = 1.6;
    ctx.beginPath(); ctx.moveTo(pad.left, ty); ctx.lineTo(width - pad.right, ty); ctx.stroke();
    ctx.restore();

    ctx.font = '11px ui-monospace, SFMono-Regular, Menlo, monospace';
    ctx.fillStyle = warn ? '#fbbf24' : '#34d399';
    ctx.fillText(formatRate(targetPrice, pair), width - pad.right + 6, ty + 4);
}

async function saveAlertRuleFromModal() {
    const cond = document.getElementById('alertModalCondition').value;
    const [frequency, cooldownStr] = document.getElementById('alertModalFrequency').value.split(':');
    const payload = {
        pair: document.getElementById('alertModalPair').value,
        nickname: document.getElementById('alertModalNickname').value.trim(),
        condition_type: cond,
        target_value: cond === 'ma_crossover' ? null : Number(document.getElementById('alertModalTargetNum').value),
        window_days: Number(document.getElementById('alertModalWindow').value) || 30,
        ma_short_period: Number(document.getElementById('alertModalMaShort').value) || 20,
        ma_long_period: Number(document.getElementById('alertModalMaLong').value) || 50,
        signal_type: document.getElementById('alertModalSignalType').value || 'both',
        frequency,
        cooldown_seconds: Number(cooldownStr) || 3600,
        notify_email: !!document.getElementById('alertModalNotifyEmail').checked
    };

    const editingId = alertsV2State.editingRuleId;
    const endpoint = editingId ? `/api/alert-rules/${encodeURIComponent(editingId)}` : '/api/alert-rules';
    const method = editingId ? 'PUT' : 'POST';

    const res = await apiRequestWithError(endpoint, { method, body: JSON.stringify(payload) });
    if (!res.ok) {
        setInlineBanner('alertModalNotice', res.error || 'Could not save alert rule.', 'error');
        return;
    }

    closeAlertRuleModal();
    showToast(editingId ? 'Alert rule updated' : 'Alert rule created', 'success');
    await refreshAlertsV2();
}

async function deleteEditingRuleFromModal() {
    const editingId = alertsV2State.editingRuleId;
    if (!editingId) return;
    closeAlertRuleModal();
    await deleteRuleById(editingId);
}

function formatShortTimestamp(isoString) {
    if (!isoString) return '—';
    const date = new Date(isoString);
    if (Number.isNaN(date.getTime())) return isoString;
    return date.toLocaleString('en-US', {
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit'
    });
}

// 5-Stage Master Pipeline & Live Master Target Sheet (Milestone 2)

let _mpCatalog = null;
let _mpSelectedPairs = new Set(['EUR/USD', 'GBP/USD', 'USD/JPY', 'AUD/USD', 'GOLD/USD']);
let _mpLastResult = null;
let _mpChart = null;
let _mpInitialized = false;
let _liveTargetsTimer = null;

function _mpEsc(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function _mpFmtUsd(value) {
    const num = Number(value);
    if (!Number.isFinite(num)) return '—';
    return `${num < 0 ? '-' : ''}$${Math.abs(num).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function _mpFmtSignedUsd(value) {
    const num = Number(value);
    if (!Number.isFinite(num)) return '—';
    return `${num >= 0 ? '+' : '-'}$${Math.abs(num).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function _mpFmtPct(value, digits = 2) {
    const num = Number(value);
    if (!Number.isFinite(num)) return '—';
    return `${num >= 0 ? '+' : ''}${num.toFixed(digits)}%`;
}

async function _mpFetchJson(endpoint, options = {}) {
    try {
        const method = (options.method || 'GET').toUpperCase();
        const headers = {
            'Content-Type': 'application/json',
            ...(options.headers || {})
        };
        if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && typeof getCsrfToken === 'function') {
            const csrfToken = await getCsrfToken();
            if (csrfToken) {
                headers['X-XSRF-TOKEN'] = csrfToken;
            }
        }
        const response = await fetch(`${API_BASE}${endpoint}`, {
            ...options,
            headers,
            credentials: 'include'
        });
        if (response.status === 401) {
            if (window.location.pathname !== '/login.html') {
                window.location.href = '/login.html';
            }
            return null;
        }
        const contentType = response.headers.get('content-type') || '';
        if (contentType.includes('application/json')) {
            const body = await response.json();
            if (!response.ok && body && body.success === undefined) {
                body.success = false;
            }
            return body;
        }
        return response.ok ? {} : { success: false, error: `Request failed (${response.status})` };
    } catch (err) {
        return { success: false, error: err?.message || 'Network error' };
    }
}

async function initMasterPipelinePage() {
    const profileSel = document.getElementById('mpProfileSelect');
    if (!profileSel) return;

    if (!_mpCatalog) {
        const catalog = await _mpFetchJson('/api/pipeline/profiles');
        if (catalog && catalog.success) {
            _mpCatalog = catalog;
            if (Array.isArray(catalog.default_pairs) && catalog.default_pairs.length) {
                _mpSelectedPairs = new Set(catalog.default_pairs);
            }
        }
    }

    _renderMpProfileOptions('mpProfileSelect');
    _renderMpPairPills();

    if (!_mpInitialized) {
        _mpInitialized = true;
        const traceFilter = document.getElementById('mpTracePairFilter');
        if (traceFilter) {
            traceFilter.addEventListener('change', _renderMpTraceRows);
        }
    }
    await loadRecentMasterRuns();
}

function _renderMpProfileOptions(selectId) {
    const sel = document.getElementById(selectId);
    if (!sel) return;
    const profiles = (_mpCatalog && Array.isArray(_mpCatalog.profiles))
        ? _mpCatalog.profiles
        : [
            { id: 'carver-aqr-spine', display_name: 'Carver / AQR Trend + Carry Spine' },
            { id: 'carver-guarded-overlay', display_name: 'Carver / AQR Spine + Drawdown/Vol Throttle' },
            { id: 'turtle-donchian', display_name: 'Turtle / Parker Donchian Breakout (20/55)' },
            { id: 'druckenmiller-rate-impulse', display_name: 'Druckenmiller Policy-Impulse + SMA-200' }
        ];
    const prev = sel.value || (_mpCatalog?.default_profile_id || 'carver-aqr-spine');
    sel.innerHTML = profiles.map(p =>
        `<option value="${_mpEsc(p.id)}">${_mpEsc(p.display_name)}</option>`
    ).join('');
    if ([...sel.options].some(o => o.value === prev)) {
        sel.value = prev;
    }
}

function _renderMpPairPills() {
    const wrap = document.getElementById('mpPairPills');
    if (!wrap) return;
    const pairs = (_mpCatalog && Array.isArray(_mpCatalog.supported_pairs))
        ? _mpCatalog.supported_pairs
        : ['EUR/USD', 'GBP/USD', 'USD/JPY', 'USD/CHF', 'AUD/USD', 'USD/CAD', 'NZD/USD', 'GOLD/USD', 'SILVER/USD'];

    wrap.innerHTML = pairs.map(pair => {
        const active = _mpSelectedPairs.has(pair);
        return `<button type="button" class="mkt-pill ${active ? 'active' : ''}" onclick="toggleMasterPipelinePair('${_mpEsc(pair)}')">${_mpEsc(pair)}</button>`;
    }).join('');
}

function toggleMasterPipelinePair(pair) {
    if (_mpSelectedPairs.has(pair)) {
        if (_mpSelectedPairs.size <= 1) {
            showToast('Keep at least one pair selected', 'error');
            return;
        }
        _mpSelectedPairs.delete(pair);
    } else {
        _mpSelectedPairs.add(pair);
    }
    _renderMpPairPills();
}

function selectAllMasterPipelinePairs(preset) {
    const allPairs = (_mpCatalog && Array.isArray(_mpCatalog.supported_pairs))
        ? _mpCatalog.supported_pairs
        : ['EUR/USD', 'GBP/USD', 'USD/JPY', 'USD/CHF', 'AUD/USD', 'USD/CAD', 'NZD/USD', 'GOLD/USD', 'SILVER/USD'];
    if (preset === 'core') {
        _mpSelectedPairs = new Set(['EUR/USD', 'GBP/USD', 'USD/JPY', 'AUD/USD', 'GOLD/USD']);
    } else {
        _mpSelectedPairs = new Set(allPairs);
    }
    _renderMpPairPills();
}

async function runMasterPipelineBacktest() {
    const runBtn = document.getElementById('mpRunBtn');
    const resultsWrap = document.getElementById('mpResultsWrap');
    setInlineBanner('mpNotice', 'Syncing NY 5:00 PM aligned bars & running 5-stage Master Arena simulation...', 'info');
    if (runBtn) {
        runBtn.disabled = true;
        runBtn.textContent = 'Running 5-Stage Arena...';
    }

    try {
        const payload = {
            profile_id: document.getElementById('mpProfileSelect')?.value || 'carver-aqr-spine',
            pairs: Array.from(_mpSelectedPairs),
            days: Number(document.getElementById('mpDays')?.value || 1260),
            initial_equity_usd: Number(document.getElementById('mpEquity')?.value || 100000),
            target_volatility_pct: Number(document.getElementById('mpTargetVol')?.value || 10),
            max_currency_exposure_multiple: Number(document.getElementById('mpClusterCap')?.value || 1.5),
            rebalance_inertia_pct: Number(document.getElementById('mpInertiaPct')?.value || 10),
            enable_carry: Boolean(document.getElementById('mpEnableCarry')?.checked),
            include_arena: true
        };

        const result = await _mpFetchJson('/api/pipeline/backtest', {
            method: 'POST',
            body: JSON.stringify(payload)
        });

        if (!result || !result.success) {
            setInlineBanner('mpNotice', result?.error || 'Failed to run 5-stage pipeline simulation.', 'error');
            return;
        }

        _mpLastResult = result;
        if (resultsWrap) resultsWrap.hidden = false;
        _renderMpSummaryAndArena(result);
        _renderMpAttributionChart(result);
        _populateMpTraceFilter(result);
        _renderMpTraceRows();
        await loadRecentMasterRuns();

        setInlineBanner(
            'mpNotice',
            `Completed ${result.profile_name} (${result.start_date} to ${result.end_date}) across ${result.pairs.length} pairs.`,
            'success'
        );
    } finally {
        if (runBtn) {
            runBtn.disabled = false;
            runBtn.textContent = 'Run 5-Stage Master Arena';
        }
    }
}

function _renderMpSummaryAndArena(result) {
    const s = result.summary || {};
    const kpiEl = document.getElementById('mpAttributionKpis');
    if (kpiEl) {
        const card = (label, primary, sub, toneClass = '') => `
            <div class="mp-kpi-card">
                <div class="mp-kpi-label">${_mpEsc(label)}</div>
                <div class="mp-kpi-val ${toneClass}">${primary}</div>
                <div class="mp-kpi-sub">${sub}</div>
            </div>
        `;
        const retTone = (s.total_return_pct ?? 0) >= 0 ? 'mp-pos' : 'mp-neg';
        const spotTone = (s.total_spot_pnl_usd ?? 0) >= 0 ? 'mp-pos' : 'mp-neg';
        const carryTone = (s.total_carry_pnl_usd ?? 0) >= 0 ? 'mp-pos' : 'mp-neg';

        kpiEl.innerHTML = [
            card('Final Equity', _mpFmtUsd(s.final_equity_usd), `Return ${_mpFmtPct(s.total_return_pct)} · CAGR ${_mpFmtPct(s.cagr_pct)}`, retTone),
            card('Net Sharpe / Sortino', `${Number(s.net_sharpe_ratio ?? 0).toFixed(2)} / ${Number(s.sortino_ratio ?? 0).toFixed(2)}`, `Spot-Only Sharpe ${Number(s.spot_only_sharpe_ratio ?? 0).toFixed(2)}`),
            card('Max Drawdown', `${Number(s.max_drawdown_pct ?? 0).toFixed(2)}%`, `95% Daily VaR ${Number(s.var_95_daily_pct ?? 0).toFixed(2)}%`, 'mp-neg'),
            card('Spot Directional P&L', _mpFmtSignedUsd(s.total_spot_pnl_usd), 'Pure price trend attribution', spotTone),
            card('Carry Rollover P&L', _mpFmtSignedUsd(s.total_carry_pnl_usd), 'Net of broker markup & 3x Wed swap', carryTone),
            card('Friction & Inertia', `-${_mpFmtUsd(s.total_friction_usd)}`, `${s.executed_trades_count ?? 0} fills · ${s.inertia_skipped_count ?? 0} inertia skips`)
        ].join('');
    }

    const arenaBody = document.getElementById('mpArenaTableBody');
    if (arenaBody) {
        const rows = Array.isArray(result.arena_comparison) ? result.arena_comparison : [];
        arenaBody.innerHTML = rows.map(item => {
            const m = item.summary || {};
            const isPrimary = item.profile_id === result.profile_id;
            return `
                <tr class="${isPrimary ? 'mp-row-highlight' : ''}">
                    <td>
                        <strong>${_mpEsc(item.display_name)}</strong>
                        ${isPrimary ? '<span class="mp-badge mp-badge-blue" style="margin-left:6px;">Selected</span>' : ''}
                    </td>
                    <td>${Number(m.net_sharpe_ratio ?? 0).toFixed(2)}</td>
                    <td>${Number(m.spot_only_sharpe_ratio ?? 0).toFixed(2)}</td>
                    <td class="${(m.cagr_pct ?? 0) >= 0 ? 'mp-pos' : 'mp-neg'}">${_mpFmtPct(m.cagr_pct)}</td>
                    <td class="mp-neg">${Number(m.max_drawdown_pct ?? 0).toFixed(2)}%</td>
                    <td class="${(m.total_spot_pnl_usd ?? 0) >= 0 ? 'mp-pos' : 'mp-neg'}">${_mpFmtSignedUsd(m.total_spot_pnl_usd)}</td>
                    <td class="${(m.total_carry_pnl_usd ?? 0) >= 0 ? 'mp-pos' : 'mp-neg'}">${_mpFmtSignedUsd(m.total_carry_pnl_usd)}</td>
                    <td>-${_mpFmtUsd(m.total_friction_usd)}</td>
                    <td>${m.executed_trades_count ?? 0} / ${m.inertia_skipped_count ?? 0}</td>
                </tr>
            `;
        }).join('');
    }
}

function _renderMpAttributionChart(result) {
    const canvas = document.getElementById('mpAttributionChart');
    if (!canvas) return;
    if (_mpChart) {
        _mpChart.destroy();
        _mpChart = null;
    }

    const curve = Array.isArray(result.equity_curve) ? result.equity_curve : [];
    if (!curve.length) return;

    const labels = curve.map(pt => pt.date);
    const equityData = curve.map(pt => pt.equity_usd);
    const spotData = curve.map(pt => pt.cum_spot_pnl_usd);
    const carryData = curve.map(pt => pt.cum_carry_pnl_usd);

    _mpChart = new Chart(canvas.getContext('2d'), {
        type: 'line',
        data: {
            labels,
            datasets: [
                {
                    label: 'Net Portfolio Equity (USD)',
                    data: equityData,
                    yAxisID: 'yEquity',
                    borderColor: '#60a5fa',
                    backgroundColor: 'rgba(96, 165, 250, 0.10)',
                    borderWidth: 2,
                    pointRadius: 0,
                    fill: true,
                    tension: 0.15
                },
                {
                    label: 'Cumulative Spot P&L (USD)',
                    data: spotData,
                    yAxisID: 'yPnl',
                    borderColor: '#34d399',
                    borderWidth: 1.8,
                    pointRadius: 0,
                    fill: false,
                    tension: 0.15
                },
                {
                    label: 'Cumulative Carry P&L (USD)',
                    data: carryData,
                    yAxisID: 'yPnl',
                    borderColor: '#fbbf24',
                    borderDash: [5, 4],
                    borderWidth: 1.8,
                    pointRadius: 0,
                    fill: false,
                    tension: 0.15
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            interaction: { mode: 'index', intersect: false },
            plugins: {
                legend: { labels: { color: '#cbd5e1' } }
            },
            scales: {
                x: { ticks: { color: '#94a3b8', maxTicksLimit: 10 }, grid: { color: 'rgba(148,163,184,0.12)' } },
                yEquity: {
                    type: 'linear',
                    position: 'left',
                    ticks: { color: '#93c5fd' },
                    grid: { color: 'rgba(148,163,184,0.12)' }
                },
                yPnl: {
                    type: 'linear',
                    position: 'right',
                    ticks: { color: '#86efac' },
                    grid: { drawOnChartArea: false }
                }
            }
        }
    });
}

function _populateMpTraceFilter(result) {
    const sel = document.getElementById('mpTracePairFilter');
    if (!sel) return;
    const pairs = Array.isArray(result?.pairs) ? result.pairs : [];
    sel.innerHTML = `<option value="ALL">All Pairs (${pairs.length})</option>` +
        pairs.map(p => `<option value="${_mpEsc(p)}">${_mpEsc(p)}</option>`).join('');
}

function _renderMpTraceRows() {
    const tbody = document.getElementById('mpTracesTableBody');
    if (!tbody) return;
    const traces = Array.isArray(_mpLastResult?.recent_traces) ? _mpLastResult.recent_traces : [];
    const filterPair = document.getElementById('mpTracePairFilter')?.value || 'ALL';
    const filtered = filterPair === 'ALL' ? traces : traces.filter(t => t.pair === filterPair);

    if (!filtered.length) {
        tbody.innerHTML = '<tr><td colspan="9" class="loading">No stage traces recorded for this filter.</td></tr>';
        return;
    }

    tbody.innerHTML = filtered.slice(0, 60).map(tr => {
        let guardBadge = '<span class="mp-badge mp-badge-green">Executed</span>';
        if (tr.held_by_inertia) {
            guardBadge = '<span class="mp-badge mp-badge-muted">10% Inertia Hold</span>';
        } else if (tr.capped_by_cluster) {
            guardBadge = '<span class="mp-badge mp-badge-amber">USD Cluster Cap</span>';
        }
        const sig = Number(tr.signal ?? 0);
        const sigCls = sig > 0.05 ? 'mp-pos' : (sig < -0.05 ? 'mp-neg' : '');
        return `
            <tr>
                <td class="app-mono-muted">${_mpEsc(tr.signal_date)} → ${_mpEsc(tr.exec_date)}</td>
                <td><strong>${_mpEsc(tr.pair)}</strong></td>
                <td class="${sigCls}">${sig >= 0 ? '+' : ''}${sig.toFixed(2)}</td>
                <td>${Number(tr.annualized_vol_pct ?? 0).toFixed(1)}%</td>
                <td>${_mpFmtSignedUsd(tr.target_notional_usd)}</td>
                <td>${guardBadge}</td>
                <td class="app-mono-muted">${Number(tr.fill_open_price ?? 0).toFixed(4)}</td>
                <td>${tr.rollover_days}d (${_mpFmtSignedUsd(tr.carry_pnl_usd)})</td>
                <td class="${(tr.spot_pnl_usd ?? 0) >= 0 ? 'mp-pos' : 'mp-neg'}">${_mpFmtSignedUsd(tr.spot_pnl_usd)}</td>
            </tr>
        `;
    }).join('');
}

async function loadRecentMasterRuns() {
    const container = document.getElementById('mpRecentRunsList');
    if (!container) return;
    const res = await _mpFetchJson('/api/pipeline/runs?limit=6');
    if (!res || !res.success || !Array.isArray(res.runs) || !res.runs.length) {
        container.innerHTML = '<div class="muted-copy">No saved simulation runs yet.</div>';
        return;
    }
    container.innerHTML = res.runs.map(run => {
        let summary = {};
        try {
            summary = JSON.parse(run.summary_json || '{}');
        } catch (_) {
            summary = {};
        }
        const ts = run.created_at ? new Date(run.created_at).toLocaleString('en-US') : '—';
        return `
            <div class="mp-run-chip">
                <div>
                    <strong>${_mpEsc(run.profile_id)}</strong>
                    <span class="muted-copy"> · ${_mpEsc(run.start_date)} → ${_mpEsc(run.end_date)}</span>
                </div>
                <div class="app-mono-muted">
                    Sharpe ${Number(summary.net_sharpe_ratio ?? 0).toFixed(2)} · Return ${_mpFmtPct(summary.total_return_pct)} · ${_mpEsc(ts)}
                </div>
            </div>
        `;
    }).join('');
}

// ============================================================================
// Live Master Target Sheet inside Paper Trade Page
// ============================================================================
function scheduleLiveMasterTargetsRefresh() {
    if (_liveTargetsTimer) {
        clearTimeout(_liveTargetsTimer);
    }
    _liveTargetsTimer = setTimeout(() => {
        loadLiveMasterTargets();
    }, 250);
}

async function loadLiveMasterTargets() {
    const tbody = document.getElementById('liveMasterTargetsBody');
    const metaEl = document.getElementById('liveMasterClusterSummary');
    if (!tbody) return;

    if (!_mpCatalog) {
        const catalog = await _mpFetchJson('/api/pipeline/profiles');
        if (catalog && catalog.success) {
            _mpCatalog = catalog;
        }
    }
    _renderMpProfileOptions('liveTargetProfileSelect');

    const equity = Number(document.getElementById('tradeEquity')?.value || 10000);
    const profileId = document.getElementById('liveTargetProfileSelect')?.value || 'carver-aqr-spine';

    const res = await _mpFetchJson(
        `/api/pipeline/targets/live?equity=${encodeURIComponent(equity)}&profile_id=${encodeURIComponent(profileId)}`
    );
    if (!res || !res.success) {
        tbody.innerHTML = `<tr><td colspan="8" class="loading">${_mpEsc(res?.error || 'Live Master targets unavailable.')}</td></tr>`;
        return;
    }

    if (metaEl) {
        metaEl.innerHTML = `
            <span>As of NY Close: <strong>${_mpEsc(res.as_of_date)}</strong></span>
            <span>Regime Throttle: <strong>${Number(res.regime_multiplier ?? 1).toFixed(2)}x</strong></span>
            <span>Net USD Exposure: <strong>${_mpFmtSignedUsd(res.net_usd_exposure_usd)}</strong></span>
            <span>USD Cluster Headroom: <strong>${_mpFmtUsd(res.usd_cluster_headroom_usd)}</strong> / ${_mpFmtUsd(res.max_usd_cluster_cap_usd)}</span>
        `;
    }

    const targets = Array.isArray(res.targets) ? res.targets : [];
    if (!targets.length) {
        tbody.innerHTML = '<tr><td colspan="8" class="loading">No aligned target positions available yet.</td></tr>';
        return;
    }

    tbody.innerHTML = targets.map(row => {
        const sig = Number(row.signal ?? 0);
        const sigCls = sig > 0.05 ? 'mp-pos' : (sig < -0.05 ? 'mp-neg' : '');
        const carry = Number(row.net_carry_annual_pct ?? 0);
        const carryCls = carry > 0 ? 'mp-pos' : (carry < 0 ? 'mp-neg' : '');
        const badge = _alignmentBadgeHtml(row.discretionary_alignment, row.open_discretionary_count);

        return `
            <tr>
                <td><strong>${_mpEsc(row.pair)}</strong></td>
                <td class="app-mono-muted">${Number(row.close ?? 0).toFixed(4)}</td>
                <td class="${sigCls}"><strong>${_mpEsc(row.direction)}</strong> (${sig >= 0 ? '+' : ''}${sig.toFixed(2)})</td>
                <td>${Number(row.annualized_vol_pct ?? 0).toFixed(1)}%</td>
                <td class="${carryCls}">${carry >= 0 ? '+' : ''}${carry.toFixed(2)}%</td>
                <td>
                    <strong>${_mpFmtSignedUsd(row.target_notional_usd)}</strong>
                    ${row.capped_by_cluster ? '<span class="mp-badge mp-badge-amber" style="margin-left:4px;">Capped</span>' : ''}
                </td>
                <td class="app-mono-muted">±${_mpFmtUsd(row.inertia_band_usd)}</td>
                <td>
                    <div style="display:flex; align-items:center; justify-content:space-between; gap:8px;">
                        ${badge}
                        <button type="button" class="btn-secondary app-compact-btn" onclick="applyMasterTargetToCalculator('${_mpEsc(row.pair)}')">Size Pair</button>
                    </div>
                </td>
            </tr>
        `;
    }).join('');
}

function _alignmentBadgeHtml(alignment, openCount) {
    switch (alignment) {
        case 'ALIGNED':
            return `<span class="mp-badge mp-badge-green">Aligned (${openCount})</span>`;
        case 'DIVERGENT':
            return `<span class="mp-badge mp-badge-red">Divergent (${openCount})</span>`;
        case 'UNPOSITIONED':
            return '<span class="mp-badge mp-badge-blue">Unpositioned</span>';
        default:
            return '<span class="mp-badge mp-badge-muted">Flat</span>';
    }
}

async function applyMasterTargetToCalculator(pair) {
    const sel = document.getElementById('tradeAsset');
    if (!sel) return;
    if (![...sel.options].some(o => o.value === pair)) {
        const opt = document.createElement('option');
        opt.value = pair;
        opt.textContent = pair;
        sel.appendChild(opt);
    }
    sel.value = pair;
    if (typeof loadTradeMetrics === 'function') {
        await loadTradeMetrics();
        if (typeof computePositionSizing === 'function') {
            computePositionSizing();
        }
    }
}

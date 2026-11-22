let ragPollTimer = null;
let ragInfoLoaded = false;
let ragAllDocuments = [];
let ragCurrentPage = 1;
const RAG_PAGE_SIZE = 15;

async function initKnowledgePage() {
    if (!ragInfoLoaded) {
        await loadRagStorageInfo();
    }
    await loadRagDocuments();
}

function handleRagFileSelection() {
    const fileInput = document.getElementById('ragUploadFile');
    const nameEl = document.getElementById('ragUploadFileName');
    const file = fileInput?.files?.[0];
    if (nameEl) {
        nameEl.textContent = file ? `${file.name} (${formatRagBytes(file.size)})` : 'No file chosen';
    }
}

async function loadRagStorageInfo() {
    const info = await fetchAPI('/api/rag/info');
    if (!info) return;
    ragInfoLoaded = true;

    const providerEl = document.getElementById('ragStorageProvider');
    const bucketEl = document.getElementById('ragStorageBucket');
    const vectorEl = document.getElementById('ragVectorStatus');
    const prefixInput = document.getElementById('ragSyncPrefix');
    const maxHint = document.getElementById('ragMaxSizeHint');

    if (providerEl) providerEl.textContent = (info.provider || '—').toUpperCase();
    if (bucketEl) bucketEl.textContent = info.bucket || '—';
    if (vectorEl) vectorEl.textContent = info.pgVectorEnabled ? 'pgvector (384-dim cosine)' : 'Keyword fallback';
    if (prefixInput && !prefixInput.value && info.defaultPrefix) {
        prefixInput.value = info.defaultPrefix;
    }
    if (maxHint && info.maxFileSizeBytes) {
        const mb = (info.maxFileSizeBytes / (1024 * 1024)).toFixed(1).replace(/\.0$/, '');
        maxHint.textContent = `Max ${mb} MB (.md, .txt, .csv)`;
    }
}

async function loadRagDocuments(options = {}) {
    const listEl = document.getElementById('ragDocumentsList');
    const countEl = document.getElementById('ragDocumentCount');
    const refreshBtn = document.getElementById('ragRefreshBtn');
    if (!listEl) return;

    const manual = Boolean(options.manual);
    if (manual && refreshBtn) {
        refreshBtn.disabled = true;
        refreshBtn.textContent = 'Refreshing...';
    }

    try {
        const docs = await fetchAPI('/api/rag/documents');
        if (!Array.isArray(docs)) {
            ragAllDocuments = [];
            listEl.replaceChildren();
            const err = document.createElement('div');
            err.className = 'loading';
            err.textContent = 'Unable to load knowledge documents.';
            listEl.appendChild(err);
            hideRagPagination();
            stopRagPolling();
            if (manual) {
                setInlineBanner('knowledgeNotice', 'Unable to refresh document list.', 'error');
            }
            return;
        }

        ragAllDocuments = docs;
        populateRagFolderFilter(docs);

        const readyCount = docs.filter(doc => doc.status === 'READY').length;
        const inFlightCount = docs.filter(doc => doc.status === 'PENDING' || doc.status === 'INDEXING').length;
        if (countEl) {
            countEl.textContent = inFlightCount > 0
                ? `${docs.length} documents · ${readyCount} ready · ${inFlightCount} indexing`
                : `${docs.length} document${docs.length === 1 ? '' : 's'} · ${readyCount} ready`;
        }

        renderFilteredRagDocuments();

        if (manual) {
            showToast(`Loaded ${docs.length} document${docs.length === 1 ? '' : 's'}`, 'success');
        }

        if (inFlightCount > 0) {
            scheduleRagPolling();
        } else {
            stopRagPolling();
        }
    } finally {
        if (manual && refreshBtn) {
            refreshBtn.disabled = false;
            refreshBtn.textContent = 'Refresh List';
        }
    }
}

function handleRagFilterChange() {
    ragCurrentPage = 1;
    renderFilteredRagDocuments();
}

function goToRagPage(nextPage) {
    ragCurrentPage = nextPage;
    renderFilteredRagDocuments();
}

function documentFolderGroup(doc) {
    if (doc.source === 'builtin') {
        return 'builtin';
    }
    const key = String(doc.storageKey || '');
    const parts = key.split('/').filter(Boolean);
    if (parts.length >= 3 && parts[0] === 'knowledge') {
        return parts[1];
    }
    if (parts.length >= 2) {
        return parts[0];
    }
    return 'root';
}

function folderLabel(folder) {
    if (folder === 'builtin') return 'Built-in Guides';
    if (folder === 'root') return 'Bucket Root';
    return folder;
}

function populateRagFolderFilter(docs) {
    const select = document.getElementById('ragFilterFolder');
    if (!select) return;
    const previous = select.value || 'all';
    const counts = new Map();
    docs.forEach(doc => {
        const folder = documentFolderGroup(doc);
        counts.set(folder, (counts.get(folder) || 0) + 1);
    });

    select.replaceChildren();
    const allOpt = document.createElement('option');
    allOpt.value = 'all';
    allOpt.textContent = `All folders (${docs.length})`;
    select.appendChild(allOpt);

    Array.from(counts.keys()).sort().forEach(folder => {
        const opt = document.createElement('option');
        opt.value = folder;
        opt.textContent = `${folderLabel(folder)} (${counts.get(folder)})`;
        select.appendChild(opt);
    });

    if ([...select.options].some(opt => opt.value === previous)) {
        select.value = previous;
    } else {
        select.value = 'all';
    }
}

function renderFilteredRagDocuments() {
    const listEl = document.getElementById('ragDocumentsList');
    if (!listEl) return;

    const query = String(document.getElementById('ragFilterQuery')?.value || '').trim().toLowerCase();
    const folder = String(document.getElementById('ragFilterFolder')?.value || 'all');

    const filtered = ragAllDocuments.filter(doc => {
        if (folder !== 'all' && documentFolderGroup(doc) !== folder) {
            return false;
        }
        if (!query) {
            return true;
        }
        const haystack = `${doc.title || ''} ${doc.filename || ''} ${doc.storageKey || ''} ${doc.status || ''}`.toLowerCase();
        return haystack.includes(query);
    });

    if (filtered.length === 0) {
        listEl.replaceChildren();
        const empty = document.createElement('div');
        empty.className = 'loading';
        empty.textContent = ragAllDocuments.length === 0
            ? 'No documents indexed yet. Upload a file or click Sync from GCS.'
            : 'No documents match the current filter.';
        listEl.appendChild(empty);
        hideRagPagination();
        return;
    }

    const totalPages = Math.max(1, Math.ceil(filtered.length / RAG_PAGE_SIZE));
    if (ragCurrentPage > totalPages) {
        ragCurrentPage = totalPages;
    }
    const startIndex = (ragCurrentPage - 1) * RAG_PAGE_SIZE;
    const pageItems = filtered.slice(startIndex, startIndex + RAG_PAGE_SIZE);

    renderRagTable(listEl, pageItems);
    renderRagPagination(filtered.length, startIndex, pageItems.length, totalPages);
}

function renderRagTable(container, docs) {
    const wrap = document.createElement('div');
    wrap.className = 'rag-table-wrap';

    const table = document.createElement('table');
    table.className = 'rag-table';

    const thead = document.createElement('thead');
    const headerRow = document.createElement('tr');
    ['Document', 'Source', 'Status', 'Chunks / Size', 'Updated', 'Actions'].forEach((label, idx) => {
        const th = document.createElement('th');
        th.textContent = label;
        if (idx === 5) th.style.textAlign = 'right';
        headerRow.appendChild(th);
    });
    thead.appendChild(headerRow);
    table.appendChild(thead);

    const tbody = document.createElement('tbody');
    docs.forEach(doc => {
        const tr = document.createElement('tr');

        const docTd = document.createElement('td');
        docTd.className = 'rag-doc-cell';
        const titleEl = document.createElement('div');
        titleEl.className = 'rag-doc-title';
        titleEl.textContent = doc.title || doc.filename;
        titleEl.title = doc.title || doc.filename;
        docTd.appendChild(titleEl);

        const pathEl = document.createElement('div');
        pathEl.className = 'rag-doc-path';
        const displayPath = doc.storageKey || doc.filename;
        pathEl.textContent = displayPath;
        pathEl.title = doc.storageBucket ? `gs://${doc.storageBucket}/${displayPath}` : displayPath;
        docTd.appendChild(pathEl);

        if (doc.errorMessage) {
            const errEl = document.createElement('div');
            errEl.className = 'rag-doc-error';
            errEl.textContent = `Error: ${doc.errorMessage}`;
            docTd.appendChild(errEl);
        }
        tr.appendChild(docTd);

        const sourceTd = document.createElement('td');
        const sourcePill = document.createElement('span');
        sourcePill.className = doc.source === 'builtin' ? 'status-pill status-pill-info' : 'status-pill status-pill-muted';
        sourcePill.textContent = doc.source === 'builtin' ? 'Guide' : (doc.storageProvider || 'GCS').toUpperCase();
        sourceTd.appendChild(sourcePill);
        tr.appendChild(sourceTd);

        const statusTd = document.createElement('td');
        const statusPill = document.createElement('span');
        statusPill.className = `status-pill ${ragStatusTone(doc.status)}`;
        statusPill.textContent = doc.status;
        statusTd.appendChild(statusPill);
        tr.appendChild(statusTd);

        const statsTd = document.createElement('td');
        statsTd.style.whiteSpace = 'nowrap';
        statsTd.textContent = `${doc.chunkCount} · ${formatRagBytes(doc.sizeBytes)}`;
        tr.appendChild(statsTd);

        const updatedTd = document.createElement('td');
        updatedTd.style.whiteSpace = 'nowrap';
        updatedTd.style.color = '#94a3b8';
        updatedTd.textContent = formatRagTimestamp(doc.updatedAt);
        tr.appendChild(updatedTd);

        const actionsTd = document.createElement('td');
        const actions = document.createElement('div');
        actions.className = 'rag-row-actions';

        const downloadBtn = document.createElement('button');
        downloadBtn.type = 'button';
        downloadBtn.className = 'btn-secondary';
        downloadBtn.textContent = 'Download';
        downloadBtn.onclick = () => {
            window.location.href = `${API_BASE}/api/rag/documents/${encodeURIComponent(doc.id)}/download`;
        };
        actions.appendChild(downloadBtn);

        const reindexBtn = document.createElement('button');
        reindexBtn.type = 'button';
        reindexBtn.className = 'btn-secondary';
        reindexBtn.textContent = 'Re-index';
        reindexBtn.onclick = () => reindexRagDocument(doc.id);
        actions.appendChild(reindexBtn);

        if (doc.source !== 'builtin') {
            const deleteBtn = document.createElement('button');
            deleteBtn.type = 'button';
            deleteBtn.className = 'btn-danger';
            deleteBtn.textContent = 'Delete';
            deleteBtn.onclick = () => deleteRagDocument(doc.id, doc.filename);
            actions.appendChild(deleteBtn);
        }

        actionsTd.appendChild(actions);
        tr.appendChild(actionsTd);
        tbody.appendChild(tr);
    });

    table.appendChild(tbody);
    wrap.appendChild(table);
    container.replaceChildren(wrap);
}

function renderRagPagination(total, startIndex, visibleCount, totalPages) {
    const el = document.getElementById('ragPagination');
    if (!el) return;
    if (total <= RAG_PAGE_SIZE) {
        el.style.display = 'none';
        el.replaceChildren();
        return;
    }

    el.style.display = 'flex';
    el.replaceChildren();

    const summary = document.createElement('span');
    summary.textContent = `Showing ${startIndex + 1}–${startIndex + visibleCount} of ${total}`;
    el.appendChild(summary);

    const buttons = document.createElement('div');
    buttons.className = 'rag-pagination-buttons';

    const prevBtn = document.createElement('button');
    prevBtn.type = 'button';
    prevBtn.className = 'btn-secondary';
    prevBtn.textContent = 'Previous';
    prevBtn.disabled = ragCurrentPage <= 1;
    prevBtn.onclick = () => goToRagPage(ragCurrentPage - 1);

    const pageInfo = document.createElement('span');
    pageInfo.textContent = `Page ${ragCurrentPage} of ${totalPages}`;

    const nextBtn = document.createElement('button');
    nextBtn.type = 'button';
    nextBtn.className = 'btn-secondary';
    nextBtn.textContent = 'Next';
    nextBtn.disabled = ragCurrentPage >= totalPages;
    nextBtn.onclick = () => goToRagPage(ragCurrentPage + 1);

    buttons.appendChild(prevBtn);
    buttons.appendChild(pageInfo);
    buttons.appendChild(nextBtn);
    el.appendChild(buttons);
}

function hideRagPagination() {
    const el = document.getElementById('ragPagination');
    if (!el) return;
    el.style.display = 'none';
    el.replaceChildren();
}

async function uploadRagDocument() {
    const fileInput = document.getElementById('ragUploadFile');
    const titleInput = document.getElementById('ragUploadTitle');
    const uploadBtn = document.getElementById('ragUploadBtn');
    const file = fileInput?.files?.[0];
    if (!file) {
        setInlineBanner('knowledgeNotice', 'Select a .md, .txt, or .csv file first.', 'warning');
        return;
    }

    const formData = new FormData();
    formData.append('file', file);
    if (titleInput && titleInput.value.trim()) {
        formData.append('title', titleInput.value.trim());
    }

    if (uploadBtn) uploadBtn.disabled = true;
    setInlineBanner('knowledgeNotice', `Uploading ${file.name}...`, 'info');
    try {
        const headers = {};
        const csrfToken = await getCsrfToken();
        if (csrfToken) {
            headers['X-XSRF-TOKEN'] = csrfToken;
        }
        const response = await fetch(`${API_BASE}/api/rag/documents`, {
            method: 'POST',
            headers,
            body: formData,
            credentials: 'include'
        });
        if (response.status === 401) {
            window.location.href = '/login.html';
            return;
        }
        const payload = await safeParseJson(response);
        if (!response.ok) {
            setInlineBanner('knowledgeNotice', payload?.error || `Upload failed (${response.status})`, 'error');
            return;
        }
        if (fileInput) fileInput.value = '';
        if (titleInput) titleInput.value = '';
        handleRagFileSelection();
        setInlineBanner('knowledgeNotice', `Uploaded "${payload.filename}". Indexing in background...`, 'success');
        showToast('Document uploaded', 'success');
        await loadRagDocuments();
    } catch (_) {
        setInlineBanner('knowledgeNotice', 'Upload failed due to a network error.', 'error');
    } finally {
        if (uploadBtn) uploadBtn.disabled = false;
    }
}

async function syncRagFromStorage() {
    const prefixInput = document.getElementById('ragSyncPrefix');
    const syncBtn = document.getElementById('ragSyncBtn');
    const prefix = prefixInput ? prefixInput.value.trim() : '';

    if (syncBtn) syncBtn.disabled = true;
    setInlineBanner('knowledgeNotice', `Scanning bucket${prefix ? ` prefix "${prefix}"` : ''}...`, 'info');
    try {
        const headers = {};
        const csrfToken = await getCsrfToken();
        if (csrfToken) {
            headers['X-XSRF-TOKEN'] = csrfToken;
        }
        const query = prefixInput ? `?prefix=${encodeURIComponent(prefix)}` : '';
        const response = await fetch(`${API_BASE}/api/rag/sync${query}`, {
            method: 'POST',
            headers,
            credentials: 'include'
        });
        if (response.status === 401) {
            window.location.href = '/login.html';
            return;
        }
        const payload = await safeParseJson(response);
        if (!response.ok) {
            setInlineBanner('knowledgeNotice', payload?.error || `Sync failed (${response.status})`, 'error');
            return;
        }
        setInlineBanner(
            'knowledgeNotice',
            `Scanned ${payload.scanned} object(s) in ${payload.bucket}: ${payload.queued} queued, ${payload.skipped} unchanged, ${payload.ignored} ignored.`,
            'success'
        );
        await loadRagDocuments();
    } catch (_) {
        setInlineBanner('knowledgeNotice', 'Storage sync failed due to a network error.', 'error');
    } finally {
        if (syncBtn) syncBtn.disabled = false;
    }
}

async function reindexRagDocument(id) {
    const headers = {};
    const csrfToken = await getCsrfToken();
    if (csrfToken) {
        headers['X-XSRF-TOKEN'] = csrfToken;
    }
    const response = await fetch(`${API_BASE}/api/rag/documents/${encodeURIComponent(id)}/reindex`, {
        method: 'POST',
        headers,
        credentials: 'include'
    });
    if (!response.ok) {
        const payload = await safeParseJson(response);
        setInlineBanner('knowledgeNotice', payload?.error || 'Re-index request failed.', 'error');
        return;
    }
    showToast('Re-indexing queued', 'success');
    await loadRagDocuments();
}

async function deleteRagDocument(id, filename) {
    if (!confirm(`Delete "${filename}" from storage and remove its indexed chunks?`)) {
        return;
    }
    const headers = {};
    const csrfToken = await getCsrfToken();
    if (csrfToken) {
        headers['X-XSRF-TOKEN'] = csrfToken;
    }
    const response = await fetch(`${API_BASE}/api/rag/documents/${encodeURIComponent(id)}`, {
        method: 'DELETE',
        headers,
        credentials: 'include'
    });
    if (!response.ok) {
        const payload = await safeParseJson(response);
        setInlineBanner('knowledgeNotice', payload?.error || 'Delete failed.', 'error');
        return;
    }
    showToast('Document deleted', 'success');
    await loadRagDocuments();
}

async function searchRagChunks() {
    const input = document.getElementById('ragSearchQuery');
    const resultsEl = document.getElementById('ragSearchResults');
    const query = input ? input.value.trim() : '';
    if (!query || !resultsEl) return;

    resultsEl.replaceChildren();
    const loading = document.createElement('div');
    loading.className = 'loading';
    loading.textContent = 'Searching indexed chunks...';
    resultsEl.appendChild(loading);

    const payload = await fetchAPI(`/api/rag/search?q=${encodeURIComponent(query)}&limit=5`);
    resultsEl.replaceChildren();
    if (!payload || !Array.isArray(payload.hits) || payload.hits.length === 0) {
        const empty = document.createElement('div');
        empty.className = 'loading';
        empty.textContent = 'No matching chunks found.';
        resultsEl.appendChild(empty);
        return;
    }

    payload.hits.forEach(hit => {
        const card = document.createElement('div');
        card.className = 'rag-chunk-card';

        const header = document.createElement('div');
        header.className = 'rag-chunk-header';

        const title = document.createElement('strong');
        title.textContent = hit.heading && hit.heading !== hit.documentTitle
            ? `${hit.documentTitle} — ${hit.heading}`
            : hit.documentTitle;
        header.appendChild(title);

        const score = document.createElement('span');
        score.className = 'status-pill status-pill-info';
        score.textContent = `score ${(Number(hit.score || 0)).toFixed(2)} · chunk #${hit.chunkIndex}`;
        header.appendChild(score);

        const content = document.createElement('pre');
        content.className = 'rag-chunk-preview';
        content.textContent = hit.content;

        card.appendChild(header);
        card.appendChild(content);
        resultsEl.appendChild(card);
    });
}

function scheduleRagPolling() {
    if (ragPollTimer) return;
    ragPollTimer = setTimeout(async () => {
        ragPollTimer = null;
        const page = document.getElementById('knowledge');
        if (page && page.classList.contains('active')) {
            await loadRagDocuments();
        }
    }, 2000);
}

function stopRagPolling() {
    if (ragPollTimer) {
        clearTimeout(ragPollTimer);
        ragPollTimer = null;
    }
}

function ragStatusTone(status) {
    if (status === 'READY') return 'status-pill-success';
    if (status === 'FAILED') return 'status-pill-warning';
    if (status === 'INDEXING') return 'status-pill-info';
    return 'status-pill-muted';
}

function formatRagBytes(bytes) {
    const value = Number(bytes || 0);
    if (value < 1024) return `${value} B`;
    if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
    return `${(value / (1024 * 1024)).toFixed(2)} MB`;
}

function formatRagTimestamp(value) {
    if (!value) return '—';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '—';
    return date.toLocaleString('en-US', {
        month: 'short',
        day: '2-digit',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false
    });
}

async function safeParseJson(response) {
    try {
        return await response.json();
    } catch (_) {
        return null;
    }
}

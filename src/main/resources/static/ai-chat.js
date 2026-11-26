(() => {
    const state = {
        conversationId: null,
        abortController: null,
        sending: false
    };

    const TOOL_META = {
        get_live_rates: {
            title: 'Live Spot Rates',
            activeLabel: 'Checking live spot rates'
        },
        get_historical_series: {
            title: 'Price History',
            activeLabel: 'Loading price history'
        },
        compare_pairs: {
            title: 'Pair Comparison & Correlation',
            activeLabel: 'Comparing pairs & correlation'
        },
        analyze_pair_technicals: {
            title: 'Technical Indicators',
            activeLabel: 'Calculating technical indicators'
        },
        calculate_position_size: {
            title: 'Position Size & Risk',
            activeLabel: 'Calculating position size & risk'
        },
        list_my_alert_rules: {
            title: 'My Alert Rules',
            activeLabel: 'Loading alert rules'
        },
        get_my_alert_inbox: {
            title: 'Alert Inbox',
            activeLabel: 'Checking alert inbox'
        },
        create_alert_rule: {
            title: 'Create Alert Rule',
            activeLabel: 'Creating alert rule'
        },
        update_alert_rule_status: {
            title: 'Update Alert Rule',
            activeLabel: 'Updating alert rule'
        },
        get_settings: {
            title: 'Risk Settings',
            activeLabel: 'Reading risk settings'
        },
        search_notes: {
            title: 'Knowledge Base Search',
            activeLabel: 'Searching knowledge notes'
        }
    };

    document.addEventListener('DOMContentLoaded', initializeAiChat);

    async function initializeAiChat() {
        const [statusResponse, authResponse] = await Promise.all([
            fetch('/api/ai/status', { credentials: 'include' }),
            fetch('/api/auth/status', { credentials: 'include' })
        ]);
        if (!statusResponse.ok) return;
        const status = await statusResponse.json();
        if (!status.enabled) return;
        if (authResponse.ok) {
            const authStatus = await authResponse.json();
            if (authStatus && authStatus.can_use_ai === false) {
                return;
            }
        }

        const openButton = document.getElementById('aiChatOpenButton');
        const panel = document.getElementById('aiChatPanel');
        openButton.hidden = false;
        openButton.addEventListener('click', openChat);
        document.getElementById('aiChatCloseButton').addEventListener('click', closeChat);
        document.getElementById('aiNewConversationButton').addEventListener('click', createConversation);
        document.getElementById('aiConversationSelect').addEventListener('change', selectConversation);
        document.getElementById('aiChatForm').addEventListener('submit', sendMessage);
        document.getElementById('aiChatStopButton').addEventListener('click', stopResponse);
        installPanelResize(panel);
        panel.hidden = true;

        window.openAiChatWithPrompt = async function openAiChatWithPrompt(promptText) {
            await openChat();
            const input = document.getElementById('aiChatInput');
            if (input && typeof promptText === 'string' && promptText.trim()) {
                input.value = promptText.trim();
                input.focus();
            }
        };
    }

    function installPanelResize(panel) {
        const handle = document.getElementById('aiChatResize');
        const storageKey = 'ai-chat-panel-width';
        const minimumWidth = 320;

        function maximumWidth() {
            return Math.max(minimumWidth, Math.min(window.innerWidth, 960));
        }

        function applyWidth(width) {
            const next = Math.round(Math.min(maximumWidth(), Math.max(minimumWidth, width)));
            panel.style.setProperty('--ai-chat-width', `${next}px`);
            handle.setAttribute('aria-valuemin', String(minimumWidth));
            handle.setAttribute('aria-valuemax', String(maximumWidth()));
            handle.setAttribute('aria-valuenow', String(next));
            return next;
        }

        const saved = Number(localStorage.getItem(storageKey));
        if (saved >= minimumWidth) {
            applyWidth(saved);
        }

        handle.addEventListener('pointerdown', event => {
            event.preventDefault();
            try {
                handle.setPointerCapture(event.pointerId);
            } catch {
                // Synthetic pointer events in tests have no active pointer to capture.
            }
            const move = pointerEvent => applyWidth(window.innerWidth - pointerEvent.clientX);
            const finish = pointerEvent => {
                try {
                    if (handle.hasPointerCapture(pointerEvent.pointerId)) {
                        handle.releasePointerCapture(pointerEvent.pointerId);
                    }
                } catch {
                    // The pointer was already released.
                }
                handle.removeEventListener('pointermove', move);
                handle.removeEventListener('pointerup', finish);
                handle.removeEventListener('pointercancel', finish);
                localStorage.setItem(storageKey, handle.getAttribute('aria-valuenow'));
            };
            handle.addEventListener('pointermove', move);
            handle.addEventListener('pointerup', finish);
            handle.addEventListener('pointercancel', finish);
        });

        handle.addEventListener('keydown', event => {
            const step = event.shiftKey ? 80 : 24;
            const current = Number(handle.getAttribute('aria-valuenow')) || 440;
            if (event.key === 'ArrowLeft') {
                localStorage.setItem(storageKey, String(applyWidth(current + step)));
            } else if (event.key === 'ArrowRight') {
                localStorage.setItem(storageKey, String(applyWidth(current - step)));
            } else {
                return;
            }
            event.preventDefault();
        });

        window.addEventListener('resize', () => applyWidth(Number(handle.getAttribute('aria-valuenow')) || 440));
    }

    async function openChat() {
        document.getElementById('aiChatPanel').hidden = false;
        document.getElementById('aiChatOpenButton').setAttribute('aria-expanded', 'true');
        await loadConversations();
        document.getElementById('aiChatInput').focus();
    }

    function closeChat() {
        document.getElementById('aiChatPanel').hidden = true;
        document.getElementById('aiChatOpenButton').setAttribute('aria-expanded', 'false');
    }

    async function loadConversations() {
        const response = await authenticatedRequest('/api/ai/conversations');
        if (!response.ok) {
            showError(await describeRequestFailure(response, 'Unable to load conversations.'));
            return;
        }
        const conversations = await response.json();
        const select = document.getElementById('aiConversationSelect');
        select.replaceChildren();
        conversations.forEach(conversation => {
            const option = document.createElement('option');
            option.value = conversation.id;
            option.textContent = conversation.title || 'New conversation';
            select.append(option);
        });
        if (conversations.length > 0) {
            state.conversationId = conversations[0].id;
            select.value = state.conversationId;
            await loadMessages();
        } else {
            await createConversation();
        }
    }

    async function createConversation() {
        const response = await authenticatedRequest('/api/ai/conversations', { method: 'POST' });
        if (!response.ok) {
            showError(await describeRequestFailure(response, 'Unable to create a conversation.'));
            return;
        }
        const conversation = await response.json();
        state.conversationId = conversation.id;
        const select = document.getElementById('aiConversationSelect');
        const option = document.createElement('option');
        option.value = conversation.id;
        option.textContent = 'New conversation';
        select.prepend(option);
        select.value = conversation.id;
        document.getElementById('aiChatMessages').replaceChildren();
        hideError();
    }

    async function selectConversation(event) {
        state.conversationId = event.target.value;
        await loadMessages();
    }

    async function loadMessages() {
        if (!state.conversationId) return;
        const response = await authenticatedRequest(
            `/api/ai/conversations/${encodeURIComponent(state.conversationId)}/messages`
        );
        if (!response.ok) {
            showError('Unable to load messages.');
            return;
        }
        const messages = await response.json();
        const container = document.getElementById('aiChatMessages');
        container.replaceChildren();
        messages.forEach(message => appendMessage(
            message.role,
            message.content,
            message.reasoning,
            message.tool_calls
        ));
        scrollMessagesToBottom();
    }

    async function sendMessage(event) {
        event.preventDefault();
        if (state.sending || !state.conversationId) return;
        const input = document.getElementById('aiChatInput');
        const message = input.value.trim();
        if (!message) return;

        input.value = '';
        hideError();
        appendMessage('user', message);

        const tracker = createLiveTurnTracker(appendMessage('assistant', '', '', [], true));
        state.sending = true;
        state.abortController = new AbortController();
        updateSendingControls();

        try {
            const response = await authenticatedRequest(
                `/api/ai/conversations/${encodeURIComponent(state.conversationId)}/messages`,
                {
                    method: 'POST',
                    headers: { Accept: 'text/event-stream' },
                    body: JSON.stringify({ message }),
                    signal: state.abortController.signal
                }
            );
            if (!response.ok) {
                const error = await response.json().catch(() => ({}));
                throw new Error(error.error || `Request failed (${response.status})`);
            }
            await readEventStream(response.body, sseEvent => {
                handleSseEvent(tracker, sseEvent);
            });
        } catch (error) {
            tracker.onError();
            if (error.name !== 'AbortError') showError(error.message || 'Unable to send message.');
        } finally {
            tracker.finish();
            state.sending = false;
            state.abortController = null;
            updateSendingControls();
            await refreshConversationTitles();
        }
    }

    function createLiveTurnTracker(elements) {
        const startedAt = performance.now();
        let hasAnswerStarted = false;
        let hasReasoning = false;
        let needsReasoningBreak = false;
        let userToggled = false;
        let failed = false;
        let rawAnswer = '';
        let completed = false;
        const stepElements = [];

        const summaryElement = elements.activityCard.querySelector('.ai-activity-summary');
        summaryElement.addEventListener('click', () => {
            userToggled = true;
        });

        elements.activityCard.hidden = false;
        elements.activityCard.open = false;
        elements.activityCard.dataset.state = 'working';
        elements.activityLabel.textContent = 'Analyzing request & context...';
        elements.activityLabel.classList.add('is-shimmer');
        elements.activityMeta.textContent = '0.0s';

        const timerId = window.setInterval(() => {
            const elapsedSec = ((performance.now() - startedAt) / 1000).toFixed(1);
            elements.activityMeta.textContent = `${elapsedSec}s`;
        }, 120);

        function openIfAuto() {
            if (!hasAnswerStarted && !userToggled) {
                elements.activityCard.open = true;
            }
        }

        function onReasoningDelta(delta) {
            if (!delta) return;
            elements.reasoningSection.hidden = false;
            openIfAuto();
            if (needsReasoningBreak && hasReasoning) {
                elements.thoughtBody.textContent += '\n\n';
            }
            needsReasoningBreak = false;
            hasReasoning = true;
            elements.thoughtBody.textContent += delta;
            if (!hasAnswerStarted) {
                elements.activityLabel.textContent = stepElements.length > 0
                    ? 'Analyzing tool findings...'
                    : 'Thinking...';
            }
            scrollMessagesToBottom();
        }

        function onToolStarted(payload) {
            elements.toolChain.hidden = false;
            openIfAuto();
            if (hasReasoning) {
                needsReasoningBreak = true;
            }
            const stepElement = createToolStepElement({
                step: payload.step || stepElements.length + 1,
                tool: payload.tool,
                arguments: payload.arguments,
                status: 'running',
                durationMs: null,
                summary: null
            });
            elements.toolChain.append(stepElement);
            stepElements.push(stepElement);
            if (!hasAnswerStarted) {
                elements.activityLabel.textContent = buildActiveToolLabel(payload.tool, payload.arguments);
            }
            scrollMessagesToBottom();
        }

        function onToolCompleted(payload) {
            const stepElement = findToolStepElement(stepElements, payload.step, payload.tool);
            if (stepElement) {
                updateToolStepCompletion(stepElement, payload);
            }
            const anyRunning = stepElements.some(el => el.dataset.status === 'running');
            if (!anyRunning && !hasAnswerStarted) {
                elements.activityLabel.textContent = 'Synthesizing results...';
            }
            scrollMessagesToBottom();
        }

        function onTextDelta(delta) {
            if (!delta) return;
            if (!hasAnswerStarted) {
                hasAnswerStarted = true;
                elements.message.classList.add('is-streaming');
                if (!userToggled) {
                    elements.activityCard.open = false;
                }
                elements.activityLabel.textContent = 'Writing response...';
            }
            rawAnswer += delta;
            renderAssistantMarkdown(elements.answerElement, rawAnswer);
            scrollMessagesToBottom();
        }

        function onCompleted() {
            completed = true;
            if (!userToggled) {
                elements.activityCard.open = false;
            }
        }

        function onError() {
            failed = true;
        }

        function finish() {
            window.clearInterval(timerId);
            elements.message.classList.remove('is-streaming');
            elements.activityLabel.classList.remove('is-shimmer');
            const elapsedMs = Math.max(0, Math.round(performance.now() - startedAt));
            if (failed) {
                elements.activityCard.dataset.state = 'error';
                elements.activityLabel.textContent = 'Assistant stopped';
                elements.activityMeta.textContent = formatDuration(elapsedMs);
                return;
            }
            if (!completed && !userToggled) {
                elements.activityCard.open = false;
            }
            elements.activityCard.dataset.state = 'done';
            if (stepElements.length === 0 && !hasReasoning) {
                elements.activityCard.hidden = true;
                return;
            }
            elements.activityLabel.textContent = buildCompletedSummaryLabel(
                stepElements.length,
                hasReasoning,
                elapsedMs
            );
            elements.activityMeta.textContent = '';
        }

        return {
            onReasoningDelta,
            onToolStarted,
            onToolCompleted,
            onTextDelta,
            onCompleted,
            onError,
            finish
        };
    }

    function handleSseEvent(tracker, sseEvent) {
        if (sseEvent.event === 'reasoning_delta') {
            const payload = JSON.parse(sseEvent.data);
            tracker.onReasoningDelta(payload.text || '');
        } else if (sseEvent.event === 'tool_started') {
            const payload = JSON.parse(sseEvent.data);
            tracker.onToolStarted(payload);
        } else if (sseEvent.event === 'tool_completed') {
            const payload = JSON.parse(sseEvent.data);
            tracker.onToolCompleted(payload);
        } else if (sseEvent.event === 'text_delta') {
            const payload = JSON.parse(sseEvent.data);
            tracker.onTextDelta(payload.text || '');
        } else if (sseEvent.event === 'message_completed') {
            tracker.onCompleted();
        } else if (sseEvent.event === 'error') {
            const payload = JSON.parse(sseEvent.data);
            tracker.onError();
            showError(payload.message || 'The assistant could not complete the response.');
        }
    }

    function findToolStepElement(stepElements, step, tool) {
        if (step != null) {
            const byStep = stepElements.find(el => el.dataset.step === String(step));
            if (byStep) return byStep;
        }
        for (let index = stepElements.length - 1; index >= 0; index--) {
            if (stepElements[index].dataset.tool === tool && stepElements[index].dataset.status === 'running') {
                return stepElements[index];
            }
        }
        return stepElements[stepElements.length - 1] || null;
    }

    function createToolStepElement(stepData) {
        const li = document.createElement('li');
        li.className = 'ai-tool-step';
        li.dataset.step = String(stepData.step || '');
        li.dataset.tool = stepData.tool || '';
        const status = stepData.status || 'ok';
        li.dataset.status = status;

        const marker = document.createElement('span');
        marker.className = 'ai-tool-step-marker';
        marker.setAttribute('aria-hidden', 'true');
        marker.textContent = toolMarkerSymbol(status);

        const main = document.createElement('div');
        main.className = 'ai-tool-step-main';

        const row = document.createElement('div');
        row.className = 'ai-tool-step-row';

        const title = document.createElement('span');
        title.className = 'ai-tool-step-title';
        title.textContent = getToolMeta(stepData.tool).title;
        row.append(title);

        const argText = formatToolArguments(stepData.tool, stepData.arguments);
        if (argText) {
            const pill = document.createElement('span');
            pill.className = 'ai-tool-arg-pill';
            pill.textContent = argText;
            row.append(pill);
        }

        const duration = document.createElement('span');
        duration.className = 'ai-tool-duration';
        duration.textContent = status === 'running'
            ? 'Running...'
            : formatDuration(stepData.durationMs);
        row.append(duration);

        const summary = document.createElement('div');
        summary.className = 'ai-tool-step-summary';
        const summaryText = formatToolSummary(stepData.summary);
        if (summaryText) {
            summary.textContent = summaryText;
        } else {
            summary.hidden = true;
        }

        main.append(row, summary);
        li.append(marker, main);
        return li;
    }

    function updateToolStepCompletion(stepElement, payload) {
        const status = payload.status === 'error' ? 'error' : 'ok';
        stepElement.dataset.status = status;

        const marker = stepElement.querySelector('.ai-tool-step-marker');
        if (marker) {
            marker.textContent = toolMarkerSymbol(status);
        }

        const duration = stepElement.querySelector('.ai-tool-duration');
        if (duration) {
            duration.textContent = formatDuration(payload.duration_ms);
        }

        const summary = stepElement.querySelector('.ai-tool-step-summary');
        const summaryText = formatToolSummary(payload.summary);
        if (summary && summaryText) {
            summary.textContent = summaryText;
            summary.hidden = false;
        }
    }

    function toolMarkerSymbol(status) {
        if (status === 'running') return '';
        if (status === 'error') return '!';
        return '✓';
    }

    function getToolMeta(tool) {
        if (TOOL_META[tool]) {
            return TOOL_META[tool];
        }
        return {
            title: formatFallbackToolName(tool),
            activeLabel: 'Running assistant tool'
        };
    }

    function formatFallbackToolName(tool) {
        if (!tool || typeof tool !== 'string') return 'Assistant Tool';
        return tool
            .split('_')
            .filter(Boolean)
            .map(part => part.charAt(0).toUpperCase() + part.slice(1))
            .join(' ');
    }

    function parseJsonObject(raw) {
        if (!raw) return null;
        if (typeof raw === 'object') return raw;
        if (typeof raw !== 'string') return null;
        try {
            const parsed = JSON.parse(raw);
            return parsed && typeof parsed === 'object' ? parsed : null;
        } catch {
            return null;
        }
    }

    function formatToolArguments(tool, rawArguments) {
        const args = parseJsonObject(rawArguments);
        if (!args) return '';
        if (tool === 'compare_pairs' && args.first && args.second) {
            return args.days ? `${args.first} vs ${args.second} · ${args.days}d` : `${args.first} vs ${args.second}`;
        }
        if ((tool === 'get_historical_series' || tool === 'analyze_pair_technicals') && args.pair) {
            return args.days ? `${args.pair} · ${args.days}d` : String(args.pair);
        }
        if (tool === 'calculate_position_size') {
            return formatPositionArgs(args);
        }
        if (tool === 'search_notes' && args.query) {
            const text = String(args.query).trim();
            return text.length > 36 ? `"${text.slice(0, 33)}..."` : `"${text}"`;
        }
        if (tool === 'create_alert_rule') {
            return joinPresent([args.pair, args.condition_type, args.target_value]);
        }
        if (tool === 'update_alert_rule_status') {
            return joinPresent([args.status]);
        }
        if (tool === 'list_my_alert_rules') {
            return joinPresent([args.pair, args.status]);
        }
        if (tool === 'get_my_alert_inbox') {
            return joinPresent([
                args.pair,
                args.unread_only ? 'unread' : '',
                args.limit != null ? `limit ${args.limit}` : ''
            ]);
        }
        return args.pair ? String(args.pair) : '';
    }

    function formatPositionArgs(args) {
        const parts = [];
        if (args.pair) parts.push(String(args.pair));
        if (args.risk_pct != null) parts.push(`${args.risk_pct}% risk`);
        if (args.account_equity != null && Number.isFinite(Number(args.account_equity))) {
            parts.push(`$${Number(args.account_equity).toLocaleString('en-US')}`);
        }
        return parts.join(' · ');
    }

    function joinPresent(values) {
        return values
            .filter(value => value !== null && value !== undefined && String(value).trim() !== '')
            .map(value => String(value))
            .join(' · ');
    }

    function formatToolSummary(rawSummary) {
        const summary = parseJsonObject(rawSummary);
        if (!summary) return '';
        if (summary.status === 'error') {
            return summary.error || 'Tool returned an error';
        }
        const hints = collectSummaryHints(summary);
        return hints.slice(0, 3).join(' · ');
    }

    function collectSummaryHints(summary) {
        const hints = [];
        if (summary.pair_count != null) hints.push(`${summary.pair_count} live pairs`);
        if (summary.point_count != null) hints.push(`${summary.point_count} points`);
        if (summary.days != null && summary.point_count == null) hints.push(`${summary.days}d lookback`);
        if (summary.pearson_correlation != null && Number.isFinite(Number(summary.pearson_correlation))) {
            hints.push(`r = ${Number(summary.pearson_correlation).toFixed(2)}`);
        }
        if (summary.direction) hints.push(String(summary.direction).toUpperCase());
        if (summary.entry_source === 'live_spot') hints.push('live spot');
        if (summary.exceeds_daily_risk_limit === true) hints.push('exceeds daily limit');
        if (summary.rule_status) hints.push(`Rule ${summary.rule_status}`);
        if (summary.count != null) hints.push(`${summary.count} results`);
        if (summary.unread_count != null) hints.push(`${summary.unread_count} unread`);
        if (summary.created === true) hints.push('Created');
        if (summary.updated === true) hints.push('Updated');
        return hints;
    }

    function buildActiveToolLabel(tool, rawArguments) {
        const base = getToolMeta(tool).activeLabel;
        const argPreview = formatToolArguments(tool, rawArguments);
        if (argPreview && argPreview.length <= 28) {
            return `${base} · ${argPreview}...`;
        }
        return `${base}...`;
    }

    function buildCompletedSummaryLabel(toolCount, hasReasoning, elapsedMs) {
        const parts = [];
        if (toolCount === 1) {
            parts.push('Used 1 tool');
        } else if (toolCount > 1) {
            parts.push(`Used ${toolCount} tools`);
        }
        if (hasReasoning) {
            parts.push(toolCount > 0 ? 'Thought' : 'Thought process');
        }
        const formattedDuration = formatDuration(elapsedMs);
        if (formattedDuration) {
            parts.push(formattedDuration);
        }
        return parts.join(' · ') || 'Completed';
    }

    function formatDuration(durationMs) {
        if (typeof durationMs !== 'number' || Number.isNaN(durationMs) || durationMs < 0) {
            return '';
        }
        if (durationMs < 1000) {
            return `${Math.round(durationMs)} ms`;
        }
        return `${(durationMs / 1000).toFixed(1)} s`;
    }

    async function stopResponse() {
        if (!state.sending || !state.conversationId) return;
        state.abortController?.abort();
        await authenticatedRequest(
            `/api/ai/conversations/${encodeURIComponent(state.conversationId)}/cancel`,
            { method: 'POST' }
        );
    }

    async function refreshConversationTitles() {
        const response = await authenticatedRequest('/api/ai/conversations');
        if (!response.ok) return;
        const conversations = await response.json();
        const select = document.getElementById('aiConversationSelect');
        const selectedId = state.conversationId;
        select.replaceChildren();
        conversations.forEach(conversation => {
            const option = document.createElement('option');
            option.value = conversation.id;
            option.textContent = conversation.title || 'New conversation';
            select.append(option);
        });
        select.value = selectedId;
    }

    async function authenticatedRequest(url, options = {}) {
        const method = (options.method || 'GET').toUpperCase();
        const headers = { ...options.headers };
        if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
            const token = await getCsrfToken();
            if (token) headers['X-XSRF-TOKEN'] = token;
            headers['Content-Type'] = 'application/json';
        }
        return fetch(url, {
            ...options,
            method,
            headers,
            credentials: 'include'
        });
    }

    async function describeRequestFailure(response, fallback) {
        let detail = '';
        try {
            const body = await response.json();
            detail = body.error || body.message || '';
        } catch {
            // Some security and proxy responses are not JSON.
        }
        return `${detail || fallback} (HTTP ${response.status})`;
    }

    async function readEventStream(stream, onEvent) {
        if (!stream) throw new Error('The server returned an empty response stream.');
        const reader = stream.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        while (true) {
            const { value, done } = await reader.read();
            buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
            buffer = buffer.replaceAll('\r\n', '\n');
            let boundary;
            while ((boundary = buffer.indexOf('\n\n')) >= 0) {
                const block = buffer.slice(0, boundary);
                buffer = buffer.slice(boundary + 2);
                dispatchEventBlock(block, onEvent);
            }
            if (done) break;
        }
        if (buffer.trim()) dispatchEventBlock(buffer, onEvent);
    }

    function dispatchEventBlock(block, onEvent) {
        let event = 'message';
        const data = [];
        block.split(/\r?\n/).forEach(line => {
            if (line.startsWith('event:')) event = line.slice(6).trim();
            if (line.startsWith('data:')) data.push(line.slice(5).trim());
        });
        if (data.length) onEvent({ event, data: data.join('\n') });
    }

    function appendMessage(role, content, reasoning, toolCalls = [], isLive = false) {
        const message = document.createElement('div');
        message.className = `ai-chat-message ai-chat-message-${role}`;
        if (role !== 'assistant') {
            message.textContent = content;
            document.getElementById('aiChatMessages').append(message);
            scrollMessagesToBottom();
            return message;
        }

        const elements = buildAssistantMessageElements(content, reasoning, toolCalls, isLive);
        elements.message = message;
        message.append(elements.activityCard, elements.answerElement);
        document.getElementById('aiChatMessages').append(message);
        scrollMessagesToBottom();
        return elements;
    }

    function buildAssistantMessageElements(content, reasoning, toolCalls, isLive) {
        const normalizedTools = Array.isArray(toolCalls) ? toolCalls : [];
        const hasReasoning = Boolean(reasoning && reasoning.trim());
        const hasActivity = normalizedTools.length > 0 || hasReasoning;

        const activityCard = document.createElement('details');
        activityCard.className = 'ai-activity-card';
        activityCard.hidden = !isLive && !hasActivity;
        activityCard.open = false;
        activityCard.dataset.state = isLive ? 'working' : 'done';

        const summary = document.createElement('summary');
        summary.className = 'ai-activity-summary';

        const icon = document.createElement('span');
        icon.className = 'ai-activity-icon';
        icon.setAttribute('aria-hidden', 'true');
        icon.textContent = '✦';

        const activityLabel = document.createElement('span');
        activityLabel.className = 'ai-activity-label';
        activityLabel.textContent = isLive
            ? 'Analyzing request & context...'
            : buildCompletedSummaryLabel(normalizedTools.length, hasReasoning, null);

        const activityMeta = document.createElement('span');
        activityMeta.className = 'ai-activity-meta';
        summary.append(icon, activityLabel, activityMeta);

        const activityBody = document.createElement('div');
        activityBody.className = 'ai-activity-body';

        const toolChain = document.createElement('ol');
        toolChain.className = 'ai-tool-chain';
        toolChain.hidden = normalizedTools.length === 0;
        normalizedTools.forEach((call, index) => {
            toolChain.append(createToolStepElement({
                step: index + 1,
                tool: call.tool,
                arguments: call.arguments,
                status: call.status || 'ok',
                durationMs: call.duration_ms,
                summary: call.summary
            }));
        });

        const reasoningSection = document.createElement('div');
        reasoningSection.className = 'ai-reasoning-section';
        reasoningSection.hidden = !hasReasoning;

        const reasoningHeading = document.createElement('div');
        reasoningHeading.className = 'ai-reasoning-heading';
        reasoningHeading.textContent = 'Reasoning';

        const thoughtBody = document.createElement('div');
        thoughtBody.className = 'ai-chat-thought-body';
        thoughtBody.textContent = reasoning || '';
        reasoningSection.append(reasoningHeading, thoughtBody);

        activityBody.append(toolChain, reasoningSection);
        activityCard.append(summary, activityBody);

        const answerElement = document.createElement('div');
        answerElement.className = 'ai-chat-answer';
        if (content) {
            renderAssistantMarkdown(answerElement, content);
        }

        return {
            activityCard,
            activityLabel,
            activityMeta,
            toolChain,
            reasoningSection,
            thoughtBody,
            answerElement
        };
    }

    function renderAssistantMarkdown(container, markdownText) {
        container.replaceChildren();
        if (!markdownText) return;
        const lines = String(markdownText).replaceAll('\r\n', '\n').split('\n');
        const parser = {
            container,
            listElement: null,
            listTag: '',
            codeLines: null
        };
        for (const line of lines) {
            processMarkdownLine(parser, line);
        }
        if (parser.codeLines) {
            appendCodeBlock(container, parser.codeLines);
        }
    }

    function processMarkdownLine(parser, line) {
        if (line.trimStart().startsWith('```')) {
            closeOpenList(parser);
            if (parser.codeLines) {
                appendCodeBlock(parser.container, parser.codeLines);
                parser.codeLines = null;
            } else {
                parser.codeLines = [];
            }
            return;
        }
        if (parser.codeLines) {
            parser.codeLines.push(line);
            return;
        }
        const trimmed = line.trim();
        if (!trimmed) {
            closeOpenList(parser);
            return;
        }
        if (appendHeadingOrRule(parser, trimmed)) {
            return;
        }
        if (appendListLine(parser, trimmed)) {
            return;
        }
        closeOpenList(parser);
        const paragraph = document.createElement('p');
        appendInlineFormatted(paragraph, line);
        parser.container.append(paragraph);
    }

    function appendHeadingOrRule(parser, trimmed) {
        if (trimmed === '---' || trimmed === '***') {
            closeOpenList(parser);
            parser.container.append(document.createElement('hr'));
            return true;
        }
        const headingLevel = countHeadingHashes(trimmed);
        if (headingLevel === 0) return false;
        closeOpenList(parser);
        const heading = document.createElement(headingLevel <= 2 ? 'h3' : 'h4');
        appendInlineFormatted(heading, trimmed.slice(headingLevel + 1));
        parser.container.append(heading);
        return true;
    }

    function countHeadingHashes(trimmed) {
        if (trimmed.startsWith('### ') || trimmed.startsWith('#### ')) {
            return trimmed.startsWith('#### ') ? 4 : 3;
        }
        if (trimmed.startsWith('## ')) return 2;
        if (trimmed.startsWith('# ')) return 1;
        return 0;
    }

    function appendListLine(parser, trimmed) {
        const isBullet = trimmed.startsWith('- ') || trimmed.startsWith('* ');
        const orderedText = parseOrderedListItem(trimmed);
        if (!isBullet && orderedText === null) return false;

        const targetTag = isBullet ? 'ul' : 'ol';
        if (!parser.listElement || parser.listTag !== targetTag) {
            parser.listElement = document.createElement(targetTag);
            parser.listTag = targetTag;
            parser.container.append(parser.listElement);
        }
        const item = document.createElement('li');
        appendInlineFormatted(item, isBullet ? trimmed.slice(2) : orderedText);
        parser.listElement.append(item);
        return true;
    }

    function parseOrderedListItem(trimmed) {
        let index = 0;
        while (index < trimmed.length && index < 4 && trimmed[index] >= '0' && trimmed[index] <= '9') {
            index++;
        }
        if (index > 0 && trimmed.slice(index, index + 2) === '. ') {
            return trimmed.slice(index + 2);
        }
        return null;
    }

    function closeOpenList(parser) {
        parser.listElement = null;
        parser.listTag = '';
    }

    function appendCodeBlock(container, codeLines) {
        const pre = document.createElement('pre');
        const code = document.createElement('code');
        code.textContent = codeLines.join('\n');
        pre.append(code);
        container.append(pre);
    }

    function appendInlineFormatted(parent, text) {
        let cursor = 0;
        while (cursor < text.length) {
            const boldPos = text.indexOf('**', cursor);
            const codePos = text.indexOf('`', cursor);
            const nextToken = pickNextInlineToken(boldPos, codePos);
            if (!nextToken) {
                parent.append(document.createTextNode(text.slice(cursor)));
                break;
            }
            if (nextToken.pos > cursor) {
                parent.append(document.createTextNode(text.slice(cursor, nextToken.pos)));
            }
            const endPos = text.indexOf(nextToken.delimiter, nextToken.pos + nextToken.delimiter.length);
            if (endPos < 0) {
                parent.append(document.createTextNode(text.slice(nextToken.pos)));
                break;
            }
            const node = document.createElement(nextToken.tag);
            node.textContent = text.slice(nextToken.pos + nextToken.delimiter.length, endPos);
            parent.append(node);
            cursor = endPos + nextToken.delimiter.length;
        }
    }

    function pickNextInlineToken(boldPos, codePos) {
        const hasBold = boldPos >= 0;
        const hasCode = codePos >= 0;
        if (!hasBold && !hasCode) return null;
        if (hasCode && (!hasBold || codePos < boldPos)) {
            return { pos: codePos, delimiter: '`', tag: 'code' };
        }
        return { pos: boldPos, delimiter: '**', tag: 'strong' };
    }

    function scrollMessagesToBottom() {
        const messages = document.getElementById('aiChatMessages');
        messages.scrollTop = messages.scrollHeight;
    }

    function showError(message) {
        const error = document.getElementById('aiChatError');
        error.textContent = message;
        error.hidden = false;
    }

    function hideError() {
        const error = document.getElementById('aiChatError');
        error.textContent = '';
        error.hidden = true;
    }

    function updateSendingControls() {
        document.getElementById('aiChatSendButton').disabled = state.sending;
        document.getElementById('aiChatStopButton').hidden = !state.sending;
        document.getElementById('aiChatInput').disabled = state.sending;
    }
})();

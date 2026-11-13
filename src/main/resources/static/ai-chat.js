(() => {
    const state = {
        conversationId: null,
        abortController: null,
        sending: false
    };

    document.addEventListener('DOMContentLoaded', initializeAiChat);

    async function initializeAiChat() {
        const statusResponse = await fetch('/api/ai/status', { credentials: 'include' });
        if (!statusResponse.ok) return;
        const status = await statusResponse.json();
        if (!status.enabled) return;

        const openButton = document.getElementById('aiChatOpenButton');
        const panel = document.getElementById('aiChatPanel');
        openButton.hidden = false;
        openButton.addEventListener('click', openChat);
        document.getElementById('aiChatCloseButton').addEventListener('click', closeChat);
        document.getElementById('aiNewConversationButton').addEventListener('click', createConversation);
        document.getElementById('aiConversationSelect').addEventListener('change', selectConversation);
        document.getElementById('aiChatForm').addEventListener('submit', sendMessage);
        document.getElementById('aiChatStopButton').addEventListener('click', stopResponse);
        panel.hidden = true;
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
        messages.forEach(message => appendMessage(message.role, message.content));
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
        const assistantElement = appendMessage('assistant', '');
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
            await readEventStream(response.body, event => {
                if (event.event === 'text_delta') {
                    const payload = JSON.parse(event.data);
                    assistantElement.textContent += payload.text || '';
                    scrollMessagesToBottom();
                } else if (event.event === 'error') {
                    const payload = JSON.parse(event.data);
                    showError(payload.message || 'The assistant could not complete the response.');
                }
            });
        } catch (error) {
            if (error.name !== 'AbortError') showError(error.message || 'Unable to send message.');
        } finally {
            state.sending = false;
            state.abortController = null;
            updateSendingControls();
            await refreshConversationTitles();
        }
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
        } catch (_) {
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
            buffer = buffer.replace(/\r\n/g, '\n');
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

    function appendMessage(role, content) {
        const message = document.createElement('div');
        message.className = `ai-chat-message ai-chat-message-${role}`;
        message.textContent = content;
        document.getElementById('aiChatMessages').append(message);
        scrollMessagesToBottom();
        return message;
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

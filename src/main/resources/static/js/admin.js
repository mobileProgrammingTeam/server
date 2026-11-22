(() => {
    const csrfToken = document.querySelector('meta[name="csrf-token"]')?.content;
    const csrfHeader = document.querySelector('meta[name="csrf-header"]')?.content;
    const time = value => value ? new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'medium', timeZone: 'Asia/Seoul' }).format(new Date(value)) : '-';
    const notice = document.getElementById('notice') || document.getElementById('message');
    const announce = value => { if (notice) notice.textContent = value; };

    function formatTimes(root = document) {
        root.querySelectorAll('[data-time]').forEach(element => { element.textContent = time(element.dataset.time); });
    }
    formatTimes();

    async function request(url, options = {}) {
        const headers = { Accept: 'application/json', ...(options.headers || {}) };
        if (options.method && options.method !== 'GET' && csrfHeader && csrfToken) headers[csrfHeader] = csrfToken;
        const response = await fetch(url, { credentials: 'same-origin', ...options, headers });
        if (response.redirected && new URL(response.url).pathname.endsWith('/admin/login')) {
            window.location.assign(response.url); throw new Error('세션이 만료되었습니다.');
        }
        if (response.status === 401) { window.location.assign('/admin/login'); throw new Error('세션이 만료되었습니다.'); }
        const body = response.status === 204 ? null : await response.json();
        if (!response.ok) throw new Error(body?.message || '요청을 처리하지 못했습니다.');
        return body;
    }

    const page = document.querySelector('[data-live-page]');
    const pageType = page?.dataset.livePage;
    let eventSource, reconnectTimer, mergeTimer, sessionCheckAt = 0, reconnectDelay = 1000, streamErrors = 0;
    let requestController, requestGeneration = 0, dirty = false, refreshing = false;
    let status, statusMessage, manualRefresh;

    function updateStatus(message, showButton = false) {
        if (!message) {
            if (status) status.hidden = true;
            return;
        }
        if (!status) {
            status = document.createElement('div');
            status.id = 'live-status';
            status.setAttribute('role', 'status');
            statusMessage = document.createElement('span');
            manualRefresh = document.createElement('button');
            manualRefresh.type = 'button';
            manualRefresh.textContent = '화면 새로고침';
            manualRefresh.addEventListener('click', refreshCurrentPage);
            status.append(statusMessage, manualRefresh);
            page?.prepend(status);
        }
        statusMessage.textContent = message;
        manualRefresh.hidden = !showButton;
        status.hidden = false;
    }

    function currentFragmentRequest() {
        const url = new URL(window.location.href);
        const query = new URLSearchParams(url.search);
        if (pageType === 'dashboard') {
            url.search = '?fragment=live';
        } else if (pageType === 'alerts') {
            url.search = '?fragment=list';
        } else if (pageType === 'events') {
            query.set('fragment', 'list');
            url.search = query.toString();
        } else if (pageType === 'workers') {
            query.set('fragment', 'list');
            url.search = query.toString();
        } else if (pageType === 'worker-detail') {
            query.set('fragment', 'live');
            url.search = query.toString();
        } else if (pageType === 'reports') {
            query.set('fragment', 'live');
            url.search = query.toString();
        }
        return url;
    }

    function currentRoot() {
        if (pageType === 'dashboard') return document.getElementById('dashboard-live');
        if (pageType === 'alerts') return document.getElementById('alert-list');
        if (pageType === 'events') return document.getElementById('event-list');
        if (pageType === 'workers') return document.getElementById('worker-list');
        if (pageType === 'worker-detail') return document.getElementById('worker-detail-live');
        if (pageType === 'reports') return document.getElementById('report-live');
        return null;
    }

    function preserveValues(root) {
        const values = {};
        root?.querySelectorAll('#report-filter input, #report-filter select').forEach(element => {
            if (element.name) values[element.name] = element.value;
        });
        return values;
    }

    function restoreValues(root, values) {
        for (const [name, value] of Object.entries(values)) {
            const field = root.querySelector(`#report-filter [name="${CSS.escape(name)}"]`);
            if (field) field.value = value;
        }
    }

    async function fetchFragment(url, signal) {
        const response = await fetch(url, {
            credentials: 'same-origin',
            headers: { Accept: 'text/html' },
            signal
        });
        if (response.redirected && new URL(response.url).pathname.endsWith('/admin/login')) {
            window.location.assign(response.url);
            throw new Error('세션이 만료되었습니다.');
        }
        if (response.status === 401) {
            window.location.assign('/admin/login');
            throw new Error('세션이 만료되었습니다.');
        }
        if (response.status === 404 && pageType === 'worker-detail') return { missing: true };
        if (!response.ok || !response.headers.get('Content-Type')?.includes('text/html')) {
            throw new Error('화면을 갱신하지 못했습니다.');
        }
        const parsed = new DOMParser().parseFromString(await response.text(), 'text/html');
        const rootId = pageType === 'dashboard' ? 'dashboard-live'
            : pageType === 'alerts' ? 'alert-list'
            : pageType === 'events' ? 'event-list'
            : pageType === 'workers' ? 'worker-list'
            : pageType === 'worker-detail' ? 'worker-detail-live'
            : 'report-live';
        const root = parsed.getElementById(rootId);
        if (!root) throw new Error('갱신할 화면 영역을 찾지 못했습니다.');
        return { root };
    }

    async function refreshCurrentPage() {
        if (!pageType || document.visibilityState === 'prerender') return;
        dirty = true;
        if (refreshing) return;
        refreshing = true;
        if (mergeTimer) { clearTimeout(mergeTimer); mergeTimer = null; }
        try {
            while (dirty) {
                dirty = false;
                const generation = ++requestGeneration;
                requestController?.abort();
                requestController = new AbortController();
                const target = currentRoot();
                const values = preserveValues(target);
                const memoForm = document.querySelector('#detail .process-event-form');
                const memo = memoForm?.elements.adminMemo?.value;
                const response = await fetchFragment(currentFragmentRequest(), requestController.signal);
                if (generation !== requestGeneration || !page.isConnected) continue;
                if (response.missing) {
                    const root = currentRoot();
                    if (root) {
                        const message = document.createElement('section');
                        message.id = 'worker-detail-live';
                        message.className = 'surface';
                        message.innerHTML = '<p>작업자 정보가 없거나 탈퇴 처리되었습니다.</p><a href="/admin/workers">작업자 목록으로 이동</a>';
                        root.replaceWith(message);
                    }
                } else {
                    restoreValues(response.root, values);
                    if (response.root.dataset.page !== undefined) {
                        const url = new URL(window.location.href);
                        if (url.searchParams.get('page') !== response.root.dataset.page) {
                            url.searchParams.set('page', response.root.dataset.page);
                            window.history.replaceState(null, '', url);
                        }
                    }
                    target?.replaceWith(response.root);
                    formatTimes(response.root);
                    if (pageType === 'reports') {
                        applyReportFilter();
                        syncReportExportButton();
                    }
                }
                if (memoForm && document.getElementById('detail')?.open) {
                    const eventId = memoForm.dataset.eventId;
                    if (eventId) await refreshOpenDetail(eventId, memo);
                }
            }
            updateStatus('');
        } catch (error) {
            if (error.name !== 'AbortError') updateStatus('자동 갱신에 실패했습니다. 수동으로 다시 시도할 수 있습니다.', true);
        } finally {
            refreshing = false;
            requestController = null;
            if (dirty) scheduleRefresh();
        }
    }

    function scheduleRefresh() {
        dirty = true;
        if (mergeTimer || refreshing) return;
        mergeTimer = window.setTimeout(() => {
            mergeTimer = null;
            refreshCurrentPage();
        }, 200);
    }

    async function refreshOpenDetail(eventId, memo) {
        const dialog = document.getElementById('detail');
        const body = document.getElementById('detail-body');
        if (!dialog?.open || !body) return;
        try {
            const response = await fetch(`/admin/events/${encodeURIComponent(eventId)}/detail`, {
                credentials: 'same-origin',
                headers: { Accept: 'text/html' }
            });
            if (response.redirected && new URL(response.url).pathname.endsWith('/admin/login')) {
                window.location.assign(response.url);
                return;
            }
            if (!response.ok) return;
            const parsed = new DOMParser().parseFromString(await response.text(), 'text/html');
            const fresh = parsed.querySelector('.surface');
            if (!fresh) return;
            if (memo) {
                const oldForm = body.querySelector('.process-event-form');
                const newForm = fresh.querySelector('.process-event-form');
                if (oldForm && !newForm) {
                    const message = document.createElement('p');
                    message.textContent = '다른 관리자가 이 이벤트를 처리했습니다. 작성 중인 메모는 전송되지 않았습니다.';
                    fresh.append(message);
                    oldForm.elements.adminMemo.value = memo;
                    oldForm.elements.adminMemo.readOnly = true;
                    oldForm.querySelector('button').disabled = true;
                    fresh.append(oldForm);
                } else if (oldForm && newForm) {
                    newForm.elements.adminMemo.value = memo;
                }
            }
            body.replaceChildren(...fresh.childNodes);
            formatTimes(body);
        } catch (_) { }
    }

    function refreshForEvent(type, data = {}) {
        if (!pageType) return;
        const workerPage = pageType === 'worker-detail';
        const workerId = Number(page?.dataset.workerId);
        if (workerPage) {
            if (data.workerId && Number(data.workerId) !== workerId) return;
        } else {
            const eventTypes = ['event-created', 'event-processed'];
            const workerTypes = ['worker-created', 'worker-updated', 'worker-deleted', 'worker-status-changed'];
            const interested = pageType === 'dashboard' || pageType === 'reports'
                ? eventTypes.includes(type) || workerTypes.includes(type)
                : pageType === 'workers' ? workerTypes.includes(type)
                : eventTypes.includes(type) || ['worker-updated', 'worker-deleted'].includes(type);
            if (!interested) return;
        }
        scheduleRefresh();
    }

    async function checkSession() {
        const now = Date.now();
        if (now - sessionCheckAt < 5000) return;
        sessionCheckAt = now;
        try {
            const response = await fetch('/api/admin/session', { credentials: 'same-origin', cache: 'no-store' });
            if (response.status === 401) {
                closeStream();
                window.location.assign('/admin/login');
            } else if (response.status === 403) {
                closeStream();
                updateStatus('관리자 권한이 없어 자동 갱신을 중단했습니다.', true);
            }
        } catch (_) { }
    }

    function connectStream() {
        if (!pageType || !window.EventSource || eventSource || document.visibilityState === 'prerender') return;
        eventSource = new EventSource('/api/admin/stream');
        eventSource.addEventListener('connected', () => {
            streamErrors = 0;
            reconnectDelay = 1000;
            updateStatus('');
            refreshCurrentPage();
        });
        ['event-created', 'event-processed', 'worker-created', 'worker-updated', 'worker-deleted', 'worker-status-changed']
            .forEach(type => eventSource.addEventListener(type, event => {
                let data = {};
                try { data = JSON.parse(event.data); } catch (_) { }
                refreshForEvent(type, data);
            }));
        eventSource.onerror = async () => {
            streamErrors++;
            updateStatus('실시간 연결을 복구하고 있습니다.');
            if (streamErrors >= 3) updateStatus('실시간 연결을 복구하고 있습니다. 수동으로 화면을 갱신할 수 있습니다.', true);
            await checkSession();
            if (eventSource?.readyState === EventSource.CLOSED) {
                eventSource.close();
                eventSource = null;
                clearTimeout(reconnectTimer);
                reconnectTimer = window.setTimeout(() => {
                    reconnectDelay = Math.min(reconnectDelay * 2, 30000);
                    connectStream();
                }, reconnectDelay);
            }
        };
    }

    function closeStream() {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
        eventSource?.close();
        eventSource = null;
    }

    function applyReportFilter() {
        const form = document.getElementById('report-filter');
        const table = document.getElementById('report-table');
        if (!form || !table) return;
        const q = form.elements.q.value.toLowerCase();
        const action = form.elements.action.value;
        [...table.querySelectorAll('tbody tr')].forEach(row => {
            if (row.querySelector('.empty')) return;
            row.hidden = (q && !row.textContent.toLowerCase().includes(q)) || (action && !row.cells[3].textContent.includes(action));
        });
    }

    function syncReportExportButton() {
        const button = document.getElementById('export-report');
        const table = document.getElementById('report-table');
        if (button && table) button.disabled = !table.querySelector('tbody tr:not(.empty)');
    }

    document.addEventListener('click', async event => {
        const resetButton = event.target.closest('.reset-password');
        if (resetButton) {
            resetButton.disabled = true;
            try {
                const result = await request(`/api/admin/workers/${resetButton.dataset.workerId}/reset-password`, { method: 'POST' });
                const credentials = document.getElementById('credentials');
                if (credentials) {
                    document.getElementById('created-summary').textContent = `작업자 ID: ${result.workerId}, 임시 비밀번호: ${result.temporaryPassword} (지금 전달하세요)`;
                    document.getElementById('created-worker-link').hidden = true;
                    credentials.hidden = false;
                }
                refreshForEvent('worker-status-changed', { workerId: Number(resetButton.dataset.workerId) });
            } catch (error) { announce(error.message); }
            finally { resetButton.disabled = false; }
            return;
        }

        const openButton = event.target.closest('.open-event');
        if (openButton) {
            try {
                const response = await fetch(`/admin/events/${encodeURIComponent(openButton.dataset.eventId)}/detail`, {
                    credentials: 'same-origin', headers: { Accept: 'text/html' }
                });
                if (response.redirected && new URL(response.url).pathname.endsWith('/admin/login')) {
                    window.location.assign(response.url);
                    return;
                }
                if (response.status === 401) { window.location.assign('/admin/login'); return; }
                if (!response.ok) throw new Error('이벤트 상세를 불러오지 못했습니다.');
                const body = document.getElementById('detail-body');
                body.innerHTML = await response.text();
                const detail = document.getElementById('detail');
                if (!detail.open) detail.showModal();
                formatTimes(body);
            } catch (error) { announce(error.message); }
            return;
        }

        if (event.target.closest('#close-detail')) document.getElementById('detail')?.close();
        if (event.target.closest('#report-search')) applyReportFilter();
        if (event.target.closest('#export-report')) exportReport();
    });

    document.addEventListener('submit', async event => {
        const workerForm = event.target.closest('#worker-form');
        if (workerForm) {
            event.preventDefault();
            const button = workerForm.querySelector('button[type="submit"]');
            const credentials = document.getElementById('credentials');
            const link = document.getElementById('created-worker-link');
            const errorMessage = document.getElementById('create-error');
            credentials.hidden = true;
            errorMessage.hidden = true;
            if (!workerForm.reportValidity()) return;
            button.disabled = true;
            try {
                const data = new FormData(workerForm);
                const workerName = String(data.get('workerName') || '').trim();
                const ageValue = String(data.get('age') || '').trim();
                if (!workerName || !ageValue) { workerForm.reportValidity(); return; }
                const result = await request('/api/admin/workers', {
                    method: 'POST', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ workerName, sex: data.get('sex'), age: Number(ageValue) })
                });
                document.getElementById('created-summary').textContent = `작업자 ID: ${result.workerId}, 임시 비밀번호: ${result.temporaryPassword} (지금 전달하세요)`;
                link.href = `/admin/workers/${encodeURIComponent(result.workerId)}`;
                link.hidden = false;
                credentials.hidden = false;
                workerForm.reset();
                refreshForEvent('worker-created', { workerId: Number(result.workerId) });
            } catch (error) {
                errorMessage.textContent = error.message;
                errorMessage.hidden = false;
            } finally { button.disabled = false; }
            return;
        }

        const form = event.target.closest('.process-event-form');
        if (!form) return;
        event.preventDefault();
        if (!form.reportValidity()) return;
        const button = form.querySelector('button');
        button.disabled = true;
        try {
            const adminMemo = new FormData(form).get('adminMemo');
            await request(`/api/admin/events/${form.dataset.eventId}/process`, {
                method: 'PATCH', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ adminMemo })
            });
            announce('이벤트 처리를 완료했습니다.');
            document.getElementById('detail')?.close();
            refreshForEvent('event-processed');
        } catch (error) {
            announce(error.message);
            button.disabled = false;
            if (error.message.includes('이미 처리된 이벤트')) refreshCurrentPage();
        }
    });

    function exportReport() {
        const table = document.getElementById('report-table');
        if (!table) return;
        const rows = [...table.querySelectorAll('tr')].filter(row => !row.hidden && !row.querySelector('.empty'))
            .map(row => [...row.cells].map(cell => cell.textContent));
        const quote = value => {
            const safe = String(value ?? '').replace(/^[\s\u0000-\u001f]*[=+\-@]/, match => `'${match}`);
            return `"${safe.replaceAll('"', '""')}"`;
        };
        const csv = '\ufeff' + rows.map(row => row.map(quote).join(',')).join('\r\n');
        const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
        const link = document.createElement('a');
        link.href = url;
        link.download = 'safety-event-report.csv';
        link.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    }

    if (pageType) {
        if (window.EventSource) connectStream();
        else updateStatus('이 브라우저에서는 실시간 자동 갱신을 사용할 수 없습니다.', true);
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'visible') {
                if (!eventSource) connectStream();
                refreshCurrentPage();
            }
        });
        window.addEventListener('pagehide', () => {
            closeStream();
            requestController?.abort();
            clearTimeout(mergeTimer);
        });
        window.addEventListener('pageshow', event => {
            if (event.persisted) {
                connectStream();
                refreshCurrentPage();
            }
        });
    }
})();

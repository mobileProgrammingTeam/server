(() => {
    const csrfToken = document.querySelector('meta[name="csrf-token"]')?.content;
    const csrfHeader = document.querySelector('meta[name="csrf-header"]')?.content;
    const time = value => value ? new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'medium', timeZone: 'Asia/Seoul' }).format(new Date(value)) : '-';
    document.querySelectorAll('[data-time]').forEach(element => { element.textContent = time(element.dataset.time); });
    const notice = document.getElementById('notice') || document.getElementById('message');
    const announce = value => { if (notice) notice.textContent = value; };

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

    async function workerListFragment(page) {
        const response = await fetch(`/admin/workers?fragment=list&page=${encodeURIComponent(page)}`, {
            credentials: 'same-origin', headers: { Accept: 'text/html' }
        });
        if (response.redirected && new URL(response.url).pathname.endsWith('/admin/login')) {
            window.location.assign(response.url); throw new Error('세션이 만료되었습니다.');
        }
        if (response.status === 401) { window.location.assign('/admin/login'); throw new Error('세션이 만료되었습니다.'); }
        if (!response.ok) throw new Error('작업자 목록을 갱신하지 못했습니다.');
        return response.text();
    }

    const workerPage = document.getElementById('workers-page');
    if (workerPage) {
        workerPage.querySelectorAll('.reset-password').forEach(button => button.addEventListener('click', async () => {
            button.disabled = true;
            try {
                const result = await request(`/api/admin/workers/${button.dataset.workerId}/reset-password`, { method: 'POST' });
                const credentials = document.getElementById('credentials');
                document.getElementById('created-summary').textContent = `작업자 ID: ${result.workerId}, 임시 비밀번호: ${result.temporaryPassword} (지금 전달하세요)`;
                document.getElementById('created-worker-link').hidden = true;
                credentials.hidden = false;
            } catch (error) { announce(error.message); }
            finally { button.disabled = false; }
        }));

        const form = document.getElementById('worker-form');
        form.addEventListener('submit', async event => {
            event.preventDefault();
            const button = form.querySelector('button[type="submit"]');
            const credentials = document.getElementById('credentials');
            const link = document.getElementById('created-worker-link');
            const errorMessage = document.getElementById('create-error');
            credentials.hidden = true; errorMessage.hidden = true;
            if (!form.reportValidity()) return;
            button.disabled = true;
            try {
                const data = new FormData(form);
                const workerName = String(data.get('workerName') || '').trim();
                const ageValue = String(data.get('age') || '').trim();
                if (!workerName || !ageValue) { form.reportValidity(); return; }
                const result = await request('/api/admin/workers', { method: 'POST', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ workerName, sex: data.get('sex'), age: Number(ageValue) }) });
                document.getElementById('created-summary').textContent = `작업자 ID: ${result.workerId}, 임시 비밀번호: ${result.temporaryPassword} (지금 전달하세요)`;
                link.href = `/admin/workers/${encodeURIComponent(result.workerId)}`;
                link.hidden = false; credentials.hidden = false; form.reset();
                try {
                    const list = document.getElementById('worker-list');
                    const html = await workerListFragment(Number(list.dataset.page || 0));
                    const parsed = new DOMParser().parseFromString(html, 'text/html').querySelector('#worker-list');
                    if (!parsed) throw new Error('작업자 목록을 갱신하지 못했습니다.');
                    list.replaceWith(parsed);
                } catch (_) {
                    errorMessage.textContent = `작업자는 생성되었으나 목록을 갱신하지 못했습니다. ID ${result.workerId}로 확인하세요.`;
                    errorMessage.hidden = false;
                }
            } catch (error) { errorMessage.textContent = error.message; errorMessage.hidden = false; }
            finally { button.disabled = false; }
        });
    }

    const detail = document.getElementById('detail');
    const closeDetail = document.getElementById('close-detail');
    if (detail && closeDetail) {
        closeDetail.addEventListener('click', () => detail.close());
        detail.addEventListener('close', () => { document.getElementById('detail-body').replaceChildren(); });
        document.querySelectorAll('.open-event').forEach(button => button.addEventListener('click', async () => {
            try {
                const response = await fetch(`/admin/events/${encodeURIComponent(button.dataset.eventId)}/detail`, { credentials: 'same-origin' });
                if (response.redirected) { window.location.assign(response.url); return; }
                if (!response.ok) throw new Error('이벤트 상세를 불러오지 못했습니다.');
                const body = document.getElementById('detail-body');
                body.innerHTML = await response.text();
                if (!detail.open) detail.showModal();
                bindProcessForm(body);
            } catch (error) { announce(error.message); }
        }));
    }

    function bindProcessForm(root = document) {
        root.querySelectorAll('.process-event-form:not([data-bound])').forEach(form => {
            form.dataset.bound = 'true';
            form.addEventListener('submit', async event => {
                event.preventDefault();
                if (!form.reportValidity()) return;
                const button = form.querySelector('button'); button.disabled = true;
                try {
                    const adminMemo = new FormData(form).get('adminMemo');
                    await request(`/api/admin/events/${form.dataset.eventId}/process`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ adminMemo }) });
                    announce('이벤트 처리를 완료했습니다.'); if (detail?.open) detail.close(); window.location.reload();
                } catch (error) { announce(error.message); button.disabled = false; }
            });
        });
    }
    bindProcessForm(document);

    const reportRows = [...document.querySelectorAll('#report-table tbody tr')].filter(row => !row.querySelector('.empty'));
    document.getElementById('report-search')?.addEventListener('click', () => {
        const form = document.getElementById('report-filter'), q = form.elements.q.value.toLowerCase(), action = form.elements.action.value;
        reportRows.forEach(row => { row.hidden = (q && !row.textContent.toLowerCase().includes(q)) || (action && !row.cells[3].textContent.includes(action)); });
    });
    document.getElementById('export-report')?.addEventListener('click', () => {
        const rows = [...document.querySelectorAll('#report-table tr')].filter(row => !row.hidden && !row.querySelector('.empty')).map(row => [...row.cells].map(cell => cell.textContent));
        const quote = value => { const safe = String(value ?? '').replace(/^[\s\u0000-\u001f]*[=+\-@]/, match => `'${match}`); return `"${safe.replaceAll('"', '""')}"`; };
        const csv = '\uFEFF' + rows.map(row => row.map(quote).join(',')).join('\r\n');
        const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
        const link = document.createElement('a'); link.href = url; link.download = 'safety-event-report.csv'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    });

    if (document.getElementById('events-page')) window.setInterval(() => window.location.reload(), 5000);
})();

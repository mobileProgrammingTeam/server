(() => {
    const token = document.querySelector('meta[name="csrf-token"]')?.content;
    const header = document.querySelector('meta[name="csrf-header"]')?.content;
    const time = value => value ? new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'medium', timeZone: 'Asia/Seoul' }).format(new Date(value)) : '-';
    document.querySelectorAll('[data-time]').forEach(element => { element.textContent = time(element.dataset.time); });
    const message = document.getElementById('message');
    const cell = (row, value) => { const td = document.createElement('td'); td.textContent = value ?? '-'; row.append(td); return td; };
    async function request(url, options = {}) {
        const headers = { ...(options.headers || {}) };
        if (options.method && options.method !== 'GET') headers[header] = token;
        const response = await fetch(url, { credentials: 'same-origin', ...options, headers });
        if (response.status === 401) { window.location.assign('/admin/login'); throw new Error('세션이 만료되었습니다.'); }
        const body = response.status === 204 ? null : await response.json();
        if (!response.ok) throw new Error(body?.message || '요청을 처리하지 못했습니다.');
        return body;
    }
    function setupPager(load) {
        let page = 0, total = 0;
        const previous = document.getElementById('previous'), next = document.getElementById('next');
        const update = result => { total = result.totalPages; document.getElementById('page-info').textContent = `${result.page + 1} / ${Math.max(total, 1)} 페이지`; previous.disabled = page === 0; next.disabled = page + 1 >= total; };
        previous.addEventListener('click', () => { if (page > 0) { page--; load(); } });
        next.addEventListener('click', () => { if (page + 1 < total) { page++; load(); } });
        return { page: () => page, reset: () => { page = 0; }, update };
    }
    if (document.getElementById('events-page')) {
        let loading = false;
        const pager = setupPager(load);
        async function load() {
            if (loading) return;
            loading = true;
            try {
                const query = new URLSearchParams({ page: String(pager.page()) });
                for (const [key, id] of [['processed', 'processed'], ['workerId', 'worker-filter'], ['eventType', 'type-filter']]) {
                    const value = document.getElementById(id).value;
                    if (value) query.set(key, value);
                }
                const result = await request(`/api/admin/events?${query}`);
                const tbody = document.getElementById('events-body'); tbody.replaceChildren();
                result.items.forEach(event => {
                    const row = document.createElement('tr');
                    cell(row, time(event.time)); cell(row, event.eventType); cell(row, `${event.workerName} (${event.workerId})`);
                    cell(row, event.alertSender); cell(row, event.processedAt ? '완료' : '미처리'); cell(row, time(event.processedAt));
                    cell(row, event.adminMemo);
                    const actions = cell(row, '');
                    if (!event.processedAt) {
                        const button = document.createElement('button'); button.textContent = '처리 완료';
                        button.addEventListener('click', async () => {
                            const adminMemo = window.prompt('처리 메모를 입력하세요 (최대 500자)');
                            if (adminMemo === null) return;
                            button.disabled = true;
                            try { await request(`/api/admin/events/${event.eventId}/process`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ adminMemo }) }); await load(); }
                            catch (error) { message.textContent = error.message; button.disabled = false; }
                        });
                        actions.append(button);
                    }
                    tbody.append(row);
                });
                pager.update(result); message.textContent = '';
            } catch (error) { message.textContent = error.message; }
            finally { loading = false; }
        }
        document.getElementById('event-search').addEventListener('click', () => { pager.reset(); load(); });
        load(); setInterval(load, 5000);
    }
    if (document.getElementById('workers-page')) {
        const credentials = document.getElementById('credentials');
        const pager = setupPager(load);
        async function load() {
            try {
                const result = await request(`/api/admin/workers?page=${pager.page()}`);
                const tbody = document.getElementById('workers-body'); tbody.replaceChildren();
                result.items.forEach(worker => {
                    const row = document.createElement('tr');
                    cell(row, worker.workerId); cell(row, worker.workerName); cell(row, worker.sex); cell(row, worker.age); cell(row, worker.enabled ? '활성' : '탈퇴');
                    const actions = cell(row, '');
                    if (worker.enabled) {
                        const button = document.createElement('button'); button.textContent = '비밀번호 초기화';
                        button.addEventListener('click', async () => {
                            button.disabled = true; credentials.textContent = '';
                            try { const result = await request(`/api/admin/workers/${worker.workerId}/reset-password`, { method: 'POST' }); credentials.textContent = `작업자 ID: ${result.workerId}, 임시 비밀번호: ${result.temporaryPassword} (지금 전달하세요)`; }
                            catch (error) { message.textContent = error.message; }
                            finally { button.disabled = false; }
                        });
                        actions.append(button);
                    }
                    tbody.append(row);
                });
                pager.update(result); message.textContent = '';
            } catch (error) { message.textContent = error.message; }
        }
        document.getElementById('worker-form').addEventListener('submit', async event => {
            event.preventDefault(); credentials.textContent = '';
            const form = event.currentTarget; const button = form.querySelector('button'); button.disabled = true;
            try {
                const data = new FormData(form);
                const result = await request('/api/admin/workers', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ workerName: data.get('workerName'), sex: data.get('sex'), age: Number(data.get('age')) }) });
                credentials.textContent = `작업자 ID: ${result.workerId}, 임시 비밀번호: ${result.temporaryPassword} (지금 전달하세요)`;
                form.reset(); pager.reset(); await load();
            } catch (error) { message.textContent = error.message; }
            finally { button.disabled = false; }
        });
        load();
    }
})();

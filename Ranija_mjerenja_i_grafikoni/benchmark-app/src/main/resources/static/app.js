const select = document.querySelector('#experiment-select');
const context = document.querySelector('#experiment-context');
const cards = document.querySelector('#cards');
const summaryBody = document.querySelector('#summary-body');
const auditList = document.querySelector('#audit-list');
const failureState = document.querySelector('#failure-state');

const number = value => Number.parseFloat(value || '0');
const integer = value => Number.parseInt(value || '0', 10);
const format = value => new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 }).format(value);

async function getJson(url) {
    const response = await fetch(url);
    if (!response.ok) throw new Error(`Request failed: ${response.status}`);
    return response.json();
}

function average(rows, field) {
    return rows.reduce((sum, row) => sum + number(row[field]), 0) / rows.length;
}

function groupMeasurements(rows) {
    const groups = new Map();
    rows
        .filter(row => row.run_type === 'MEASUREMENT' && row.operation !== 'DATA_PRELOAD')
        .forEach(row => {
            const key = `${row.operation}|${row.workload_profile}`;
            if (!groups.has(key)) groups.set(key, []);
            groups.get(key).push(row);
        });
    return [...groups.values()];
}

function renderCards(experiment, rows) {
    const measured = rows.filter(row => row.run_type === 'MEASUREMENT' && row.operation !== 'DATA_PRELOAD');
    const failures = measured.reduce((sum, row) => sum + integer(row.failed_operations), 0);
    const values = [
        ['Database', experiment.database],
        ['Test series', experiment.test_series],
        ['Cluster / consistency', `${experiment.cluster_node_count} nodes · ${experiment.consistency_level}`],
        ['Measurement failures', failures.toString()]
    ];
    cards.innerHTML = values.map(([label, value]) => `<article class="card"><div class="label">${label}</div><div class="value">${value}</div></article>`).join('');
}

function renderSummary(rows) {
    const groups = groupMeasurements(rows);
    summaryBody.innerHTML = groups.map(group => {
        const first = group[0];
        const failed = group.reduce((sum, row) => sum + integer(row.failed_operations), 0);
        return `<tr>
            <td>${first.operation}</td><td>${first.workload_profile}</td><td>${group.length}</td>
            <td>${format(average(group, 'throughput_ops_sec'))}</td>
            <td>${format(average(group, 'average_latency_ms'))}</td>
            <td>${format(average(group, 'p95_ms'))}</td><td>${format(average(group, 'p99_ms'))}</td><td>${failed}</td>
        </tr>`;
    }).join('') || '<tr><td colspan="8">No measurement rows found.</td></tr>';
}

function renderAudit(events, experimentId) {
    const matching = events.filter(event => event.experiment_id === experimentId);
    auditList.innerHTML = matching.map(event => {
        const status = event.event_type.toLowerCase();
        const detail = event.detail ? ` — ${event.detail}` : '';
        return `<li><span class="status ${status}">${event.event_type}</span>${event.event_at_utc}${detail}</li>`;
    }).join('') || '<li class="empty">No lifecycle event was recorded for this legacy pilot.</li>';
}

function renderFailures(failures) {
    if (!failures.length) {
        failureState.textContent = 'No per-operation failures were recorded for this experiment.';
        return;
    }
    failureState.textContent = `${failures.length} failure records exist. Inspect benchmark-failures-v2.csv for full detail.`;
}

async function loadExperiment(experiment) {
    const [rows, events, failures] = await Promise.all([
        getJson(`/api/results?experimentId=${encodeURIComponent(experiment.experiment_id)}`),
        getJson('/api/events'),
        getJson(`/api/failures?experimentId=${encodeURIComponent(experiment.experiment_id)}`)
    ]);
    context.textContent = `${experiment.experiment_id} · RF ${experiment.replication_factor} · ${experiment.lifecycle_status}`;
    renderCards(experiment, rows);
    renderSummary(rows);
    renderAudit(events, experiment.experiment_id);
    renderFailures(failures);
}

async function start() {
    try {
        const experiments = await getJson('/api/experiments');
        select.innerHTML = experiments.map(experiment => `<option value="${experiment.experiment_id}">${experiment.database.toUpperCase()} · ${experiment.test_series} · ${experiment.experiment_id}</option>`).join('');
        if (!experiments.length) {
            context.textContent = 'No saved experiment metadata exists yet.';
            return;
        }
        const byId = new Map(experiments.map(experiment => [experiment.experiment_id, experiment]));
        select.addEventListener('change', () => loadExperiment(byId.get(select.value)));
        await loadExperiment(experiments[0]);
    } catch (error) {
        context.textContent = `Dashboard could not load evidence: ${error.message}`;
    }
}

start();

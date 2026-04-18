// SENSEI Safety Dashboard — SSE + REST client.
(function() {
    'use strict';

    let eventCount = 0;
    let eventSource = null;
    const MAX_LOG_ENTRIES = 100;
    const MAX_ESCALATION_LOG = 20;

    const SENSOR_TYPES = ['MQ2','MQ3','MQ5','MQ6','MQ7','MQ8','MQ135'];
    const SENSOR_MAXES = {
        MQ2: 2000, MQ3: 1000, MQ5: 1500, MQ6: 1500,
        MQ7: 100, MQ8: 2000, MQ135: 800
    };

    function connect() {
        if (eventSource) eventSource.close();
        eventSource = new EventSource('/events');

        eventSource.addEventListener('incident', function(e) {
            try {
                const data = JSON.parse(e.data);
                eventCount++;
                updateDashboard(data);
            } catch (err) {
                console.error('Failed to parse incident event:', err);
            }
        });

        eventSource.onopen = function() {
            const el = document.getElementById('connection-status');
            el.className = 'status-badge connected';
            el.textContent = 'Connected';
        };

        eventSource.onerror = function() {
            const el = document.getElementById('connection-status');
            el.className = 'status-badge disconnected';
            el.textContent = 'Reconnecting...';
        };
    }

    function updateDashboard(data) {
        document.getElementById('event-count').textContent = 'Events: ' + eventCount;
        document.getElementById('timestamp').textContent = new Date(data.timestampMs).toLocaleTimeString();
        updateAgentStatus(data.agentStates);
        updateSensorPanel(data.sensorValues, data.maxTemp, data.avgTemp);
        updateClassificationPanel(data.classificationLabel, data.classificationConfidence);
        updateEscalationPanel(data.escalationTier, data.escalationAction);
        updateLLMPanel(data.llmAnalysis, data.llmRecommendation, data.similarIncidents);
        addLogEntry(data);
    }

    function updateAgentStatus(agentStates) {
        if (!agentStates) return;
        for (const [agent, status] of Object.entries(agentStates)) {
            const el = document.getElementById('agent-' + agent);
            if (el) el.querySelector('.status-dot').className = 'status-dot ' + status;
        }
    }

    function updateSensorPanel(sensorValues, maxTemp, avgTemp) {
        if (sensorValues) {
            for (const [sensor, value] of Object.entries(sensorValues)) {
                const bar = document.getElementById('bar-' + sensor);
                const val = document.getElementById('val-' + sensor);
                if (!bar || !val) continue;
                const maxVal = SENSOR_MAXES[sensor] || 1000;
                const pct = Math.min((value / maxVal) * 100, 100);
                bar.style.width = pct + '%';
                val.textContent = (value || 0).toFixed(1);
                bar.className = 'sensor-bar';
                if (pct > 80) bar.classList.add('danger');
                else if (pct > 50) bar.classList.add('warning');
            }
        }
        if (maxTemp !== undefined) {
            document.getElementById('thermal-max').textContent = maxTemp.toFixed(1);
            document.getElementById('thermal-avg').textContent = avgTemp.toFixed(1);
            const anomalyEl = document.getElementById('thermal-anomaly');
            if (maxTemp > 50) {
                anomalyEl.textContent = 'ANOMALY';
                anomalyEl.className = 'thermal-anomaly';
            } else {
                anomalyEl.textContent = 'Normal';
                anomalyEl.className = 'thermal-normal';
            }
        }
    }

    function updateClassificationPanel(label, confidence) {
        const labelEl = document.getElementById('class-label');
        labelEl.textContent = label || 'NO_GAS';
        labelEl.className = 'classification-label';
        if (label === 'SMOKE') labelEl.classList.add('smoke');
        else if (label === 'PERFUME') labelEl.classList.add('perfume');
        else if (label === 'COMBINED') labelEl.classList.add('combined');

        const confPct = ((confidence || 0) * 100);
        document.getElementById('confidence-bar').style.width = confPct + '%';
        document.getElementById('confidence-value').textContent = confPct.toFixed(0) + '%';

        const labels = ['NO_GAS', 'SMOKE', 'PERFUME', 'COMBINED'];
        labels.forEach(function(lbl) {
            const bar = document.getElementById('prob-' + lbl);
            const valEl = document.getElementById('probval-' + lbl);
            if (!bar || !valEl) return;
            const p = (lbl === label) ? confPct : ((100 - confPct) / 3);
            bar.style.width = p + '%';
            valEl.textContent = p.toFixed(0) + '%';
        });
    }

    function updateEscalationPanel(tier, action) {
        const tierEl = document.getElementById('escalation-tier');
        tierEl.textContent = tier || 'NONE';
        tierEl.className = 'escalation-tier';
        if (tier === 'T1_LOG') tierEl.classList.add('t1');
        else if (tier === 'T2_ALERT') tierEl.classList.add('t2');
        else if (tier === 'T3_SHUTDOWN') tierEl.classList.add('t3');

        document.getElementById('escalation-action').textContent = action || 'Normal operations';

        if (tier && tier !== 'NONE') {
            const log = document.getElementById('escalation-log');
            const entry = document.createElement('div');
            const tierClass = tier === 'T1_LOG' ? 't1' : tier === 'T2_ALERT' ? 't2' : 't3';
            entry.className = 'escalation-entry ' + tierClass;
            entry.innerHTML = '<span style="color:#64748b">' + new Date().toLocaleTimeString() +
                '</span> [' + tier + '] ' + (action || '').substring(0, 60);
            log.insertBefore(entry, log.firstChild);
            while (log.children.length > MAX_ESCALATION_LOG) log.removeChild(log.lastChild);
        }
    }

    function updateLLMPanel(analysis, recommendation, similarIncidents) {
        document.getElementById('llm-analysis').textContent = analysis || 'Waiting for hazard events...';
        document.getElementById('llm-recommendation').textContent = recommendation || '--';

        const container = document.getElementById('similar-incidents');
        if (similarIncidents && similarIncidents.length > 0) {
            container.innerHTML = '';
            similarIncidents.forEach(function(inc) {
                const item = document.createElement('div');
                item.className = 'incident-item';
                item.innerHTML = '<div class="score">Similarity: ' +
                    ((inc.similarityScore || 0) * 100).toFixed(0) + '%</div>' +
                    '<div>' + (inc.description || '') + '</div>' +
                    '<div style="color:#64748b;font-size:0.7rem;margin-top:4px">' +
                    (inc.resolution || '') + '</div>';
                container.appendChild(item);
            });
        }
    }

    function addLogEntry(data) {
        const log = document.getElementById('event-log');
        const entry = document.createElement('div');
        entry.className = 'log-entry';
        const classLower = (data.classificationLabel || 'no_gas').toLowerCase();
        entry.innerHTML =
            '<span class="log-time">' + new Date(data.timestampMs).toLocaleTimeString() + '</span>' +
            '<span class="log-id">' + (data.id || '--') + '</span>' +
            '<span class="log-class ' + classLower + '">' + (data.classificationLabel || 'NO_GAS') + '</span>' +
            '<span>' + ((data.classificationConfidence || 0) * 100).toFixed(0) + '%</span>' +
            '<span class="log-tier">' + (data.escalationTier || 'NONE') + '</span>';
        log.insertBefore(entry, log.firstChild);
        while (log.children.length > MAX_LOG_ENTRIES) log.removeChild(log.lastChild);
    }

    // Fault-tolerance demo: build sensor kill grid + status poll.
    function initFaultPanel() {
        const grid = document.getElementById('fault-grid');
        SENSOR_TYPES.forEach(function(sensor) {
            const div = document.createElement('div');
            div.className = 'fault-item';
            div.dataset.sensor = sensor;
            div.innerHTML = '<span>' + sensor + '</span><span class="fault-status">alive</span>';
            div.addEventListener('click', function() {
                fetch('/api-fault/kill/' + sensor, { method: 'POST' })
                    .then(r => r.json()).then(j => console.log('killed', j));
            });
            grid.appendChild(div);
        });
        setInterval(refreshFaultStatus, 1500);
    }

    function refreshFaultStatus() {
        fetch('/api-fault/status').then(r => r.json()).then(j => {
            const shards = j.shards || {};
            for (const [sensor, info] of Object.entries(shards)) {
                const item = document.querySelector('.fault-item[data-sensor="' + sensor + '"]');
                if (!item) continue;
                const status = item.querySelector('.fault-status');
                status.textContent = info.status;
                status.className = 'fault-status ' + info.status;
            }
        }).catch(() => {});
    }

    // Operator chat → /api-chat
    function initChat() {
        const input = document.getElementById('chat-input');
        const send = document.getElementById('chat-send');
        const out = document.getElementById('chat-output');

        function submit() {
            const q = input.value.trim();
            if (!q) return;
            input.value = '';
            const userMsg = document.createElement('div');
            userMsg.className = 'chat-msg-user';
            userMsg.textContent = 'You: ' + q;
            out.appendChild(userMsg);
            out.scrollTop = out.scrollHeight;

            fetch('/api-chat', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ query: q, conversationId: 'dashboard' })
            }).then(r => r.json()).then(j => {
                const botMsg = document.createElement('div');
                botMsg.className = 'chat-msg-bot';
                botMsg.textContent = j.answer || '(no answer)';
                out.appendChild(botMsg);
                out.scrollTop = out.scrollHeight;
            }).catch(err => {
                const botMsg = document.createElement('div');
                botMsg.className = 'chat-msg-bot';
                botMsg.textContent = 'Error: ' + err.message;
                out.appendChild(botMsg);
            });
        }
        send.addEventListener('click', submit);
        input.addEventListener('keypress', e => { if (e.key === 'Enter') submit(); });
    }

    connect();
    initFaultPanel();
    initChat();

    document.addEventListener('visibilitychange', function() {
        if (!document.hidden && (!eventSource || eventSource.readyState === 2)) connect();
    });
})();
